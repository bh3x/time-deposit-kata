package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.KeyInProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateResult.Succeeded;
import org.ikigaidigital.timedeposit.application.port.out.fake.InMemoryBalanceUpdateRecords;
import org.ikigaidigital.timedeposit.application.port.out.fake.InMemoryTimeDeposits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The balance update use case of ADR-0012 with in-memory ports and the real calculator (ADR-0009). The services are
 * wired by hand, so @Transactional has no effect here: transactions and locks are covered by the persistence and
 * end-to-end tests. Each test starts at the same instant, which has nanoseconds to show the truncation.
 */
class ApplyMonthlyInterestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final Duration ONE_MICROSECOND = Duration.ofNanos(1_000);
    private static final BalanceUpdateOutcome STORED = new BalanceUpdateOutcome(3, Instant.parse("2026-10-31T08:00:00Z"));

    private final List<String> calls = new ArrayList<>();
    private final InMemoryTimeDeposits deposits = new InMemoryTimeDeposits(calls);
    private final InMemoryBalanceUpdateRecords records = new InMemoryBalanceUpdateRecords(calls);
    private final MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);

    private ApplyMonthlyInterestService service() {
        return new ApplyMonthlyInterestService(new IdempotencyKeyReservations(records, clock),
                new MonthlyInterestRun(deposits, deposits, records, new TimeDepositCalculator(), clock));
    }

    private static Succeeded succeeded(int processedCount, Instant executedAt) {
        return new Succeeded(new BalanceUpdateOutcome(processedCount, executedAt));
    }

    @Nested
    @DisplayName("without a key: one run (P1-P3, P5)")
    class WithoutKey {

        @Test
        void appliesInterestAndCountsUnchangedDeposits() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31), new TimeDeposit(2, "basic", 1000.00, 30));

            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(2, NOW_MICROS));
            assertThat(deposits.balanceOf(1)).isEqualTo(1000.83);
            assertThat(deposits.balanceOf(2)).isEqualTo(1000.00);
        }

        // ADR-0006: microseconds, the precision of timestamptz, truncated rather than rounded; always UTC.
        @Test
        void truncatesExecutedAtToMicrosecondsWhateverTheClockZone() {
            MutableClock berlin = new MutableClock(Instant.parse("2026-11-01T10:15:30.123456999Z"),
                    ZoneId.of("Europe/Berlin"));
            ApplyMonthlyInterestService service = new ApplyMonthlyInterestService(
                    new IdempotencyKeyReservations(records, berlin),
                    new MonthlyInterestRun(deposits, deposits, records, new TimeDepositCalculator(), berlin));

            assertThat(service.applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));
        }

        // ADR-0012: executedAt is read once the deposits are locked, so serialised runs get increasing times.
        @Test
        void readsExecutedAtAfterTheDepositsAreLocked() {
            deposits.afterLoad(() -> clock.advance(Duration.ofSeconds(1)));

            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS.plusSeconds(1)));
        }

        @Test
        void eachCallAppliesInterestAgain() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));

            service().applyMonthlyInterest();
            service().applyMonthlyInterest();

            assertThat(deposits.balanceOf(1)).isEqualTo(1001.66);
        }

        @Test
        void returnsZeroWhenNoDepositsExist() {
            assertThat(service().applyMonthlyInterest()).isEqualTo(succeeded(0, NOW_MICROS));
        }

        @Test
        void touchesNoIdempotencyKey() {
            service().applyMonthlyInterest();

            assertThat(calls).containsExactly("load", "save");
        }
    }

    @Nested
    @DisplayName("with a key: reserve, run, complete (P6, P9, P12)")
    class WithKey {

        @Test
        void purgesReservesRunsAndCompletesInThisOrder() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(succeeded(1, NOW_MICROS));

            assertThat(calls).containsExactly("purge", "reserve", "lock", "load", "save", "complete");
            assertThat(records.stateOf("k")).contains(new Completed(new BalanceUpdateOutcome(1, NOW_MICROS)));
            assertThat(records.completedAtOf("k")).contains(NOW_MICROS);
            assertThat(deposits.balanceOf(1)).isEqualTo(1000.83);
        }

        @Test
        void replaysACompletedKeyWithoutRunning() {
            records.storeCompleted("k", STORED, NOW.minus(Duration.ofHours(1)));
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(new Succeeded(STORED));
            assertThat(calls).doesNotContain("load", "save");
            assertThat(deposits.balanceOf(1)).isEqualTo(1000.00);
        }

        @Test
        void conflictsOnAFreshReservation() {
            Instant createdAt = NOW_MICROS.minus(Duration.ofMinutes(10)).plus(ONE_MICROSECOND);
            records.storeInProgress("k", createdAt);

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(new KeyInProgress());
            assertThat(records.stateOf("k")).contains(new InProgress(createdAt));
            assertThat(calls).doesNotContain("load");
        }

        // A locked row is a live run, however old: never taken over (ADR-0012, C).
        @Test
        void conflictsWhenAnotherTransactionHoldsTheKey() {
            records.storeInProgress("k", NOW_MICROS.minus(Duration.ofHours(1)));
            records.lockByAnotherTransaction("k");

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(new KeyInProgress());
            assertThat(calls).doesNotContain("takeOver", "load");
        }

        @Test
        void takesOverAReservationThatIsExactlyTenMinutesOld() {
            records.storeInProgress("k", NOW_MICROS.minus(Duration.ofMinutes(10)));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(succeeded(0, NOW_MICROS));
            assertThat(calls).containsSubsequence("takeOver", "lock", "load", "complete");
        }

        @Test
        void runsAgainForAKeyCompletedExactlyTwentyFourHoursAgo() {
            records.storeCompleted("k", STORED, NOW_MICROS.minus(Duration.ofHours(24)));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(succeeded(0, NOW_MICROS));
        }

        @Test
        void replaysAKeyCompletedJustUnderTwentyFourHoursAgo() {
            records.storeCompleted("k", STORED, NOW_MICROS.minus(Duration.ofHours(24)).plus(ONE_MICROSECOND));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(new Succeeded(STORED));
        }

        @Test
        void purgesAbandonedReservationsOfOtherKeysFromTwentyFourHours() {
            records.storeInProgress("abandoned", NOW_MICROS.minus(Duration.ofHours(24)));
            records.storeInProgress("young", NOW_MICROS.minus(Duration.ofHours(24)).plus(ONE_MICROSECOND));
            records.storeInProgress("locked", NOW_MICROS.minus(Duration.ofHours(48)));
            records.lockByAnotherTransaction("locked");

            service().applyMonthlyInterest("k");

            assertThat(records.stateOf("abandoned")).isEmpty();
            assertThat(records.stateOf("young")).isPresent();
            assertThat(records.stateOf("locked")).isPresent();
        }
    }

    @Nested
    @DisplayName("fence: a successor acted between reservation and run (P13)")
    class Fence {

        @Test
        void conflictsWhenTheKeyIsGone() {
            records.beforeLock(() -> records.remove("k"));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(new KeyInProgress());
            assertThat(calls).doesNotContain("load", "save", "release");
        }

        @Test
        void replaysWhenASuccessorCompletedTheKey() {
            records.beforeLock(() -> records.storeCompleted("k", STORED, NOW));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(new Succeeded(STORED));
            assertThat(calls).doesNotContain("load", "save", "release");
        }

        @Test
        void conflictsWhenASuccessorTookTheKeyOver() {
            Instant foreignToken = NOW_MICROS.plus(Duration.ofMinutes(11));
            records.beforeLock(() -> records.storeInProgress("k", foreignToken));

            assertThat(service().applyMonthlyInterest("k")).isEqualTo(new KeyInProgress());
            assertThat(records.stateOf("k")).contains(new InProgress(foreignToken));
            assertThat(calls).doesNotContain("load", "save", "release");
        }

        @Test
        void failsAndReleasesWhenTheSwitchToCompletedMatchesNoRow() {
            records.letCompleteMatchNothing();

            assertThatIllegalStateException().isThrownBy(() -> service().applyMonthlyInterest("k"));
            assertThat(calls).endsWith("complete", "release");
        }
    }

    @Nested
    @DisplayName("failures release the reservation, so a retry runs (P9, P11)")
    class Failures {

        @Test
        void releasesTheKeyWhenTheRunFails() {
            deposits.store(new TimeDeposit(1, "basic", 1000.00, 31));
            RuntimeException failure = new IllegalStateException("save failed");
            deposits.failOnSave(failure);

            assertThatThrownBy(() -> service().applyMonthlyInterest("k")).isSameAs(failure);
            assertThat(records.stateOf("k")).isEmpty();

            deposits.failOnSave(null);
            assertThat(service().applyMonthlyInterest("k")).isEqualTo(succeeded(1, NOW_MICROS));
            assertThat(deposits.balanceOf(1)).isEqualTo(1000.83);
        }

        @Test
        void releasesTheKeyWhenLockingItFails() {
            records.failOnLock(new IllegalStateException("database unreachable"));

            assertThatIllegalStateException().isThrownBy(() -> service().applyMonthlyInterest("k"));
            assertThat(records.stateOf("k")).isEmpty();
        }

        @Test
        void keepsTheOriginalErrorWhenTheReleaseFailsToo() {
            RuntimeException failure = new IllegalStateException("save failed");
            RuntimeException releaseFailure = new IllegalStateException("release failed");
            deposits.failOnSave(failure);
            records.failOnRelease(releaseFailure);

            assertThatThrownBy(() -> service().applyMonthlyInterest("k"))
                    .isSameAs(failure)
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(releaseFailure));
        }

        @Test
        void releasesNothingWithoutAKey() {
            deposits.failOnSave(new IllegalStateException("save failed"));

            assertThatIllegalStateException().isThrownBy(() -> service().applyMonthlyInterest());
            assertThat(calls).doesNotContain("release");
        }
    }
}
