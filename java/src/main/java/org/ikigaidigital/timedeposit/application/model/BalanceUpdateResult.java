package org.ikigaidigital.timedeposit.application.model;

import java.util.Objects;

/**
 * What a balance update request ends in (ADR-0013). A sealed result rather than an exception for the conflict: a month
 * whose run is in progress is an expected answer of the use case, not a failure.
 */
public sealed interface BalanceUpdateResult {

    /** The month's outcome: applied by this request, or by an earlier request of the same month. */
    record Succeeded(BalanceUpdateOutcome outcome) implements BalanceUpdateResult {

        public Succeeded {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** The balance update of this month is still in progress; the client retries later ({@code 409}). */
    record MonthInProgress() implements BalanceUpdateResult {
    }
}
