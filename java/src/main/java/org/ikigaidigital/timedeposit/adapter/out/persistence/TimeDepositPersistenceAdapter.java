package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositViewsPort;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositsPort;
import org.ikigaidigital.timedeposit.application.port.out.SaveTimeDepositsPort;
import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Reads time deposits for the list (ADR-0008, decision 4) and loads and writes them for a balance update run
 * (ADR-0012), always as the legacy {@link TimeDeposit}. The balance is read as {@code BigDecimal.doubleValue()}, the
 * nearest double, and written rounded to cents (ADR-0006); rounding for the API happens in the REST adapter. A plain
 * {@code @Component}: the Spring Data repository already translates persistence exceptions, and adapters declare no
 * transactions (ADR-0008) — the balance update's transaction belongs to the application (ADR-0012, B).
 */
@Component
class TimeDepositPersistenceAdapter implements LoadTimeDepositViewsPort, LoadTimeDepositsPort, SaveTimeDepositsPort {

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

    @Override
    public List<TimeDeposit> loadAllForUpdate() {
        return repository.findAllForUpdate().stream()
                .map(TimeDepositPersistenceAdapter::toDeposit)
                .toList();
    }

    // One statement per deposit: simple and enough at the kata's scale (ADR-0012, Growth).
    @Override
    public void saveBalances(List<TimeDeposit> deposits) {
        for (TimeDeposit deposit : deposits) {
            if (repository.updateBalance(deposit.getId(), toCents(deposit.getBalance())) != 1) {
                throw new IllegalStateException("time deposit " + deposit.getId() + " is not stored");
            }
        }
    }

    // ADR-0006: BigDecimal.valueOf takes the double's shortest decimal string; below 10^13 its distance to the exact
    // cent sum stays under half a cent, so HALF_UP to cents recovers it (1000.9300000000001 -> 1000.93).
    private static BigDecimal toCents(double balance) {
        return BigDecimal.valueOf(balance).setScale(2, RoundingMode.HALF_UP);
    }

    private static TimeDeposit toDeposit(TimeDepositEntity entity) {
        return new TimeDeposit(entity.getId(), entity.getPlanType(), entity.getBalance().doubleValue(),
                entity.getDays());
    }

    private static TimeDepositView toView(TimeDepositEntity entity) {
        List<WithdrawalView> withdrawals = entity.getWithdrawals().stream()
                .map(w -> new WithdrawalView(w.getPublicId(), w.getAmount(), w.getDate()))
                .toList();
        return new TimeDepositView(entity.getPublicId(), new ReadOnlyTimeDeposit(toDeposit(entity)), withdrawals);
    }
}
