package org.ikigaidigital.timedeposit.application.port.out.fake;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.InProgress;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory balance update periods for application tests (ADR-0009), with the semantics the persistence tests pin for
 * the real queries: token-filtered completion and release, rows "locked by another transaction" that lookups skip,
 * no purge. Hooks and failures let tests play the races of ADR-0012/0013 (a successor acting between reservation and
 * run, or a run completing between the reservation's reads) deterministically. Calls go to a shared log; the months
 * passed to {@code lock} are recorded.
 */
public final class InMemoryBalanceUpdatePeriods implements RecordBalanceUpdatePort {

    private record Row(BalanceUpdatePeriodState state, Instant completedAt) {
    }

    private final Map<YearMonth, Row> rows = new HashMap<>();
    private final Set<YearMonth> lockedByAnotherTransaction = new HashSet<>();
    private final List<YearMonth> lockedPeriods = new ArrayList<>();
    private final List<String> calls;
    private Runnable beforeFind = () -> { };
    private Runnable beforeFindUnlocked = () -> { };
    private Runnable beforeLock = () -> { };
    private RuntimeException lockFailure;
    private RuntimeException releaseFailure;
    private boolean completeMatchesNothing;

    public InMemoryBalanceUpdatePeriods(List<String> calls) {
        this.calls = calls;
    }

    // The port promises times truncated to microseconds, the precision of timestamptz (ADR-0012 C). A finer instant
    // would match its own copy here but not the row PostgreSQL stores, so the fake rejects it instead of hiding a
    // missing truncation.
    private static Instant microseconds(Instant instant, String what) {
        if (instant.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(what + " is finer than microseconds: " + instant);
        }
        return instant;
    }

    public void storeInProgress(YearMonth period, Instant token) {
        rows.put(period, new Row(new InProgress(microseconds(token, "token")), null));
    }

    public void storeCompleted(YearMonth period, BalanceUpdateOutcome outcome, Instant completedAt) {
        microseconds(outcome.executedAt(), "executedAt");
        rows.put(period, new Row(new Completed(outcome), microseconds(completedAt, "completedAt")));
    }

    public void lockByAnotherTransaction(YearMonth period) {
        lockedByAnotherTransaction.add(period);
    }

    public void remove(YearMonth period) {
        rows.remove(period);
    }

    public Optional<BalanceUpdatePeriodState> stateOf(YearMonth period) {
        return Optional.ofNullable(rows.get(period)).map(Row::state);
    }

    public Optional<Instant> completedAtOf(YearMonth period) {
        return Optional.ofNullable(rows.get(period)).map(Row::completedAt);
    }

    public Set<YearMonth> periods() {
        return Set.copyOf(rows.keySet());
    }

    public List<YearMonth> lockedPeriods() {
        return List.copyOf(lockedPeriods);
    }

    public void beforeFind(Runnable hook) {
        this.beforeFind = hook;
    }

    public void beforeFindUnlocked(Runnable hook) {
        this.beforeFindUnlocked = hook;
    }

    public void beforeLock(Runnable hook) {
        this.beforeLock = hook;
    }

    public void failOnLock(RuntimeException failure) {
        this.lockFailure = failure;
    }

    public void failOnRelease(RuntimeException failure) {
        this.releaseFailure = failure;
    }

    public void letCompleteMatchNothing() {
        this.completeMatchesNothing = true;
    }

    @Override
    public boolean reserve(YearMonth period, Instant token) {
        calls.add("reserve");
        return rows.putIfAbsent(period, new Row(new InProgress(microseconds(token, "token")), null)) == null;
    }

    // Reads past a lock held by another transaction, like a plain SELECT (ADR-0014).
    @Override
    public Optional<BalanceUpdatePeriodState> find(YearMonth period) {
        calls.add("find");
        beforeFind.run();
        return stateOf(period);
    }

    @Override
    public Optional<BalanceUpdatePeriodState> findUnlocked(YearMonth period) {
        calls.add("findUnlocked");
        beforeFindUnlocked.run();
        return lockedByAnotherTransaction.contains(period) ? Optional.empty() : stateOf(period);
    }

    @Override
    public void takeOver(YearMonth period, Instant token) {
        calls.add("takeOver");
        microseconds(token, "token");
        if (!(stateOf(period).orElse(null) instanceof InProgress)) {
            throw new IllegalStateException("balance update period " + period + " is not in progress");
        }
        rows.put(period, new Row(new InProgress(token), null));
    }

    @Override
    public Optional<BalanceUpdatePeriodState> lock(YearMonth period) {
        calls.add("lock");
        lockedPeriods.add(period);
        beforeLock.run();
        if (lockFailure != null) {
            throw lockFailure;
        }
        return stateOf(period);
    }

    @Override
    public boolean complete(YearMonth period, Instant token, BalanceUpdateOutcome outcome, Instant completedAt) {
        calls.add("complete");
        microseconds(token, "token");
        microseconds(outcome.executedAt(), "executedAt");
        microseconds(completedAt, "completedAt");
        if (completeMatchesNothing || !new InProgress(token).equals(stateOf(period).orElse(null))) {
            return false;
        }
        rows.put(period, new Row(new Completed(outcome), completedAt));
        return true;
    }

    @Override
    public void release(YearMonth period, Instant token) {
        calls.add("release");
        microseconds(token, "token");
        if (releaseFailure != null) {
            throw releaseFailure;
        }
        if (new InProgress(token).equals(stateOf(period).orElse(null))) {
            rows.remove(period);
        }
    }
}
