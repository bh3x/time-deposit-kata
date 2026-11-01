package org.ikigaidigital.timedeposit.application.port.out;

import org.ikigaidigital.TimeDeposit;

import java.util.List;

/** Driven port: the write path's load of all time deposits for a balance update run (ADR-0012, A). */
public interface LoadTimeDepositsPort {

    /**
     * Returns every stored time deposit as a legacy {@link TimeDeposit}, ordered by internal key, and keeps them
     * locked until the caller's transaction ends, so concurrent runs are serialised and never lose interest. Must be
     * called within a transaction; the deposits' ids are the internal keys {@link SaveTimeDepositsPort} writes by.
     */
    List<TimeDeposit> loadAllForUpdate();
}
