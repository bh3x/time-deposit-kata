package org.ikigaidigital.timedeposit.application.port.out.fake;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory idempotency keys for application tests (ADR-0009), with the semantics the persistence tests pin for the
 * real queries: inclusive purge cutoffs, token-filtered completion and release, and rows "locked by another
 * transaction" that lookups and the purge skip. Hooks and failures let tests play the races of ADR-0012 (a successor
 * acting between reservation and run) deterministically. Calls go to a shared log.
 */
public final class InMemoryBalanceUpdateRecords implements RecordBalanceUpdatePort {

    private record Row(BalanceUpdateKeyState state, Instant createdAt, Instant completedAt) {
    }

    private final Map<String, Row> rows = new HashMap<>();
    private final Set<String> lockedByAnotherTransaction = new HashSet<>();
    private final List<String> calls;
    private Runnable beforeLock = () -> { };
    private RuntimeException lockFailure;
    private RuntimeException releaseFailure;
    private boolean completeMatchesNothing;

    public InMemoryBalanceUpdateRecords(List<String> calls) {
        this.calls = calls;
    }

    public void storeInProgress(String key, Instant createdAt) {
        rows.put(key, new Row(new InProgress(createdAt), createdAt, null));
    }

    public void storeCompleted(String key, BalanceUpdateOutcome outcome, Instant completedAt) {
        rows.put(key, new Row(new Completed(outcome), completedAt, completedAt));
    }

    public void lockByAnotherTransaction(String key) {
        lockedByAnotherTransaction.add(key);
    }

    public void remove(String key) {
        rows.remove(key);
    }

    public Optional<BalanceUpdateKeyState> stateOf(String key) {
        return Optional.ofNullable(rows.get(key)).map(Row::state);
    }

    public Optional<Instant> completedAtOf(String key) {
        return Optional.ofNullable(rows.get(key)).map(Row::completedAt);
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
    public void purge(Instant cutoff) {
        calls.add("purge");
        rows.entrySet().removeIf(entry -> !lockedByAnotherTransaction.contains(entry.getKey())
                && switch (entry.getValue().state()) {
                    case Completed completed -> !entry.getValue().completedAt().isAfter(cutoff);
                    case InProgress inProgress -> !entry.getValue().createdAt().isAfter(cutoff);
                });
    }

    @Override
    public boolean reserve(String key, Instant token) {
        calls.add("reserve");
        return rows.putIfAbsent(key, new Row(new InProgress(token), token, null)) == null;
    }

    @Override
    public Optional<BalanceUpdateKeyState> findUnlocked(String key) {
        calls.add("findUnlocked");
        return lockedByAnotherTransaction.contains(key) ? Optional.empty() : stateOf(key);
    }

    @Override
    public void takeOver(String key, Instant token) {
        calls.add("takeOver");
        if (!(stateOf(key).orElse(null) instanceof InProgress)) {
            throw new IllegalStateException("idempotency key is not in progress");
        }
        rows.put(key, new Row(new InProgress(token), token, null));
    }

    @Override
    public Optional<BalanceUpdateKeyState> lock(String key) {
        calls.add("lock");
        beforeLock.run();
        if (lockFailure != null) {
            throw lockFailure;
        }
        return stateOf(key);
    }

    @Override
    public boolean complete(String key, Instant token, BalanceUpdateOutcome outcome, Instant completedAt) {
        calls.add("complete");
        if (completeMatchesNothing || !new InProgress(token).equals(stateOf(key).orElse(null))) {
            return false;
        }
        rows.put(key, new Row(new Completed(outcome), rows.get(key).createdAt(), completedAt));
        return true;
    }

    @Override
    public void release(String key, Instant token) {
        calls.add("release");
        if (releaseFailure != null) {
            throw releaseFailure;
        }
        if (new InProgress(token).equals(stateOf(key).orElse(null))) {
            rows.remove(key);
        }
    }
}
