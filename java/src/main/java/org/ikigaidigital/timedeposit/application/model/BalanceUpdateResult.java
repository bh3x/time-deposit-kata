package org.ikigaidigital.timedeposit.application.model;

import java.util.Objects;

/**
 * What a balance update request ends in (ADR-0004, ADR-0012). A sealed result rather than an exception for the
 * conflict: an in-progress key is an expected answer of the use case, not a failure (ADR-0012, flow step 4).
 */
public sealed interface BalanceUpdateResult {

    /** The run's outcome: applied now, or replayed for a repeated idempotency key. */
    record Succeeded(BalanceUpdateOutcome outcome) implements BalanceUpdateResult {

        public Succeeded {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** A request with the same idempotency key is still in progress; the client retries later ({@code 409}). */
    record KeyInProgress() implements BalanceUpdateResult {
    }
}
