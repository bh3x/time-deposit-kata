package org.ikigaidigital.timedeposit.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The stored state of an idempotency key: exactly the two states the schema allows ({@code V1__schema.sql},
 * ADR-0006). A sealed type, so every reader handles both.
 */
public sealed interface BalanceUpdateKeyState {

    /**
     * A reserved key whose run has not completed. {@code token} is the reservation's {@code createdAt}, the fencing
     * token of ADR-0012: a takeover replaces it, so a stalled original can recognise that it lost the key.
     */
    record InProgress(Instant token) implements BalanceUpdateKeyState {

        public InProgress {
            Objects.requireNonNull(token, "token");
        }
    }

    /** A completed run whose outcome a repeated request replays. */
    record Completed(BalanceUpdateOutcome outcome) implements BalanceUpdateKeyState {

        public Completed {
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
