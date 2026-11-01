package org.ikigaidigital.timedeposit.adapter.in.rest;

import org.ikigaidigital.shared.adapter.in.rest.MoneyFormatter;
import org.ikigaidigital.shared.adapter.in.rest.api.TimeDepositsApi;
import org.ikigaidigital.shared.adapter.in.rest.model.BalanceUpdateDto;
import org.ikigaidigital.shared.adapter.in.rest.model.TimeDepositDto;
import org.ikigaidigital.shared.adapter.in.rest.model.WithdrawalDto;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Driving adapter for the {@code TimeDeposits} tag of the contract (ADR-0008 rule 6): implements the generated
 * interface, which carries the mappings, and translates between use cases and the generated representation. The
 * mapping stays here, next to the only operation that needs it; the web slice test covers it.
 */
@RestController
class TimeDepositsController implements TimeDepositsApi {

    private final ListTimeDepositsUseCase listTimeDeposits;

    TimeDepositsController(ListTimeDepositsUseCase listTimeDeposits) {
        this.listTimeDeposits = listTimeDeposits;
    }

    // Keeps the use case's order: deterministic (persistence orders by internal key), though the contract promises
    // no order (ADR-0004).
    @Override
    public ResponseEntity<List<TimeDepositDto>> listTimeDeposits() {
        return ResponseEntity.ok(listTimeDeposits.listAll().stream()
                .map(TimeDepositsController::toTimeDeposit)
                .toList());
    }

    // Placeholder: skipDefaultInterface makes every contract operation mandatory at compile time, and the balance
    // update is implemented in a later step. Until then the request fails like any unexpected error.
    @Override
    public ResponseEntity<BalanceUpdateDto> createBalanceUpdate(@Nullable String idempotencyKey) {
        throw new UnsupportedOperationException("POST /balance-updates is not implemented yet");
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
