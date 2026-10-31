package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.shared.application.UseCase;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositViewsPort;

import java.util.List;
import java.util.Objects;

/**
 * Lists the time deposits by delegating to the read port. Deliberately thin: listing needs no domain logic. No
 * transaction is declared because the port contract requires a consistent snapshot; if an adapter cannot provide
 * one, this service gets {@code @Transactional(readOnly = true)} (ADR-0008).
 */
@UseCase
public final class ListTimeDepositsService implements ListTimeDepositsUseCase {

    private final LoadTimeDepositViewsPort timeDepositViews;

    public ListTimeDepositsService(LoadTimeDepositViewsPort timeDepositViews) {
        this.timeDepositViews = Objects.requireNonNull(timeDepositViews, "timeDepositViews");
    }

    @Override
    public List<TimeDepositView> listAll() {
        return timeDepositViews.loadAll();
    }
}
