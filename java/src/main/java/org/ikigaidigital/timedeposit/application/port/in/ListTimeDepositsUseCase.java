package org.ikigaidigital.timedeposit.application.port.in;

import org.ikigaidigital.timedeposit.application.model.TimeDepositView;

import java.util.List;

/** Driving port: lists all time deposits with their withdrawals ({@code GET /time-deposits}, ADR-0004). */
public interface ListTimeDepositsUseCase {

    /**
     * Returns every stored time deposit, in the order of {@link
     * org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositViewsPort}; an empty list if there are none.
     */
    List<TimeDepositView> listAll();
}
