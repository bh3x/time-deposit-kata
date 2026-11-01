package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The short transactions around a keyed balance update run (ADR-0012, B and C): purge, reserve and release, each
 * committed on its own, so a duplicate request sees the reservation and gets {@code 409}. A plain {@code @Service},
 * not a {@code @UseCase}: it implements no driving port. Not final, because Spring proxies it for
 * {@code @Transactional}; the methods are public for the same reason.
 */
@Service
public class IdempotencyKeyReservations {

    /** An unlocked reservation at least this old is stale and taken over (assumption, ADR-0012, C). */
    static final Duration IN_FLIGHT_TIMEOUT = Duration.ofMinutes(10);

    /** Completed keys, and abandoned reservations, are kept this long (ADR-0004, ADR-0012, D). */
    static final Duration RETENTION = Duration.ofHours(24);

    private final RecordBalanceUpdatePort records;
    private final Clock clock;

    public IdempotencyKeyReservations(RecordBalanceUpdatePort records, Clock clock) {
        this.records = Objects.requireNonNull(records, "records");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What a reservation attempt found. */
    public sealed interface Reservation {

        /** The key is reserved for this request with {@code token}. */
        record Reserved(Instant token) implements Reservation {
        }

        /** The key completed earlier; its outcome is replayed. */
        record Replay(BalanceUpdateOutcome outcome) implements Reservation {
        }

        /** Another request holds the key. */
        record Busy() implements Reservation {
        }
    }

    // Its own transaction, committed before the reservation: uncommitted deletes in the reservation's transaction
    // could deadlock two reservations (ADR-0012, D).
    @Transactional
    public void purge() {
        records.purge(now().minus(RETENTION));
    }

    @Transactional
    public Reservation reserve(String key) {
        Instant token = now();
        if (records.reserve(key, token)) {
            return new Reservation.Reserved(token);
        }
        // Empty: a live run holds the row locked, or it was deleted meanwhile; a retry decides again.
        return records.findUnlocked(key).<Reservation>map(state -> switch (state) {
            case Completed completed -> new Reservation.Replay(completed.outcome());
            case InProgress inProgress when isStale(inProgress, token) -> {
                records.takeOver(key, token);
                yield new Reservation.Reserved(token);
            }
            case InProgress inProgress -> new Reservation.Busy();
        }).orElseGet(Reservation.Busy::new);
    }

    @Transactional
    public void release(String key, Instant token) {
        records.release(key, token);
    }

    // Stale at exactly the timeout: createdAt <= now - 10 minutes (ADR-0012, C). The new token is then at least ten
    // minutes later than the old one, so tokens of one key never collide.
    private static boolean isStale(InProgress inProgress, Instant now) {
        return !inProgress.token().isAfter(now.minus(IN_FLIGHT_TIMEOUT));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
