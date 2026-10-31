package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositViewsPort;
import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Reads time deposits for the list (ADR-0008, decision 4): one fetch-join statement, mapped to views around the
 * legacy {@link TimeDeposit}. The balance is read as {@code BigDecimal.doubleValue()}, the nearest double
 * (ADR-0006); rounding for the API happens in the REST adapter.
 */
@Component
class TimeDepositPersistenceAdapter implements LoadTimeDepositViewsPort {

    private final TimeDepositJpaRepository repository;

    TimeDepositPersistenceAdapter(TimeDepositJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<TimeDepositView> loadAll() {
        return repository.findAllWithWithdrawals().stream()
                .map(TimeDepositPersistenceAdapter::toView)
                .toList();
    }

    private static TimeDepositView toView(TimeDepositEntity entity) {
        TimeDeposit deposit = new TimeDeposit(entity.getId(), entity.getPlanType(), entity.getBalance().doubleValue(),
                entity.getDays());
        List<WithdrawalView> withdrawals = entity.getWithdrawals().stream()
                .map(w -> new WithdrawalView(w.getPublicId(), w.getAmount(), w.getDate()))
                .toList();
        return new TimeDepositView(entity.getPublicId(), new ReadOnlyTimeDeposit(deposit), withdrawals);
    }
}
