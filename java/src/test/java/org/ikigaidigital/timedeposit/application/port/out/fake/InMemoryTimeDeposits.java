package org.ikigaidigital.timedeposit.application.port.out.fake;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.timedeposit.application.port.out.LoadTimeDepositsPort;
import org.ikigaidigital.timedeposit.application.port.out.SaveTimeDepositsPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory write path for application tests (ADR-0009): stores copies of deposits by internal key, hands out fresh
 * copies ordered by key like the real adapter, and records its calls in a shared log so tests can check the order of
 * steps. It can fail on save and run a hook after a load.
 */
public final class InMemoryTimeDeposits implements LoadTimeDepositsPort, SaveTimeDepositsPort {

    private final Map<Integer, TimeDeposit> stored = new TreeMap<>();
    private final List<String> calls;
    private Runnable afterLoad = () -> { };
    private RuntimeException saveFailure;

    public InMemoryTimeDeposits(List<String> calls) {
        this.calls = calls;
    }

    public void store(TimeDeposit... deposits) {
        for (TimeDeposit deposit : deposits) {
            stored.put(deposit.getId(), copy(deposit));
        }
    }

    public double balanceOf(int id) {
        return stored.get(id).getBalance();
    }

    public void afterLoad(Runnable hook) {
        this.afterLoad = hook;
    }

    public void failOnSave(RuntimeException failure) {
        this.saveFailure = failure;
    }

    @Override
    public List<TimeDeposit> loadAllForUpdate() {
        calls.add("load");
        List<TimeDeposit> deposits = stored.values().stream()
                .sorted(Comparator.comparingInt(TimeDeposit::getId))
                .map(InMemoryTimeDeposits::copy)
                .toList();
        afterLoad.run();
        return deposits;
    }

    @Override
    public void saveBalances(List<TimeDeposit> deposits) {
        calls.add("save");
        if (saveFailure != null) {
            throw saveFailure;
        }
        for (TimeDeposit deposit : deposits) {
            if (!stored.containsKey(deposit.getId())) {
                throw new IllegalStateException("time deposit " + deposit.getId() + " is not stored");
            }
            // Rounded to cents like the real adapter (port contract, ADR-0006): the noise of the double sum is not kept.
            double cents = BigDecimal.valueOf(deposit.getBalance()).setScale(2, RoundingMode.HALF_UP).doubleValue();
            stored.put(deposit.getId(),
                    new TimeDeposit(deposit.getId(), deposit.getPlanType(), cents, deposit.getDays()));
        }
    }

    private static TimeDeposit copy(TimeDeposit deposit) {
        return new TimeDeposit(deposit.getId(), deposit.getPlanType(), deposit.getBalance(), deposit.getDays());
    }
}
