package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency key queries of ADR-0012 against PostgreSQL, within one transaction per test (scenarios P6, P8, P12,
 * P13). Behaviour across connections (SKIP LOCKED, waits) is covered by {@code BalanceUpdateLockingTest}. Times are
 * passed in from the application clock, never taken from the database's now().
 */
@DataJpaTest
@Import({PostgresContainerConfiguration.class, IdempotencyKeyPersistenceAdapter.class})
class IdempotencyKeyPersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final Instant TOKEN = NOW.minus(Duration.ofMinutes(1));
    private static final Instant OTHER_TOKEN = NOW.minus(Duration.ofMinutes(2));
    private static final BalanceUpdateOutcome OUTCOME =
            new BalanceUpdateOutcome(3, Instant.parse("2026-11-01T10:15:29.654321Z"));

    @Autowired
    private IdempotencyKeyPersistenceAdapter adapter;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
    }

    private void insertInProgress(String key, Instant createdAt) {
        jdbc.sql("""
                insert into "idempotencyKeys" ("idempotencyKey", "status", "createdAt") values (?, 'IN_PROGRESS', ?)
                """).params(key, utc(createdAt)).update();
    }

    private void insertCompleted(String key, Instant completedAt) {
        jdbc.sql("""
                insert into "idempotencyKeys"
                    ("idempotencyKey", "status", "processedCount", "executedAt", "createdAt", "completedAt")
                values (?, 'COMPLETED', 3, ?, ?, ?)
                """).params(key, utc(completedAt), utc(completedAt), utc(completedAt)).update();
    }

    private boolean exists(String key) {
        return jdbc.sql("select count(*) from \"idempotencyKeys\" where \"idempotencyKey\" = ?").param(key)
                .query(Integer.class).single() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("reserve and inspect")
    class Reserve {

        @Test
        void reservesANewKeyInProgressWithItsToken() {
            assertThat(adapter.reserve("k", TOKEN)).isTrue();

            assertThat(adapter.findUnlocked("k")).contains(new InProgress(TOKEN));
        }

        @Test
        void doesNotReserveAnExistingKey() {
            insertInProgress("k", OTHER_TOKEN);

            assertThat(adapter.reserve("k", TOKEN)).isFalse();
            assertThat(adapter.findUnlocked("k")).contains(new InProgress(OTHER_TOKEN));
        }

        @Test
        void findsNothingForAnUnknownKey() {
            assertThat(adapter.findUnlocked("unknown")).isEmpty();
            assertThat(adapter.lock("unknown")).isEmpty();
        }

        @Test
        void takesOverAnInProgressKeyWithANewToken() {
            insertInProgress("k", OTHER_TOKEN);
            adapter.findUnlocked("k");

            adapter.takeOver("k", TOKEN);

            assertThat(adapter.lock("k")).contains(new InProgress(TOKEN));
        }
    }

    @Nested
    @DisplayName("P6/P8/P13: complete and release only with the own token")
    class CompleteAndRelease {

        // P8: the longest key the contract allows; P6: executedAt survives the round trip to the microsecond, in UTC.
        @Test
        void completesAndReadsBackTheOutcome() {
            String key = "k".repeat(255);
            adapter.reserve(key, TOKEN);

            assertThat(adapter.complete(key, TOKEN, OUTCOME, NOW)).isTrue();

            BalanceUpdateKeyState state = adapter.lock(key).orElseThrow();
            assertThat(state).isEqualTo(new Completed(OUTCOME));
            assertThat(jdbc.sql("select \"completedAt\" from \"idempotencyKeys\"").query(OffsetDateTime.class)
                    .single().toInstant()).isEqualTo(NOW);
        }

        @Test
        void doesNotCompleteWithAForeignToken() {
            insertInProgress("k", OTHER_TOKEN);

            assertThat(adapter.complete("k", TOKEN, OUTCOME, NOW)).isFalse();
            assertThat(adapter.lock("k")).contains(new InProgress(OTHER_TOKEN));
        }

        @Test
        void doesNotCompleteAMissingOrCompletedKey() {
            insertCompleted("done", NOW);

            assertThat(adapter.complete("missing", TOKEN, OUTCOME, NOW)).isFalse();
            assertThat(adapter.complete("done", NOW, OUTCOME, NOW)).isFalse();
        }

        @Test
        void releasesTheOwnReservation() {
            adapter.reserve("k", TOKEN);

            adapter.release("k", TOKEN);

            assertThat(exists("k")).isFalse();
        }

        @Test
        void keepsAReservationWithAForeignToken() {
            insertInProgress("k", OTHER_TOKEN);

            adapter.release("k", TOKEN);

            assertThat(exists("k")).isTrue();
        }

        @Test
        void keepsACompletedKey() {
            insertCompleted("k", NOW);

            adapter.release("k", NOW);

            assertThat(exists("k")).isTrue();
        }
    }

    @Nested
    @DisplayName("P12: purge at the exact 24-hour boundary")
    class Purge {

        private static final Instant CUTOFF = NOW.minus(Duration.ofHours(24));
        private static final Duration ONE_MICROSECOND = Duration.ofNanos(1_000);

        @Test
        void deletesCompletedKeysUpToTheCutoff() {
            insertCompleted("expired", CUTOFF);
            insertCompleted("kept", CUTOFF.plus(ONE_MICROSECOND));

            adapter.purge(CUTOFF);

            assertThat(exists("expired")).isFalse();
            assertThat(exists("kept")).isTrue();
        }

        @Test
        void deletesAbandonedReservationsUpToTheCutoff() {
            insertInProgress("abandoned", CUTOFF);
            insertInProgress("kept", CUTOFF.plus(ONE_MICROSECOND));

            adapter.purge(CUTOFF);

            assertThat(exists("abandoned")).isFalse();
            assertThat(exists("kept")).isTrue();
        }

        // completedAt decides for a completed key, not createdAt.
        @Test
        void keepsACompletedKeyCreatedBeforeButCompletedAfterTheCutoff() {
            jdbc.sql("""
                    insert into "idempotencyKeys"
                        ("idempotencyKey", "status", "processedCount", "executedAt", "createdAt", "completedAt")
                    values ('k', 'COMPLETED', 1, ?, ?, ?)
                    """).params(utc(NOW), utc(CUTOFF.minus(Duration.ofHours(1))), utc(NOW)).update();

            adapter.purge(CUTOFF);

            assertThat(exists("k")).isTrue();
        }
    }
}
