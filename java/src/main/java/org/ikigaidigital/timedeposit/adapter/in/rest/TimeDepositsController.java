package org.ikigaidigital.timedeposit.adapter.in.rest;

import org.ikigaidigital.shared.adapter.in.rest.MoneyFormatter;
import org.ikigaidigital.shared.adapter.in.rest.api.TimeDepositsApi;
import org.ikigaidigital.shared.adapter.in.rest.model.BalanceUpdateDto;
import org.ikigaidigital.shared.adapter.in.rest.model.TimeDepositDto;
import org.ikigaidigital.shared.adapter.in.rest.model.WithdrawalDto;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.KeyInProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.Succeeded;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.ikigaidigital.timedeposit.application.port.in.ApplyMonthlyInterestUseCase;
import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Driving adapter for the {@code TimeDeposits} tag of the contract (ADR-0008 rule 6): implements the generated
 * interface, which carries the mappings, and translates between use cases and the generated representation. The
 * mapping stays here, next to the only operation that needs it; the web slice test covers it.
 */
@RestController
class TimeDepositsController implements TimeDepositsApi {

    private final ListTimeDepositsUseCase listTimeDeposits;
    private final ApplyMonthlyInterestUseCase applyMonthlyInterest;

    TimeDepositsController(ListTimeDepositsUseCase listTimeDeposits, ApplyMonthlyInterestUseCase applyMonthlyInterest) {
        this.listTimeDeposits = listTimeDeposits;
        this.applyMonthlyInterest = applyMonthlyInterest;
    }

    // Keeps the use case's order: deterministic (persistence orders by internal key), though the contract promises
    // no order (ADR-0004).
    @Override
    public ResponseEntity<List<TimeDepositDto>> listTimeDeposits() {
        return ResponseEntity.ok(listTimeDeposits.listAll().stream()
                .map(TimeDepositsController::toTimeDeposit)
                .toList());
    }

    // A missing header means "apply again" (ADR-0004); the key's length (1-255) is checked by the generated interface
    // before this method runs. An in-progress key is the contract's 409: ErrorResponseException is rendered as
    // Problem Details by Spring Boot's handler, like Spring MVC's own errors (ADR-0003), so no extra advice is needed.
    @Override
    public ResponseEntity<BalanceUpdateDto> createBalanceUpdate(@Nullable String idempotencyKey) {
        BalanceUpdateResult result = idempotencyKey == null
                ? applyMonthlyInterest.applyMonthlyInterest()
                : applyMonthlyInterest.applyMonthlyInterest(idempotencyKey);
        return switch (result) {
            case Succeeded succeeded -> ResponseEntity.ok(toBalanceUpdate(succeeded.outcome()));
            case KeyInProgress keyInProgress -> throw new ErrorResponseException(HttpStatus.CONFLICT,
                    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                            "A request with this Idempotency-Key is still being processed."), null);
        };
    }

    // executedAt in UTC, rendered with Z as the contract requires.
    private static BalanceUpdateDto toBalanceUpdate(BalanceUpdateOutcome outcome) {
        return new BalanceUpdateDto(outcome.processedCount(), outcome.executedAt().atOffset(ZoneOffset.UTC));
    }

    private static TimeDepositDto toTimeDeposit(TimeDepositView view) {
        TimeDepositTerms terms = view.terms();
        List<WithdrawalDto> withdrawals = view.withdrawals().stream()
                .map(TimeDepositsController::toWithdrawal)
                .toList();
        // planType is passed through unchanged: an open, case-sensitive value set (x-extensible-enum, ADR-0003), so an
        // unknown plan such as "gold" is listed as stored rather than rejected or normalised.
        return new TimeDepositDto(view.id(), terms.getPlanType(), MoneyFormatter.format(terms.getBalance()),
                terms.getDays(), withdrawals);
    }

    private static WithdrawalDto toWithdrawal(WithdrawalView view) {
        return new WithdrawalDto(view.id(), MoneyFormatter.format(view.amount()), view.date());
    }
}
