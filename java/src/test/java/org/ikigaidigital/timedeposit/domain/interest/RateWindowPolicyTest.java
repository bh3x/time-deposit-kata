package org.ikigaidigital.timedeposit.domain.interest;

import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aDeposit;

/**
 * One month of interest from a rate and a day window (ADR-0011, B2). A balance of 1200.00 gives exact doubles
 * (1200 × 0.01 / 12 = 1.0, × 0.03 → 3.0, × 0.05 → 5.0), so expected values are compared exactly.
 */
class RateWindowPolicyTest {

    private static final RateWindowPolicy BASIC =
            new RateWindowPolicy(0.01, 30, OptionalInt.empty(), OptionalInt.empty());
    private static final RateWindowPolicy STUDENT =
            new RateWindowPolicy(0.03, 30, OptionalInt.empty(), OptionalInt.of(366));
    private static final RateWindowPolicy PREMIUM =
            new RateWindowPolicy(0.05, 30, OptionalInt.of(45), OptionalInt.empty());
    private static final Map<String, RateWindowPolicy> POLICIES =
            Map.of("basic", BASIC, "student", STUDENT, "premium", PREMIUM);

    // The plan type is not read by a policy: the registry has chosen it already.
    private static TimeDepositTerms terms(double balance, int days) {
        return new ReadOnlyTimeDeposit(aDeposit("any", balance, days));
    }

    @ParameterizedTest(name = "{0} at {1} days earns nothing")
    @CsvSource({
            "basic, -2147483648", "basic, -1", "basic, 0", "basic, 30",
            "student, 0", "student, 30", "premium, 0", "premium, 30"})
    void paysNothingWithinTheGracePeriod(String plan, int days) {
        assertThat(POLICIES.get(plan).monthlyInterest(terms(1200.00, days))).isEqualTo(0.0);
    }

    @Test
    void paysFromTheDayAfterTheGracePeriod() {
        assertThat(BASIC.monthlyInterest(terms(1200.00, 31))).isEqualTo(1.0);
    }

    // Open bounds are absent values, not Integer.MAX_VALUE sentinels: the legacy code still pays at the largest days.
    @ParameterizedTest(name = "{0} at Integer.MAX_VALUE days -> {1}")
    @CsvSource({"basic, 1.0", "premium, 5.0"})
    void anOpenUpperBoundStillPaysAtTheLargestDays(String plan, double expected) {
        assertThat(POLICIES.get(plan).monthlyInterest(terms(1200.00, Integer.MAX_VALUE))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "student at {0} days -> {1}")
    @CsvSource({"31, 3.0", "365, 3.0", "366, 0.0", "2147483647, 0.0"})
    void studentPaysOnlyBeforeDay366(int days, double expected) {
        assertThat(STUDENT.monthlyInterest(terms(1200.00, days))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "premium at {0} days -> {1}")
    @CsvSource({"31, 0.0", "45, 0.0", "46, 5.0"})
    void premiumPaysOnlyAfterDay45(int days, double expected) {
        assertThat(PREMIUM.monthlyInterest(terms(1200.00, days))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "grace period 60, {0} days -> {1}")
    @CsvSource({"60, 0.0", "61, 1.0"})
    void theGracePeriodBelongsToThePolicy(int days, double expected) {
        RateWindowPolicy policy = new RateWindowPolicy(0.01, 60, OptionalInt.empty(), OptionalInt.empty());

        assertThat(policy.monthlyInterest(terms(1200.00, days))).isEqualTo(expected);
    }

    // Grace period and minimum are both lower bounds; the later one decides.
    @ParameterizedTest(name = "grace period 50, minimum 45, {0} days -> {1}")
    @CsvSource({"50, 0.0", "51, 5.0"})
    void aGracePeriodLongerThanTheMinimumWins(int days, double expected) {
        RateWindowPolicy policy = new RateWindowPolicy(0.05, 50, OptionalInt.of(45), OptionalInt.empty());

        assertThat(policy.monthlyInterest(terms(1200.00, days))).isEqualTo(expected);
    }

    // Known issue #1: balance × rate / 12 and balance × (rate / 12) differ in double; only the first is legacy.
    @Test
    void multipliesTheBalanceByTheRateBeforeDividingByTwelve() {
        double interest = PREMIUM.monthlyInterest(terms(3.60, 46));

        assertThat(interest).isEqualTo(3.60 * 0.05 / 12).isNotEqualTo(3.60 * (0.05 / 12));
    }

    // The calculator rounds (ADR-0011, D1): 18 × 0.01 / 12 is exactly just below 0.015 as a double and must reach the
    // calculator unchanged, neither rounded to cents nor converted to a decimal. The double literal 0.015 is itself
    // below 0.015, so the exact value is compared as a BigDecimal.
    @Test
    void returnsTheInterestUnrounded() {
        double interest = BASIC.monthlyInterest(terms(18.00, 31));

        assertThat(interest).isEqualTo(18.00 * 0.01 / 12);
        assertThat(new BigDecimal(interest)).isLessThan(new BigDecimal("0.015")).isGreaterThan(new BigDecimal("0.0149"));
    }

    @ParameterizedTest(name = "basic {0} -> {1}")
    @CsvSource({"0.0, 0.0", "-1200.00, -1.0"})
    void zeroAndNegativeBalances(double balance, double expected) {
        assertThat(BASIC.monthlyInterest(terms(balance, 31))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "rate {0} rejected")
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsANonFiniteRate(double annualRate) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RateWindowPolicy(annualRate, 30, OptionalInt.empty(), OptionalInt.empty()));
    }

    @Test
    void rejectsANegativeGracePeriod() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RateWindowPolicy(0.01, -1, OptionalInt.empty(), OptionalInt.empty()));
    }

    @Test
    void rejectsMissingBounds() {
        assertThatNullPointerException().isThrownBy(
                () -> new RateWindowPolicy(0.01, 30, null, OptionalInt.empty()));
        assertThatNullPointerException().isThrownBy(
                () -> new RateWindowPolicy(0.01, 30, OptionalInt.empty(), null));
    }
}
