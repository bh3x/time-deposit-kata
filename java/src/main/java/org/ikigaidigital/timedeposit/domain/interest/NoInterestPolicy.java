package org.ikigaidigital.timedeposit.domain.interest;

import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;

/**
 * Pays nothing. {@link InterestPolicies} returns it for plan types without a registered policy, which earn nothing in
 * the legacy code (known issue #6). It reads no terms, so no balance or day count can make it fail.
 */
public final class NoInterestPolicy implements InterestPolicy {

    public static final NoInterestPolicy INSTANCE = new NoInterestPolicy();

    private NoInterestPolicy() {
    }

    @Override
    public double monthlyInterest(TimeDepositTerms terms) {
        return 0.0;
    }
}
