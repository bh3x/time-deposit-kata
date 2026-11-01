package org.ikigaidigital.timedeposit.domain.interest;

import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;

/**
 * The interest rule of one plan (ADR-0011, Strategy). A new kind of rule is a new implementation plus its registration
 * in {@link InterestPolicies}; the calculator and existing policies stay untouched.
 */
@FunctionalInterface
public interface InterestPolicy {

    /**
     * One month of interest for the deposit, unrounded: the calculator rounds it once (ADR-0011, D1). Returns
     * {@code 0.0} when the policy pays none, including within its own grace period, which every implementation must
     * apply itself (ADR-0011, C2).
     */
    double monthlyInterest(TimeDepositTerms terms);
}
