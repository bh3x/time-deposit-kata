package org.ikigaidigital.timedeposit.application.port.out;

import org.ikigaidigital.timedeposit.application.model.TimeDepositView;

import java.util.List;

/** Driven port: the read path for listing time deposits (ADR-0008, decision 4). */
public interface LoadTimeDepositViewsPort {

    /**
     * Returns all time deposits with their withdrawals as an unmodifiable list, never {@code null}: deposits ordered
     * by internal key, withdrawals by date and then internal key — deterministic, not guaranteed by the contract
     * (ADR-0004, ADR-0008).
     */
    List<TimeDepositView> loadAll();
}
