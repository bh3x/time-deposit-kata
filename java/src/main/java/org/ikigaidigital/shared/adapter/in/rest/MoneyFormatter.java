package org.ikigaidigital.shared.adapter.in.rest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Formats money for the API: a decimal string with exactly two fraction digits, e.g. {@code "1000.93"} (contract
 * {@code Amount}, ADR-0004). Technical only, so it lives in {@code shared} and serves every REST adapter.
 */
public final class MoneyFormatter {

    private MoneyFormatter() {
    }

    /**
     * Formats a legacy {@code Double} balance. {@code BigDecimal.valueOf} takes the double's shortest decimal
     * representation ({@code 1.005}), unlike {@code new BigDecimal(double)}, which takes its binary value
     * ({@code 1.00499..}); ADR-0004 rounds that decimal HALF_UP, the rounding mode of the legacy calculator.
     *
     * @throws IllegalArgumentException for a negative, NaN or infinite amount
     */
    public static String format(Double amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.isNaN() || amount.isInfinite()) {
            throw new IllegalArgumentException("Amount must be a finite number: " + amount);
        }
        return format(BigDecimal.valueOf(amount));
    }

    /**
     * Formats a decimal amount, e.g. a withdrawal read from {@code NUMERIC(15,2)}. Assumption: a scale above two
     * cannot come from the database; should it occur, it is rounded HALF_UP like the {@code Double} path rather than
     * rejected.
     *
     * @throws IllegalArgumentException for a negative amount
     */
    public static String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        // Assumption: the contract allows only non-negative amounts and the database enforces it (ADR-0006), so a
        // negative value is a programming error, reported as such (500) rather than sent in breach of the contract.
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Amount must not be negative: " + amount);
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
