package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.shared.application.UseCase;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.MonthInProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.Succeeded;
import org.ikigaidigital.timedeposit.application.port.in.ApplyMonthlyInterestUseCase;
import org.ikigaidigital.timedeposit.application.service.BalanceUpdatePeriodReservations.Reservation;

import java.util.Objects;

/**
 * Orchestrates a balance update (ADR-0012, B; ADR-0013). Deliberately <b>without</b> {@code @Transactional}: the
 * month's reservation, run and release are each committed on their own, so they live in
 * {@link BalanceUpdatePeriodReservations} and {@link MonthlyInterestRun} and are called through their Spring proxies.
 * This refines ADR-0008 decision 7 as ADR-0012 records; the ArchUnit rules are unchanged.
 */
@UseCase
public final class ApplyMonthlyInterestService implements ApplyMonthlyInterestUseCase {

    private final BalanceUpdatePeriodReservations reservations;
    private final MonthlyInterestRun run;

    public ApplyMonthlyInterestService(BalanceUpdatePeriodReservations reservations, MonthlyInterestRun run) {
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.run = Objects.requireNonNull(run, "run");
    }

    @Override
    public BalanceUpdateResult applyMonthlyInterest() {
        return switch (reservations.reserve()) {
            // ADR-0013, B1 (assumption): a later request of an applied month gets 200 with that month's stored
            // outcome rather than a 409, so every request of a month gets the same answer and retries are harmless.
            case Reservation.Replay replay -> new Succeeded(replay.outcome());
            case Reservation.Busy busy -> new MonthInProgress();
            case Reservation.Reserved reserved -> runReserved(reserved);
        };
    }

    // Releases on any exception, also one before the fence decided: the release matches only this request's own
    // in-progress month, so it is always safe (ADR-0012, flow step 4). A fence outcome is a result and releases
    // nothing. The original error wins; a failing release is attached to it.
    private BalanceUpdateResult runReserved(Reservation.Reserved reserved) {
        try {
            return run.apply(reserved.period(), reserved.token());
        } catch (Throwable failure) {
            try {
                reservations.release(reserved.period(), reserved.token());
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }
}
