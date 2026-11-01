package org.ikigaidigital.timedeposit.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The stored state of a balance update period (a UTC calendar month, ADR-0013): exactly the two states the schema
 * allows. A sealed type, so every reader handles both.
 */
public sealed interface BalanceUpdatePeriodState {

    /**
     * A reserved month whose run has not completed. {@code token} is the reservation's {@code createdAt}, the fencing
     * token of ADR-0012: a takeover replaces it, so a stalled original can recognise that it lost the month.
     */
    record InProgress(Instant token) implements BalanceUpdatePeriodState {

        public InProgress {
            Objects.requireNonNull(token, "token");
        }
    }

    /** A completed month whose outcome every later request of that month gets. */
    record Completed(BalanceUpdateOutcome outcome) implements BalanceUpdatePeriodState {

        public Completed {
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
