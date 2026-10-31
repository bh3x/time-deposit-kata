package org.ikigaidigital.timedeposit.domain;

import org.ikigaidigital.TimeDeposit;

import java.util.Objects;

/**
 * Exposes a legacy {@link TimeDeposit} through {@link TimeDepositTerms} only (ADR-0008, decision 4). It delegates
 * instead of copying, so there is a single deposit model, and it keeps the wrapped object private so nobody can reach
 * {@code setBalance}. Equality is identity, like {@code TimeDeposit} itself.
 */
public final class ReadOnlyTimeDeposit implements TimeDepositTerms {

    private final TimeDeposit deposit;

    public ReadOnlyTimeDeposit(TimeDeposit deposit) {
        this.deposit = Objects.requireNonNull(deposit, "deposit");
    }

    @Override
    public String getPlanType() {
        return deposit.getPlanType();
    }

    // Not null-checked: the balance column is NOT NULL (ADR-0006), so stored deposits always have one.
    @Override
    public Double getBalance() {
        return deposit.getBalance();
    }

    @Override
    public int getDays() {
        return deposit.getDays();
    }
}
