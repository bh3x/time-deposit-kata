package org.ikigaidigital.timedeposit.application.port.out;

import org.ikigaidigital.TimeDeposit;

import java.util.List;

/** Driven port: stores the balances a balance update run computed (ADR-0006, ADR-0012). */
public interface SaveTimeDepositsPort {

    /**
     * Writes the balance of each deposit, and nothing else, by its internal key, rounded to cents (ADR-0006). The
     * statements are executed before this method returns, so a later write in the same transaction (the switch of the
     * month to completed, ADR-0013) comes after them. Fails with {@link IllegalStateException} for a deposit that is
     * not stored, and with the database's error for a balance the column cannot hold.
     */
    void saveBalances(List<TimeDeposit> deposits);
}
