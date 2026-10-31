package org.ikigaidigital.timedeposit.application.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.uuid;

class WithdrawalViewTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final LocalDate DATE = LocalDate.parse("2026-09-15");

    @Nested
    @DisplayName("rejects missing parts")
    class MissingParts {

        @Test
        void rejectsNullComponents() {
            assertThatNullPointerException().isThrownBy(() -> new WithdrawalView(null, AMOUNT, DATE));
            assertThatNullPointerException().isThrownBy(() -> new WithdrawalView(uuid(1), null, DATE));
            assertThatNullPointerException().isThrownBy(() -> new WithdrawalView(uuid(1), AMOUNT, null));
        }
    }

    @Nested
    @DisplayName("keeps the amount as read")
    class Amount {

        // Formatting belongs to the REST adapter, so the scale is kept and amounts are compared by value.
        @Test
        void keepsTheAmountAsGiven() {
            WithdrawalView withdrawal = new WithdrawalView(uuid(1), new BigDecimal("500.0"), DATE);

            assertThat(withdrawal.amount()).isEqualByComparingTo("500.00");
            assertThat(withdrawal.amount().scale()).isEqualTo(1);
        }
    }
}
