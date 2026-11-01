package org.ikigaidigital;

import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.ikigaidigital.timedeposit.domain.interest.InterestPolicies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

public class TimeDepositCalculator {
    // Plan types, rates, day windows and the grace period live in the interest policies (ADR-0011). The 30-day grace
    // period belongs to each policy, because the brief limits it to "existing plans": today's three use
    // InterestPolicies.DEFAULT_GRACE_PERIOD_DAYS, so the behaviour is unchanged.
    private final InterestPolicies interestPolicies;

    public TimeDepositCalculator() {
        this(InterestPolicies.defaults());
    }

    public TimeDepositCalculator(InterestPolicies interestPolicies) {
        this.interestPolicies = Objects.requireNonNull(interestPolicies, "interestPolicies");
    }

    // One deposit at a time, in list order, and always written, also when the interest is 0.0 (ADR-0011): computing
    // all interests first would change which deposits are updated when one fails (known issue #7), and skipping the
    // write would let a null balance pass (known issue #4) and keep -0.0. Wrapping a null element throws
    // NullPointerException, as the legacy code did (known issue #10).
    public void updateBalance(List<TimeDeposit> timeDeposits) {
        for (TimeDeposit deposit : timeDeposits) {
            double interest = interestPolicies.policyFor(deposit.getPlanType())
                    .monthlyInterest(new ReadOnlyTimeDeposit(deposit));
            deposit.setBalance(deposit.getBalance() + roundToCents(interest));
        }
    }

    // Only the interest is rounded, from its exact binary value (new BigDecimal(double)); the sum is not rounded.
    private static double roundToCents(double amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
