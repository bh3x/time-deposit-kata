package org.ikigaidigital.timedeposit.application.port.in;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;

/**
 * Driving port: applies one month of interest to every stored time deposit and stores the new balances
 * ({@code POST /balance-updates}, ADR-0004), at most once per UTC calendar month (ADR-0013). Runs are serialised and
 * all-or-nothing (ADR-0012).
 */
public interface ApplyMonthlyInterestUseCase {

    /**
     * The first request of a month applies the interest; every later request of that month gets the month's stored
     * outcome ({@link BalanceUpdateResult.Succeeded}), or {@link BalanceUpdateResult.MonthInProgress} while its run is
     * in progress. A failed run stores nothing, so a retry runs.
     */
    BalanceUpdateResult applyMonthlyInterest();
}
