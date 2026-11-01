package org.ikigaidigital.timedeposit.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of one balance update run (ADR-0004): how many deposits were processed, including those whose balance
 * did not change, and when the run executed. Stored for a keyed request and replayed unchanged (ADR-0006), so
 * {@code executedAt} is already truncated to microseconds, the precision of {@code timestamptz}.
 */
public record BalanceUpdateOutcome(int processedCount, Instant executedAt) {

    public BalanceUpdateOutcome {
        if (processedCount < 0) {
            throw new IllegalArgumentException("processedCount must not be negative: " + processedCount);
        }
        Objects.requireNonNull(executedAt, "executedAt");
    }
}
