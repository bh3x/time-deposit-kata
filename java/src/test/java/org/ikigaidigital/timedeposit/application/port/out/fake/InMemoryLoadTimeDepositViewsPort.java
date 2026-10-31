package org.ikigaidigital.timedeposit.application.port.out.fake;

import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositViewsPort;

import java.util.List;

/**
 * In-memory {@link LoadTimeDepositViewsPort} for application tests (ADR-0009): returns the views it was given, in
 * that order, as an unmodifiable list like the real adapter, or fails on demand.
 */
public final class InMemoryLoadTimeDepositViewsPort implements LoadTimeDepositViewsPort {

    private List<TimeDepositView> views = List.of();
    private RuntimeException failure;

    public void replaceWith(TimeDepositView... views) {
        this.views = List.of(views);
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public List<TimeDepositView> loadAll() {
        if (failure != null) {
            throw failure;
        }
        return views;
    }
}
