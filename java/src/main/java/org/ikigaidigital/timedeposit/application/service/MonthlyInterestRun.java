package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.MonthInProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.Succeeded;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositsPort;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;
import org.ikigaidigital.timedeposit.application.port.out.SaveTimeDepositsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * One month's balance update run in one transaction (ADR-0012, A and E; ADR-0013): lock the month row, lock all
 * deposits, apply the legacy calculator, write the balances and switch the month to completed — all or nothing. A
 * plain {@code @Service}, not a {@code @UseCase}; not final, because Spring proxies it for {@code @Transactional}.
 */
@Service
public class MonthlyInterestRun {

    private final LoadTimeDepositsPort loadDeposits;
    private final SaveTimeDepositsPort saveDeposits;
    private final RecordBalanceUpdatePort periods;
    private final TimeDepositCalculator calculator;
    private final Clock clock;

    public MonthlyInterestRun(LoadTimeDepositsPort loadDeposits, SaveTimeDepositsPort saveDeposits,
                              RecordBalanceUpdatePort periods, TimeDepositCalculator calculator, Clock clock) {
        this.loadDeposits = Objects.requireNonNull(loadDeposits, "loadDeposits");
        this.saveDeposits = Objects.requireNonNull(saveDeposits, "saveDeposits");
        this.periods = Objects.requireNonNull(periods, "periods");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The run behind the fence (ADR-0012, C): it locks the month row first, waiting for a run that holds it, and runs
     * only while the month is still in progress with this request's token. The switch to completed is the last
     * statement and must match exactly one row.
     */
    @Transactional
    public BalanceUpdateResult apply(YearMonth period, Instant token) {
        var state = periods.lock(period);
        if (state.isEmpty()) {
            return new MonthInProgress();
        }
        return switch (state.get()) {
            case Completed completed -> new Succeeded(completed.outcome());
            case InProgress inProgress when !inProgress.token().equals(token) -> new MonthInProgress();
            case InProgress inProgress -> {
                BalanceUpdateOutcome outcome = applyToAllDeposits();
                if (!periods.complete(period, token, outcome, now())) {
                    throw new IllegalStateException("balance update period " + period + " changed during the run");
                }
                yield new Succeeded(outcome);
            }
        };
    }

    // processedCount counts every deposit, including those whose balance did not change (ADR-0004).
    private BalanceUpdateOutcome applyToAllDeposits() {
        List<TimeDeposit> deposits = loadDeposits.loadAllForUpdate();
        Instant executedAt = now();
        calculator.updateBalance(deposits);
        saveDeposits.saveBalances(deposits);
        return new BalanceUpdateOutcome(deposits.size(), executedAt);
    }

    // Microseconds, the precision of timestamptz, so a replay returns exactly the stored value (ADR-0006).
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
