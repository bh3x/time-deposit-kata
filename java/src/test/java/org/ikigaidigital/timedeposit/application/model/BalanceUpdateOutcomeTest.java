package org.ikigaidigital.timedeposit.application.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BalanceUpdateOutcomeTest {

    private static final Instant EXECUTED_AT = Instant.parse("2026-11-01T10:15:29.654321Z");

    @Nested
    @DisplayName("processedCount is never negative")
    class ProcessedCount {

        @ParameterizedTest(name = "processedCount {0}")
        @ValueSource(ints = {-1, Integer.MIN_VALUE})
        void rejectsANegativeProcessedCount(int processedCount) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BalanceUpdateOutcome(processedCount, EXECUTED_AT))
                    .withMessageContaining(String.valueOf(processedCount));
        }

        // The boundary of the guard above: a run over no deposits (scenario P5) must be representable.
        @Test
        void acceptsAnEmptyRun() {
            BalanceUpdateOutcome outcome = new BalanceUpdateOutcome(0, EXECUTED_AT);

            assertThat(outcome.processedCount()).isZero();
            assertThat(outcome.executedAt()).isEqualTo(EXECUTED_AT);
        }

        @ParameterizedTest(name = "processedCount {0}")
        @ValueSource(ints = {1, Integer.MAX_VALUE})
        void acceptsAPositiveProcessedCount(int processedCount) {
            assertThat(new BalanceUpdateOutcome(processedCount, EXECUTED_AT).processedCount())
                    .isEqualTo(processedCount);
        }
    }

    @Nested
    @DisplayName("rejects missing parts")
    class MissingParts {

        // Count 0, so only the executedAt guard can fire.
        @Test
        void rejectsAMissingExecutedAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new BalanceUpdateOutcome(0, null))
                    .withMessageContaining("executedAt");
        }
    }
}
