package org.ikigaidigital;

import org.ikigaidigital.timedeposit.domain.interest.InterestPolicies;
import org.ikigaidigital.timedeposit.domain.interest.RateWindowPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The calculator with configurable interest policies (ADR-0011). Today's plans through the no-argument constructor
 * are pinned by {@link TimeDepositCalculatorCharacterizationTest} and not repeated here.
 */
class TimeDepositCalculatorTest {

    // The brief defines no new plan: "gold" exists only in this test, to show that a plan is added by registration
    // alone, without changing the calculator or any existing policy.
    @Test
    void appliesAPlanRegisteredNextToTheDefaults() {
        InterestPolicies policies = InterestPolicies.defaults().toBuilder()
                .register("gold", new RateWindowPolicy(0.02, InterestPolicies.DEFAULT_GRACE_PERIOD_DAYS,
                        OptionalInt.empty(), OptionalInt.empty()))
                .build();
        TimeDeposit basic = new TimeDeposit(1, "basic", 1200.00, 31);
        TimeDeposit gold = new TimeDeposit(2, "gold", 1200.00, 31);

        new TimeDepositCalculator(policies).updateBalance(List.of(basic, gold));

        assertThat(basic.getBalance()).isEqualTo(1201.00);
        assertThat(gold.getBalance()).isEqualTo(1202.00);
    }

    // The calculator is the single rounding point (ADR-0011, D1): 0.125 is exact in binary, so HALF_UP decides it.
    @ParameterizedTest(name = "interest {0} -> balance {1}")
    @CsvSource({"0.125, 0.13", "-0.125, -0.13", "0.004, 0.0"})
    void roundsTheInterestOfAnyPolicyToCents(double interest, double expected) {
        InterestPolicies policies = InterestPolicies.builder().register("fixed", terms -> interest).build();
        TimeDeposit deposit = new TimeDeposit(1, "fixed", 0.0, 0);

        new TimeDepositCalculator(policies).updateBalance(List.of(deposit));

        assertThat(deposit.getBalance()).isEqualTo(expected);
    }

    @Test
    void rejectsMissingPolicies() {
        assertThatNullPointerException().isThrownBy(() -> new TimeDepositCalculator(null));
    }
}
