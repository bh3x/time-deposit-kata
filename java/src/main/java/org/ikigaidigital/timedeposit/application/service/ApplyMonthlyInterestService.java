package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.shared.application.UseCase;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.KeyInProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.Succeeded;
import org.ikigaidigital.timedeposit.application.port.in.ApplyMonthlyInterestUseCase;
import org.ikigaidigital.timedeposit.application.service.IdempotencyKeyReservations.Reservation;

import java.util.Objects;

/**
 * Orchestrates a balance update (ADR-0012, B). Deliberately <b>without</b> {@code @Transactional}: a keyed request
 * needs several transactions — purge, reservation, run, release — each committed on its own, so they live in
 * {@link IdempotencyKeyReservations} and {@link MonthlyInterestRun} and are called through their Spring proxies. This
 * refines ADR-0008 decision 7 as ADR-0012 records; the ArchUnit rules are unchanged.
 */
@UseCase
public final class ApplyMonthlyInterestService implements ApplyMonthlyInterestUseCase {

    private final IdempotencyKeyReservations reservations;
    private final MonthlyInterestRun run;

    public ApplyMonthlyInterestService(IdempotencyKeyReservations reservations, MonthlyInterestRun run) {
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.run = Objects.requireNonNull(run, "run");
    }

    @Override
    public BalanceUpdateResult applyMonthlyInterest() {
        return new Succeeded(run.apply());
    }

    @Override
    public BalanceUpdateResult applyMonthlyInterest(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        // A separate call, so the purge commits before the reservation: purging inside the reservation's transaction
        // could let two reservations each delete the other's expired key and then deadlock (ADR-0012, D).
        reservations.purge();
        return switch (reservations.reserve(idempotencyKey)) {
            case Reservation.Replay replay -> new Succeeded(replay.outcome());
            case Reservation.Busy busy -> new KeyInProgress();
            case Reservation.Reserved reserved -> runReserved(idempotencyKey, reserved);
        };
    }

    // Releases on any exception, also one before the fence decided: the release matches only this request's own
    // in-progress row, so it is always safe (ADR-0012, flow step 4). A fence outcome is a result and releases nothing.
    // The original error wins; a failing release is attached to it.
    private BalanceUpdateResult runReserved(String key, Reservation.Reserved reserved) {
        try {
            return run.apply(key, reserved.token());
        } catch (Throwable failure) {
            try {
                reservations.release(key, reserved.token());
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }
}
