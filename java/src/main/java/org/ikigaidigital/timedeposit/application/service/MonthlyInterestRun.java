package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.KeyInProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.Succeeded;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositsPort;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;
import org.ikigaidigital.timedeposit.application.port.out.SaveTimeDepositsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * One balance update run in one transaction (ADR-0012, A, C and E): lock all deposits, apply the legacy calculator,
 * write the balances and, for a keyed request, switch the key to completed — all or nothing. A plain
 * {@code @Service}, not a {@code @UseCase}; not final, because Spring proxies it for {@code @Transactional}.
 */
@Service
public class MonthlyInterestRun {

    private final LoadTimeDepositsPort loadDeposits;
    private final SaveTimeDepositsPort saveDeposits;
    private final RecordBalanceUpdatePort records;
    private final TimeDepositCalculator calculator;
    private final Clock clock;

    public MonthlyInterestRun(LoadTimeDepositsPort loadDeposits, SaveTimeDepositsPort saveDeposits,
                              RecordBalanceUpdatePort records, TimeDepositCalculator calculator, Clock clock) {
        this.loadDeposits = Objects.requireNonNull(loadDeposits, "loadDeposits");
        this.saveDeposits = Objects.requireNonNull(saveDeposits, "saveDeposits");
        this.records = Objects.requireNonNull(records, "records");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public BalanceUpdateOutcome apply() {
        return applyToAllDeposits();
    }

    /**
     * The keyed run behind the fence (ADR-0012, C): it locks the key row first, waiting for a run that holds it, and
     * runs only while the key is still in progress with this request's token. The switch to completed is the last
     * statement and must match exactly one row.
     */
    @Transactional
    public BalanceUpdateResult apply(String key, Instant token) {
        var state = records.lock(key);
        if (state.isEmpty()) {
            return new KeyInProgress();
        }
        return switch (state.get()) {
            case Completed completed -> new Succeeded(completed.outcome());
            case InProgress inProgress when !inProgress.token().equals(token) -> new KeyInProgress();
            case InProgress inProgress -> {
                BalanceUpdateOutcome outcome = applyToAllDeposits();
                if (!records.complete(key, token, outcome, now())) {
                    throw new IllegalStateException("idempotency key changed during the run");
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
