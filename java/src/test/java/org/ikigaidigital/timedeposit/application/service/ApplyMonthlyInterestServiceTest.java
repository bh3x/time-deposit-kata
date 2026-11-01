package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.MonthInProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.Succeeded;
import org.ikigaidigital.timedeposit.application.port.out.fake.InMemoryBalanceUpdatePeriods;
import org.ikigaidigital.timedeposit.application.port.out.fake.InMemoryTimeDeposits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The balance update use case of ADR-0013 with in-memory ports and the real calculator (ADR-0009): at most one run per
 * UTC calendar month, with ADR-0012's reservation, takeover, fence and release keyed by the month. The services are
 * wired by hand, so @Transactional has no effect here: transactions and locks are covered by the persistence and
 * end-to-end tests. Each test starts at the same instant, which has nanoseconds to show the truncation.
 */
class ApplyMonthlyInterestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Duration ONE_MICROSECOND = Duration.ofNanos(1_000);
    private static final BalanceUpdateOutcome STORED =
            new BalanceUpdateOutcome(3, Instant.parse("2026-11-01T08:00:00Z"));

    private final List<String> calls = new ArrayList<>();
    private final InMemoryTimeDeposits deposits = new InMemoryTimeDeposits(calls);
    private final InMemoryBalanceUpdatePeriods periods = new InMemoryBalanceUpdatePeriods(calls);
    private final MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);

    private ApplyMonthlyInterestService service() {
        return serviceWith(clock);
    }

    private ApplyMonthlyInterestService serviceWith(MutableClock clock) {
        return new ApplyMonthlyInterestService(new BalanceUpdatePeriodReservations(periods, clock),
                new MonthlyInterestRun(deposits, deposits, periods, new TimeDepositCalculator(), clock));
    }

    private static Succeeded succeeded(int processedCount, Instant executedAt) {
        return new Succeeded(new BalanceUpdateOutcome(processedCount, executedAt));
    }

    @Nested
    @DisplayName("the first request of a month runs (P1, P2, P5)")
    class FirstRequest {

        @Test
        void appliesInterestAndCountsUnchangedDeposits() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31), new TimeDeposit(2, "basic", 1000.00, 30));

            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(2, NOW_MICROS));

            assertThat(deposits.balanceOf(1)).isEqualTo(1000.83);
            assertThat(deposits.balanceOf(2)).isEqualTo(1000.00);
            assertThat(calls).containsExactly("reserve", "lock", "load", "save", "complete");
            assertThat(periods.stateOf(NOVEMBER)).contains(new Completed(new BalanceUpdateOutcome(2, NOW_MICROS)));
            assertThat(periods.completedAtOf(NOVEMBER)).contains(NOW_MICROS);
        }

        // ADR-0006: microseconds, the precision of timestamptz, truncated rather than rounded.
        @Test
        void truncatesExecutedAtToMicroseconds() {
            MutableClock late = new MutableClock(Instant.parse("2026-11-01T10:15:30.123456999Z"), ZoneOffset.UTC);

            assertThat(serviceWith(late).applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));
        }

        // ADR-0012 C: the token is the reservation time in microseconds, truncated rather than rounded, so it equals
        // what timestamptz stores and the fence's equality check matches the stored row.
        @Test
        void storesTheReservationTokenTruncatedToMicroseconds() {
            MutableClock late = new MutableClock(Instant.parse("2026-11-01T10:15:30.123456999Z"), ZoneOffset.UTC);
            List<BalanceUpdatePeriodState> atRun = new ArrayList<>();
            periods.beforeLock(() -> atRun.add(periods.stateOf(NOVEMBER).orElseThrow()));

            assertThat(serviceWith(late).applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));

            assertThat(atRun).containsExactly(new InProgress(NOW_MICROS));
            assertThat(periods.stateOf(NOVEMBER)).contains(new Completed(new BalanceUpdateOutcome(0, NOW_MICROS)));
        }

        // ADR-0012: executedAt is read once the deposits are locked, so serialised runs get increasing times.
        @Test
        void readsExecutedAtAfterTheDepositsAreLocked() {
            deposits.afterLoad(() -> clock.advance(Duration.ofSeconds(1)));

            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS.plusSeconds(1)));
        }

        // A month with no deposits is still applied: a deposit added later earns nothing until the next month.
        @Test
        void aZeroOutcomeStillCompletesTheMonth() {
            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));

            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));
            assertThat(deposits.balanceOf(1)).isEqualTo(1000.00);
        }
    }

    @Nested
    @DisplayName("at most once per UTC calendar month (P3, P6, P8)")
    class OncePerMonth {

        @Test
        void replaysTheMonthWithoutASecondRun() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));
            BalanceUpdateResult first = service().applyMonthlyInterest();
            clock.set(Instant.parse("2026-11-30T23:59:59.999999Z"));
            calls.clear();

            assertThat(service().applyMonthlyInterest()).isEqualTo(first);
            assertThat(deposits.balanceOf(1)).isEqualTo(1000.83);
            assertThat(calls).doesNotContain("load", "save");
        }

        @Test
        void runsAgainInTheNextMonth() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));
            clock.set(Instant.parse("2026-10-15T12:00:00Z"));
            service().applyMonthlyInterest();
            clock.set(Instant.parse("2026-11-01T00:00:00Z"));

            service().applyMonthlyInterest();

            assertThat(deposits.balanceOf(1)).isEqualTo(1001.66);
            assertThat(periods.periods()).containsExactlyInAnyOrder(OCTOBER, NOVEMBER);
        }

        // ADR-0013, E1: a missed month is not applied later.
        @Test
        void appliesOnlyTheCurrentMonthAfterAMissedMonth() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));
            periods.storeCompleted(YearMonth.of(2026, 9), STORED, Instant.parse("2026-09-15T00:00:00Z"));

            service().applyMonthlyInterest();

            assertThat(deposits.balanceOf(1)).isEqualTo(1000.83);
            assertThat(periods.periods()).doesNotContain(OCTOBER);
        }

        // The month is truncated from the clock in UTC, never rounded, whatever zone the clock carries.
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "2026-10-31T23:59:59.999999Z, 2026-10",
                "2026-10-31T23:59:59.999999999Z, 2026-10",
                "2026-11-01T00:00:00Z, 2026-11"})
        void takesTheUtcMonthOfTheReservation(String instant, String month) {
            serviceWith(new MutableClock(Instant.parse(instant), ZoneId.of("Europe/Berlin"))).applyMonthlyInterest();

            assertThat(periods.periods()).containsExactly(YearMonth.parse(month));
        }

        // ADR-0013: the run belongs to the month it reserved, even if it finishes after midnight.
        @Test
        void aRunReservedBeforeMidnightCompletesThatMonth() {
            clock.set(Instant.parse("2026-10-31T23:59:59.999999Z"));
            periods.beforeLock(() -> clock.advance(ONE_MICROSECOND));

            assertThat(service().applyMonthlyInterest())
                    .isEqualTo(succeeded(0, Instant.parse("2026-11-01T00:00:00Z")));
            assertThat(periods.lockedPeriods()).containsExactly(OCTOBER);
            assertThat(periods.periods()).containsExactly(OCTOBER);

            periods.beforeLock(() -> { });
            service().applyMonthlyInterest();
            assertThat(periods.periods()).containsExactlyInAnyOrder(OCTOBER, NOVEMBER);
        }
    }

    @Nested
    @DisplayName("in progress and takeover (P9, P12, P15)")
    class InProgressAndTakeover {

        @Test
        void conflictsOnAFreshReservation() {
            Instant token = NOW_MICROS.minus(Duration.ofMinutes(10)).plus(ONE_MICROSECOND);
            periods.storeInProgress(NOVEMBER, token);

            assertThat(service().applyMonthlyInterest()).isEqualTo(new MonthInProgress());
            assertThat(periods.stateOf(NOVEMBER)).contains(new InProgress(token));
            assertThat(calls).doesNotContain("load");
        }

        // A locked row is a live run, however old: never taken over (ADR-0012, C).
        @Test
        void conflictsWhenAnotherTransactionHoldsTheMonth() {
            periods.storeInProgress(NOVEMBER, NOW_MICROS.minus(Duration.ofHours(1)));
            periods.lockByAnotherTransaction(NOVEMBER);

            assertThat(service().applyMonthlyInterest()).isEqualTo(new MonthInProgress());
            assertThat(calls).doesNotContain("takeOver", "load");
        }

        @Test
        void takesOverAMonthReservedExactlyTenMinutesAgo() {
            periods.storeInProgress(NOVEMBER, NOW_MICROS.minus(Duration.ofMinutes(10)));

            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));
            assertThat(calls).containsSubsequence("takeOver", "lock", "load", "complete");
        }

        // The new token of a takeover is truncated like a reservation's (ADR-0012 C).
        @Test
        void takesOverWithATokenTruncatedToMicroseconds() {
            periods.storeInProgress(NOVEMBER, NOW_MICROS.minus(Duration.ofMinutes(10)));
            MutableClock late = new MutableClock(Instant.parse("2026-11-01T10:15:30.123456999Z"), ZoneOffset.UTC);
            List<BalanceUpdatePeriodState> atRun = new ArrayList<>();
            periods.beforeLock(() -> atRun.add(periods.stateOf(NOVEMBER).orElseThrow()));

            assertThat(serviceWith(late).applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));

            assertThat(atRun).containsExactly(new InProgress(NOW_MICROS));
        }

        @Test
        void keepsCompletedEarlierMonths() {
            periods.storeCompleted(YearMonth.of(2026, 9), STORED, Instant.parse("2026-09-15T00:00:00Z"));
            periods.storeCompleted(OCTOBER, STORED, Instant.parse("2026-10-15T00:00:00Z"));

            service().applyMonthlyInterest();

            assertThat(periods.stateOf(YearMonth.of(2026, 9))).contains(new Completed(STORED));
            assertThat(periods.stateOf(OCTOBER)).contains(new Completed(STORED));
        }

        // P15: a past month left in progress counts as missed; it never blocks a later month and is never taken over.
        @ParameterizedTest(name = "October locked by another transaction: {0}")
        @ValueSource(booleans = {false, true})
        void ignoresAPastMonthLeftInProgress(boolean locked) {
            Instant token = Instant.parse("2026-10-31T23:00:00Z");
            periods.storeInProgress(OCTOBER, token);
            if (locked) {
                periods.lockByAnotherTransaction(OCTOBER);
            }

            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));
            assertThat(periods.stateOf(OCTOBER)).contains(new InProgress(token));
            assertThat(periods.lockedPeriods()).containsExactly(NOVEMBER);
        }
    }

    @Nested
    @DisplayName("a completed month is replayed without a lock (P16)")
    class CompletedMonthWithoutLock {

        // ADR-0014: a concurrent request of the month may hold the completed row locked; it is still the month's
        // outcome, not a run in progress.
        @Test
        void replaysACompletedMonthLockedByAnotherTransaction() {
            Instant completedAt = Instant.parse("2026-11-01T08:00:01Z");
            periods.storeCompleted(NOVEMBER, STORED, completedAt);
            periods.lockByAnotherTransaction(NOVEMBER);

            assertThat(service().applyMonthlyInterest()).isEqualTo(new Succeeded(STORED));
            assertThat(calls).doesNotContain("takeOver", "lock", "load", "save", "complete", "release");
            assertThat(periods.stateOf(NOVEMBER)).contains(new Completed(STORED));
            assertThat(periods.completedAtOf(NOVEMBER)).contains(completedAt);
        }

        // The cause of the wrong 409: a replay must never take the row lock, so replays cannot make each other skip.
        @Test
        void replaysACompletedMonthWithoutLockingIt() {
            periods.storeCompleted(NOVEMBER, STORED, Instant.parse("2026-11-01T08:00:01Z"));

            for (int request = 0; request < 3; request++) {
                assertThat(service().applyMonthlyInterest()).isEqualTo(new Succeeded(STORED));
            }
            assertThat(calls).containsOnly("reserve", "find");
        }

        @Test
        void conflictsWhenTheMonthIsReleasedBetweenReserveAndRead() {
            periods.storeInProgress(NOVEMBER, NOW_MICROS.minus(Duration.ofMinutes(1)));
            periods.beforeFind(() -> periods.remove(NOVEMBER));

            assertThat(service().applyMonthlyInterest()).isEqualTo(new MonthInProgress());
            assertThat(calls).doesNotContain("findUnlocked", "load");
        }

        @Test
        void replaysWhenTheRunCompletesBetweenTheReadAndTheLock() {
            periods.storeInProgress(NOVEMBER, NOW_MICROS.minus(Duration.ofMinutes(1)));
            periods.beforeFindUnlocked(() -> periods.storeCompleted(NOVEMBER, STORED, NOW_MICROS));

            assertThat(service().applyMonthlyInterest()).isEqualTo(new Succeeded(STORED));
            assertThat(calls).doesNotContain("takeOver", "load");
        }

        // The window ADR-0014 accepts: the run was in progress when this request read the month.
        @Test
        void conflictsWhenTheRunCompletesAndIsLockedBetweenTheReadAndTheLock() {
            periods.storeInProgress(NOVEMBER, NOW_MICROS.minus(Duration.ofMinutes(1)));
            periods.beforeFindUnlocked(() -> {
                periods.storeCompleted(NOVEMBER, STORED, NOW_MICROS);
                periods.lockByAnotherTransaction(NOVEMBER);
            });

            assertThat(service().applyMonthlyInterest()).isEqualTo(new MonthInProgress());
        }
    }

    @Nested
    @DisplayName("fence: a successor acted between reservation and run (P13)")
    class Fence {

        @Test
        void conflictsWhenTheMonthIsGone() {
            periods.beforeLock(() -> periods.remove(NOVEMBER));

            assertThat(service().applyMonthlyInterest()).isEqualTo(new MonthInProgress());
            assertThat(calls).doesNotContain("load", "save", "release");
        }

        @Test
        void replaysWhenASuccessorCompletedTheMonth() {
            periods.beforeLock(() -> periods.storeCompleted(NOVEMBER, STORED, NOW_MICROS));

            assertThat(service().applyMonthlyInterest()).isEqualTo(new Succeeded(STORED));
            assertThat(calls).doesNotContain("load", "save", "release");
        }

        @Test
        void conflictsWhenASuccessorTookTheMonthOver() {
            Instant foreignToken = NOW_MICROS.plus(Duration.ofMinutes(11));
            periods.beforeLock(() -> periods.storeInProgress(NOVEMBER, foreignToken));

            assertThat(service().applyMonthlyInterest()).isEqualTo(new MonthInProgress());
            assertThat(periods.stateOf(NOVEMBER)).contains(new InProgress(foreignToken));
            assertThat(calls).doesNotContain("load", "save", "release");
        }

        @Test
        void failsAndReleasesWhenTheSwitchToCompletedMatchesNoRow() {
            periods.letCompleteMatchNothing();

            assertThatIllegalStateException().isThrownBy(() -> service().applyMonthlyInterest());
            assertThat(calls).endsWith("complete", "release");
        }
    }

    @Nested
    @DisplayName("failures release the month, so a retry runs (P9, P11)")
    class Failures {

        @Test
        void releasesTheMonthWhenTheRunFails() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));
            RuntimeException failure = new IllegalStateException("save failed");
            deposits.failOnSave(failure);

            assertThatThrownBy(() -> service().applyMonthlyInterest()).isSameAs(failure);
            assertThat(periods.stateOf(NOVEMBER)).isEmpty();

            deposits.failOnSave(null);
            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(1, NOW_MICROS));
            assertThat(deposits.balanceOf(1)).isEqualTo(1000.83);
        }

        @Test
        void releasesTheMonthWhenLockingItFails() {
            periods.failOnLock(new IllegalStateException("database unreachable"));

            assertThatIllegalStateException().isThrownBy(() -> service().applyMonthlyInterest());
            assertThat(periods.stateOf(NOVEMBER)).isEmpty();
        }

        @Test
        void keepsTheOriginalErrorWhenTheReleaseFailsToo() {
            RuntimeException failure = new IllegalStateException("save failed");
            RuntimeException releaseFailure = new IllegalStateException("release failed");
            deposits.failOnSave(failure);
            periods.failOnRelease(releaseFailure);

            assertThatThrownBy(() -> service().applyMonthlyInterest())
                    .isSameAs(failure)
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(releaseFailure));
        }
    }
}
