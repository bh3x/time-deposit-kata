package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.InProgress;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The short transactions around a month's balance update run (ADR-0013, with ADR-0012's mechanisms): reserve and
 * release the month, each committed on its own, so a duplicate request sees the reservation and gets {@code 409}.
 * A plain {@code @Service}, not a {@code @UseCase}: it implements no driving port. Not final, because Spring proxies
 * it for {@code @Transactional}; the methods are public for the same reason.
 */
@Service
public class BalanceUpdatePeriodReservations {

    /** An unlocked reservation at least this old is stale and taken over (assumption, ADR-0012, C). */
    static final Duration IN_FLIGHT_TIMEOUT = Duration.ofMinutes(10);

    private final RecordBalanceUpdatePort periods;
    private final Clock clock;

    public BalanceUpdatePeriodReservations(RecordBalanceUpdatePort periods, Clock clock) {
        this.periods = Objects.requireNonNull(periods, "periods");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What a reservation attempt found. */
    public sealed interface Reservation {

        /** The month is reserved for this request with {@code token}. */
        record Reserved(YearMonth period, Instant token) implements Reservation {
        }

        /** The month completed earlier; its outcome is returned. */
        record Replay(BalanceUpdateOutcome outcome) implements Reservation {
        }

        /** Another request holds the month. */
        record Busy() implements Reservation {
        }
    }

    // The month is the UTC calendar month of this moment (ADR-0013, A1: the brief names no zone), whatever zone the
    // clock carries; the run keeps this month and never re-reads it from the clock.
    @Transactional
    public Reservation reserve() {
        Instant token = clock.instant().truncatedTo(ChronoUnit.MICROS);
        YearMonth period = YearMonth.from(token.atOffset(ZoneOffset.UTC));
        if (periods.reserve(period, token)) {
            return new Reservation.Reserved(period, token);
        }
        // Empty: a live run holds the row locked, or it was deleted meanwhile; a retry decides again.
        return periods.findUnlocked(period).<Reservation>map(state -> switch (state) {
            case Completed completed -> new Reservation.Replay(completed.outcome());
            case InProgress inProgress when isStale(inProgress, token) -> {
                periods.takeOver(period, token);
                yield new Reservation.Reserved(period, token);
            }
            case InProgress inProgress -> new Reservation.Busy();
        }).orElseGet(Reservation.Busy::new);
    }

    @Transactional
    public void release(YearMonth period, Instant token) {
        periods.release(period, token);
    }

    // Stale at exactly the timeout: createdAt <= now - 10 minutes (ADR-0012, C). The new token is then at least ten
    // minutes later than the old one, so tokens of one month never collide.
    private static boolean isStale(InProgress inProgress, Instant now) {
        return !inProgress.token().isAfter(now.minus(IN_FLIGHT_TIMEOUT));
    }
}
