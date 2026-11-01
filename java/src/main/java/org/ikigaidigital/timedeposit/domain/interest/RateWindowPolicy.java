package org.ikigaidigital.timedeposit.domain.interest;

import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Pays an annual rate monthly while the deposit's days lie in a window (ADR-0011, B2): after the grace period, after
 * {@code minDaysExclusive} and before {@code maxDaysExclusive}. A plan that differs only in these numbers is one
 * registration, not a new class.
 *
 * <p>Open bounds are {@link OptionalInt#empty()}, never a sentinel such as {@code Integer.MAX_VALUE}: with a sentinel
 * a deposit at {@code Integer.MAX_VALUE} days would stop earning, which the legacy code does not do.
 *
 * <p>Validation is an assumption, as ADR-0011 names none: a non-finite rate or a negative grace period is rejected,
 * because neither is a meaningful plan. Negative rates and empty windows are allowed, since a future plan might need
 * them and they do not break the arithmetic.
 */
public record RateWindowPolicy(double annualRate, int gracePeriodDays, OptionalInt minDaysExclusive,
                               OptionalInt maxDaysExclusive) implements InterestPolicy {

    private static final int MONTHS_PER_YEAR = 12;

    public RateWindowPolicy {
        if (!Double.isFinite(annualRate)) {
            throw new IllegalArgumentException("annualRate must be finite: " + annualRate);
        }
        if (gracePeriodDays < 0) {
            throw new IllegalArgumentException("gracePeriodDays must not be negative: " + gracePeriodDays);
        }
        Objects.requireNonNull(minDaysExclusive, "minDaysExclusive");
        Objects.requireNonNull(maxDaysExclusive, "maxDaysExclusive");
    }

    @Override
    public double monthlyInterest(TimeDepositTerms terms) {
        int days = terms.getDays();
        if (days <= gracePeriodDays
                || minDaysExclusive.isPresent() && days <= minDaysExclusive.getAsInt()
                || maxDaysExclusive.isPresent() && days >= maxDaysExclusive.getAsInt()) {
            return 0.0;
        }
        // balance × rate / 12, in this order: a precomputed monthly rate (rate / 12) gives other doubles and changes
        // rounded balances (known issue #1), which the brief forbids for existing plans.
        return terms.getBalance() * annualRate / MONTHS_PER_YEAR;
    }
}
