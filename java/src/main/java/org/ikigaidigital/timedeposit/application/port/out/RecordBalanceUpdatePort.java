package org.ikigaidigital.timedeposit.application.port.out;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Driven port: the balance update periods, one per UTC calendar month (ADR-0013), with ADR-0012's reservation, fence
 * and release keyed by the month. Each method runs in the caller's transaction; which methods share one is the
 * application's decision (ADR-0012, B). Times come from the application clock, truncated to microseconds. Rows are
 * never purged.
 */
public interface RecordBalanceUpdatePort {

    /** Inserts the month in progress with {@code token}; {@code false} if the month exists already. */
    boolean reserve(YearMonth period, Instant token);

    /**
     * Returns the month's state without locking it and without waiting for another transaction's lock; empty if the
     * month does not exist (ADR-0014).
     */
    Optional<BalanceUpdatePeriodState> find(YearMonth period);

    /**
     * Locks and returns the month's state without waiting: empty if the month does not exist or another transaction
     * holds it locked (a live run, ADR-0012, C).
     */
    Optional<BalanceUpdatePeriodState> findUnlocked(YearMonth period);

    /** Gives the in-progress month, which the caller's transaction holds locked, a new token. */
    void takeOver(YearMonth period, Instant token);

    /**
     * Locks the month, waiting for another transaction if needed, and returns its state; empty if it does not exist.
     */
    Optional<BalanceUpdatePeriodState> lock(YearMonth period);

    /**
     * Switches the month from in progress with {@code token} to completed with the outcome; {@code false} if no such
     * row exists (missing, completed or with a foreign token).
     */
    boolean complete(YearMonth period, Instant token, BalanceUpdateOutcome outcome, Instant completedAt);

    /** Deletes the month if it is still in progress with {@code token}; otherwise leaves it as it is. */
    void release(YearMonth period, Instant token);
}
