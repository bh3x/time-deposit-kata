package org.ikigaidigital.timedeposit.domain.interest;

import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aDeposit;

/**
 * The registry of interest policies (ADR-0011, E1). Today's plans are compared as records here; their effect on
 * balances is pinned by the calculator's characterization tests and not repeated.
 */
class InterestPoliciesTest {

    private static final InterestPolicy GOLD = new RateWindowPolicy(0.02, 30, OptionalInt.empty(), OptionalInt.empty());

    private static TimeDepositTerms terms(String planType, int days) {
        return new ReadOnlyTimeDeposit(aDeposit(planType, 1200.00, days));
    }

    @Test
    void defaultsRegisterTodaysPlans() {
        InterestPolicies defaults = InterestPolicies.defaults();

        assertThat(defaults.policyFor("basic"))
                .isEqualTo(new RateWindowPolicy(0.01, 30, OptionalInt.empty(), OptionalInt.empty()));
        assertThat(defaults.policyFor("student"))
                .isEqualTo(new RateWindowPolicy(0.03, 30, OptionalInt.empty(), OptionalInt.of(366)));
        assertThat(defaults.policyFor("premium"))
                .isEqualTo(new RateWindowPolicy(0.05, 30, OptionalInt.of(45), OptionalInt.empty()));
    }

    @Test
    void theDefaultGracePeriodIsThirtyDays() {
        assertThat(InterestPolicies.DEFAULT_GRACE_PERIOD_DAYS).isEqualTo(30);
    }

    // Matching is exact and case-sensitive (CLAUDE.md hard constraints).
    @ParameterizedTest(name = "\"{0}\" gets no interest")
    @ValueSource(strings = {"gold", "Basic", "STUDENT", " basic", "basic ", ""})
    void unknownPlanTypesGetNoInterest(String planType) {
        assertThat(InterestPolicies.defaults().policyFor(planType)).isSameAs(NoInterestPolicy.INSTANCE);
    }

    // Known issue #3, kept in the registry: a null plan type passes within the default grace period, in any registry.
    @ParameterizedTest(name = "null plan type at {0} days earns nothing")
    @ValueSource(ints = {0, 30})
    void aNullPlanTypePaysNothingWithinTheDefaultGracePeriod(int days) {
        for (InterestPolicies policies : List.of(InterestPolicies.defaults(), InterestPolicies.builder().build())) {
            assertThat(policies.policyFor(null).monthlyInterest(terms(null, days))).isEqualTo(0.0);
        }
    }

    @Test
    void aNullPlanTypeFailsAfterTheDefaultGracePeriod() {
        InterestPolicy policy = InterestPolicies.defaults().policyFor(null);

        assertThatNullPointerException().isThrownBy(() -> policy.monthlyInterest(terms(null, 31)));
    }

    @Test
    void registersAnExtraPlanNextToTheDefaults() {
        InterestPolicies policies = InterestPolicies.defaults().toBuilder().register("gold", GOLD).build();

        assertThat(policies.policyFor("gold")).isSameAs(GOLD);
        assertThat(policies.policyFor("basic")).isEqualTo(InterestPolicies.defaults().policyFor("basic"));
    }

    @Test
    void allowsPlanTypesThatDifferOnlyInCase() {
        InterestPolicy upperCase = new RateWindowPolicy(0.04, 30, OptionalInt.empty(), OptionalInt.empty());

        InterestPolicies policies = InterestPolicies.builder().register("gold", GOLD).register("GOLD", upperCase).build();

        assertThat(policies.policyFor("gold")).isSameAs(GOLD);
        assertThat(policies.policyFor("GOLD")).isSameAs(upperCase);
    }

    @Test
    void rejectsADuplicatePlanType() {
        InterestPolicies.Builder builder = InterestPolicies.defaults().toBuilder();

        assertThatIllegalArgumentException().isThrownBy(() -> builder.register("basic", GOLD));
    }

    @ParameterizedTest(name = "\"{0}\" rejected")
    @ValueSource(strings = {"", "  ", "\t"})
    void rejectsABlankPlanType(String planType) {
        InterestPolicies.Builder builder = InterestPolicies.builder();

        assertThatIllegalArgumentException().isThrownBy(() -> builder.register(planType, GOLD));
    }

    @Test
    void rejectsANullPlanTypeOrPolicy() {
        InterestPolicies.Builder builder = InterestPolicies.builder();

        assertThatNullPointerException().isThrownBy(() -> builder.register(null, GOLD));
        assertThatNullPointerException().isThrownBy(() -> builder.register("gold", null));
    }

    @Test
    void cannotBeChangedAfterItIsBuilt() {
        InterestPolicies.Builder builder = InterestPolicies.builder();
        InterestPolicies built = builder.build();
        InterestPolicies defaults = InterestPolicies.defaults();

        builder.register("gold", GOLD);
        defaults.toBuilder().register("gold", GOLD);

        assertThat(built.policyFor("gold")).isSameAs(NoInterestPolicy.INSTANCE);
        assertThat(defaults.policyFor("gold")).isSameAs(NoInterestPolicy.INSTANCE);
    }
}
