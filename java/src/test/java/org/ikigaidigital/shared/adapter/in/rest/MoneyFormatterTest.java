package org.ikigaidigital.shared.adapter.in.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Money on the wire is a decimal string with exactly two fraction digits, rounded HALF_UP from the decimal value of
 * the legacy {@code double} ({@code BigDecimal.valueOf}, ADR-0004), and never negative (contract {@code Amount}).
 */
class MoneyFormatterTest {

    @Nested
    @DisplayName("formats a legacy Double balance")
    class FromDouble {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                // Floating-point noise from 1000.10 + 0.83 is far below half a cent.
                "1000.9300000000001, 1000.93",
                "0.0, 0.00",
                // BigDecimal has no negative zero, so -0.0 is zero, not a negative amount.
                "-0.0, 0.00",
                // HALF_UP on both sides of the half cent.
                "0.004, 0.00",
                "0.005, 0.01",
                // The binary values are 1.00499.. and 2.67499..: new BigDecimal(double) would round down.
                "1.005, 1.01",
                "2.675, 2.68",
                "1.5, 1.50",
                "10000.0, 10000.00",
                // Double.toString switches to E notation at 10^7 and below 10^-3; the result must stay plain.
                "1.0E7, 10000000.00",
                "1.0E-4, 0.00",
                // Column maximum of NUMERIC(15,2) (ADR-0006).
                "9999999999999.99, 9999999999999.99"
        })
        void formatsWithTwoDecimalsHalfUp(double amount, String expected) {
            assertThat(MoneyFormatter.format(Double.valueOf(amount))).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} is rejected")
        @ValueSource(doubles = {-0.01, -0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void rejectsAmountsTheContractCannotRepresent(double amount) {
            assertThatIllegalArgumentException().isThrownBy(() -> MoneyFormatter.format(Double.valueOf(amount)));
        }

        @Test
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> MoneyFormatter.format((Double) null));
        }
    }

    @Nested
    @DisplayName("formats a BigDecimal amount")
    class FromBigDecimal {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "500.00, 500.00",
                "500, 500.00",
                "500.5, 500.50",
                // Negative scale: still a plain string.
                "1E+3, 1000.00",
                // Scale above two cannot come from NUMERIC(15,2); rounded HALF_UP like the Double path.
                "0.005, 0.01",
                "0.0049, 0.00",
                "0, 0.00"
        })
        void formatsWithTwoDecimalsHalfUp(String amount, String expected) {
            assertThat(MoneyFormatter.format(new BigDecimal(amount))).isEqualTo(expected);
        }

        @Test
        void rejectsNegativeAmounts() {
            assertThatIllegalArgumentException().isThrownBy(() -> MoneyFormatter.format(new BigDecimal("-0.01")));
        }

        @Test
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> MoneyFormatter.format((BigDecimal) null));
        }
    }
}
