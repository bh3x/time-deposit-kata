package org.ikigaidigital.timedeposit.application.port.in;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;

/**
 * Driving port: applies one month of interest to every stored time deposit and stores the new balances
 * ({@code POST /balance-updates}, ADR-0004). Runs are serialised and all-or-nothing (ADR-0012).
 */
public interface ApplyMonthlyInterestUseCase {

    /** Applies interest again on every call; always {@link BalanceUpdateResult.Succeeded}, or throws. */
    BalanceUpdateResult applyMonthlyInterest();

    /**
     * Applies interest once per idempotency key: a repeated key replays the stored outcome, a key whose request is
     * still in progress gives {@link BalanceUpdateResult.KeyInProgress}, and a failed request stores nothing, so a
     * retry runs (ADR-0004, ADR-0012). The key is used exactly as given; its length is checked by the caller.
     */
    BalanceUpdateResult applyMonthlyInterest(String idempotencyKey);
}
