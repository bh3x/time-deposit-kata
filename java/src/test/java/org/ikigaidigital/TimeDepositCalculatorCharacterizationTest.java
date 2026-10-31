package org.ikigaidigital;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests: pin the legacy behaviour of {@link TimeDepositCalculator#updateBalance(List)} before any
 * refactoring (CLAUDE.md hard constraints). Expected values are derived from the rules by hand and compared exactly,
 * because "behaviour identical" includes the {@code double} arithmetic. A failing test here means the code changed,
 * not that the test is wrong.
 */
class TimeDepositCalculatorCharacterizationTest {

    private final TimeDepositCalculator calculator = new TimeDepositCalculator();

    private double balanceAfterUpdate(String planType, double balance, int days) {
        TimeDeposit deposit = new TimeDeposit(1, planType, balance, days);
        calculator.updateBalance(List.of(deposit));
        return deposit.getBalance();
    }

    @Nested
    @DisplayName("no interest during the first 30 days, for every plan")
    class ThirtyDayGate {

        @ParameterizedTest(name = "{0} at {1} days keeps 1000.00")
        @CsvSource({"basic, 30", "student, 30", "premium, 30", "basic, 0", "basic, -1", "premium, -5"})
        void noInterestUpToThirtyDays(String planType, int days) {
            assertThat(balanceAfterUpdate(planType, 1000.00, days)).isEqualTo(1000.00);
        }

        @ParameterizedTest(name = "{0} at 31 days -> {1}")
        @CsvSource({"basic, 1000.83", "student, 1002.5"})
        void interestFromDay31(String planType, double expected) {
            assertThat(balanceAfterUpdate(planType, 1000.00, 31)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("basic: 1% per year, applied monthly")
    class Basic {

        @ParameterizedTest(name = "basic at {0} days -> 1000.83")
        @ValueSource(ints = {31, 365, 366, 10_000})
        void earnsAfterThirtyDaysWithoutUpperLimit(int days) {
            // 1000.00 * 0.01 / 12 = 0.8333.. -> 0.83
            assertThat(balanceAfterUpdate("basic", 1000.00, days)).isEqualTo(1000.83);
        }
    }

    @Nested
    @DisplayName("student: 3% per year, only while days < 366")
    class Student {

        @ParameterizedTest(name = "student at {0} days -> {1}")
        @CsvSource({"31, 1002.5", "365, 1002.5", "366, 1000.0", "1000, 1000.0"})
        void earnsUntilDay365(int days, double expected) {
            // 1000.00 * 0.03 / 12 = 2.50
            assertThat(balanceAfterUpdate("student", 1000.00, days)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("premium: 5% per year, only when days > 45")
    class Premium {

        @ParameterizedTest(name = "premium at {0} days -> {1}")
        @CsvSource({"31, 1000.0", "45, 1000.0", "46, 1004.17", "366, 1004.17"})
        void earnsFromDay46(int days, double expected) {
            // 1000.00 * 0.05 / 12 = 4.1666.. -> 4.17
            assertThat(balanceAfterUpdate("premium", 1000.00, days)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("unknown plan types earn nothing; matching is exact and case-sensitive")
    class UnknownPlans {

        @ParameterizedTest(name = "\"{0}\" at 100 days keeps 1000.00")
        @ValueSource(strings = {"gold", "", "Basic", "STUDENT", "Premium", " basic", "basic "})
        void noInterest(String planType) {
            assertThat(balanceAfterUpdate(planType, 1000.00, 100)).isEqualTo(1000.00);
        }
    }

    @Nested
    @DisplayName("only the interest is rounded (HALF_UP of the exact binary double), the sum is not")
    class Rounding {

        @Test
        void halfCentJustAboveRoundsUp() {
            // 6.00 * 0.01 / 12 is just above 0.005 as a double -> 0.01
            assertThat(balanceAfterUpdate("basic", 6.00, 31)).isEqualTo(6.01);
        }

        @Test
        void halfCentJustBelowRoundsDown() {
            // 18.00 * 0.01 / 12 is just below 0.015 as a double -> 0.01 (decimal HALF_UP would give 0.02)
            assertThat(balanceAfterUpdate("basic", 18.00, 31)).isEqualTo(18.01);
        }

        @Test
        void sumKeepsDoubleNoise() {
            // 1000.10 + 0.83 is not rounded: the double sum is 1000.9300000000001
            assertThat(balanceAfterUpdate("basic", 1000.10, 31)).isEqualTo(1000.9300000000001);
        }
    }

    @Nested
    @DisplayName("balances")
    class Balances {

        @Test
        void zeroStaysZero() {
            assertThat(balanceAfterUpdate("premium", 0.0, 100)).isEqualTo(0.0);
        }

        @Test
        void largeBalance() {
            // 9,999,999,999,999.99 * 0.01 / 12 = 8,333,333,333.33 (rounded) -> 10,008,333,333,333.32
            assertThat(balanceAfterUpdate("basic", 9_999_999_999_999.99, 31)).isEqualTo(1.000833333333332E13);
        }
    }

    @Nested
    @DisplayName("lists: every deposit is updated independently and in place")
    class Lists {

        @Test
        void updatesEveryDepositInOneCall() {
            TimeDeposit basic = new TimeDeposit(1, "basic", 1000.00, 31);
            TimeDeposit student = new TimeDeposit(2, "student", 1000.00, 366);
            TimeDeposit premium = new TimeDeposit(3, "premium", 1000.00, 46);
            TimeDeposit unknown = new TimeDeposit(4, "gold", 1000.00, 100);

            calculator.updateBalance(List.of(basic, student, premium, unknown));

            assertThat(basic.getBalance()).isEqualTo(1000.83);
            assertThat(student.getBalance()).isEqualTo(1000.00);
            assertThat(premium.getBalance()).isEqualTo(1004.17);
            assertThat(unknown.getBalance()).isEqualTo(1000.00);
        }

        @Test
        void emptyListIsAccepted() {
            List<TimeDeposit> deposits = new ArrayList<>();

            calculator.updateBalance(deposits);

            assertThat(deposits).isEmpty();
        }

        @Test
        void mutatesOnlyTheBalanceOfTheSameInstances() {
            TimeDeposit first = new TimeDeposit(7, "premium", 1000.00, 46);
            TimeDeposit second = new TimeDeposit(8, "basic", 1000.00, 31);
            List<TimeDeposit> deposits = new ArrayList<>(List.of(first, second));

            calculator.updateBalance(deposits);

            assertThat(deposits).containsExactly(first, second);
            assertThat(first.getId()).isEqualTo(7);
            assertThat(first.getPlanType()).isEqualTo("premium");
            assertThat(first.getDays()).isEqualTo(46);
        }

        @Test
        void eachCallAppliesInterestAgain() {
            TimeDeposit deposit = new TimeDeposit(1, "basic", 1000.00, 31);

            calculator.updateBalance(List.of(deposit));
            calculator.updateBalance(List.of(deposit));

            // 1000.83 * 0.01 / 12 = 0.834.. -> 0.83; the double sum carries noise into the second call (ADR-0006)
            assertThat(deposit.getBalance()).isEqualTo(1001.6600000000001);
        }
    }
}
