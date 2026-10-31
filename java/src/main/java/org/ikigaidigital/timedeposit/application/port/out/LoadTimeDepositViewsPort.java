package org.ikigaidigital.timedeposit.application.port.out;

import org.ikigaidigital.timedeposit.application.model.TimeDepositView;

import java.util.List;

/** Driven port: the read path for listing time deposits (ADR-0008, decision 4). */
public interface LoadTimeDepositViewsPort {

    /**
     * Returns all time deposits with their withdrawals as an unmodifiable list, never {@code null}: deposits ordered
     * by internal key, withdrawals by date and then internal key — deterministic, but not guaranteed by the OpenAPI
     * contract (ADR-0004, ADR-0008). The result is a consistent snapshot: implementations read deposits and
     * withdrawals together (e.g. in one statement), because the use case declares no transaction.
     */
    List<TimeDepositView> loadAll();
}
