package org.ikigaidigital.timedeposit.application.port.out;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;

import java.time.Instant;
import java.util.Optional;

/**
 * Driven port: the idempotency keys of balance update runs (ADR-0004, ADR-0006, ADR-0012). Each method runs in the
 * caller's transaction; which methods share one is the application's decision (ADR-0012, B). Times come from the
 * application clock, truncated to microseconds.
 */
public interface RecordBalanceUpdatePort {

    /**
     * Deletes completed keys whose {@code completedAt}, and abandoned in-progress keys whose {@code createdAt}, is at
     * or before {@code cutoff}, skipping rows that another transaction holds locked; never waits for them.
     */
    void purge(Instant cutoff);

    /** Inserts the key in progress with {@code token}; {@code false} if the key exists already. */
    boolean reserve(String key, Instant token);

    /**
     * Locks and returns the key's state without waiting: empty if the key does not exist or another transaction holds
     * it locked (a live run, ADR-0012, C).
     */
    Optional<BalanceUpdateKeyState> findUnlocked(String key);

    /** Gives the in-progress key, which the caller's transaction holds locked, a new token. */
    void takeOver(String key, Instant token);

    /** Locks the key, waiting for another transaction if needed, and returns its state; empty if it does not exist. */
    Optional<BalanceUpdateKeyState> lock(String key);

    /**
     * Switches the key from in progress with {@code token} to completed with the outcome; {@code false} if no such row
     * exists (missing, completed or with a foreign token).
     */
    boolean complete(String key, Instant token, BalanceUpdateOutcome outcome, Instant completedAt);

    /** Deletes the key if it is still in progress with {@code token}; otherwise leaves it as it is. */
    void release(String key, Instant token);
}
