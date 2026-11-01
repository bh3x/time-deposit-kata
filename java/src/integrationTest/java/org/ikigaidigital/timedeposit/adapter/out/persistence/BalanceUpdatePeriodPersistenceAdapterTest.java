package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.InProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The balance update period queries of ADR-0013 against PostgreSQL, within one transaction per test (scenarios P6,
 * P12, P13). Behaviour across connections (SKIP LOCKED, waits) is covered by {@code BalanceUpdateLockingTest}. Times
 * are passed in from the application clock, never taken from the database.
 */
@DataJpaTest
@Import({PostgresContainerConfiguration.class, BalanceUpdatePeriodPersistenceAdapter.class})
class BalanceUpdatePeriodPersistenceAdapterTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final Instant TOKEN = NOW.minus(Duration.ofMinutes(1));
    private static final Instant OTHER_TOKEN = NOW.minus(Duration.ofMinutes(2));
    private static final BalanceUpdateOutcome OUTCOME =
            new BalanceUpdateOutcome(3, Instant.parse("2026-11-01T10:15:29.654321Z"));

    @Autowired
    private BalanceUpdatePeriodPersistenceAdapter adapter;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
    }

    private void insertInProgress(YearMonth period, Instant createdAt) {
        jdbc.sql("""
                insert into "balanceUpdatePeriods" ("period", "status", "createdAt") values (?, 'IN_PROGRESS', ?)
                """).params(period.atDay(1), utc(createdAt)).update();
    }

    private void insertCompleted(YearMonth period, Instant completedAt) {
        jdbc.sql("""
                insert into "balanceUpdatePeriods"
                    ("period", "status", "processedCount", "executedAt", "createdAt", "completedAt")
                values (?, 'COMPLETED', 3, ?, ?, ?)
                """).params(period.atDay(1), utc(completedAt), utc(completedAt), utc(completedAt)).update();
    }

    private boolean exists(YearMonth period) {
        return jdbc.sql("select count(*) from \"balanceUpdatePeriods\" where \"period\" = ?").param(period.atDay(1))
                .query(Integer.class).single() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("reserve and inspect")
    class Reserve {

        @Test
        void reservesANewMonthInProgressWithItsToken() {
            assertThat(adapter.reserve(NOVEMBER, TOKEN)).isTrue();

            assertThat(adapter.findUnlocked(NOVEMBER)).contains(new InProgress(TOKEN));
            assertThat(jdbc.sql("select \"period\"::text from \"balanceUpdatePeriods\"").query(String.class).single())
                    .isEqualTo("2026-11-01");
        }

        // The month is stored as a DATE, so the session time zone of the connection must not shift it.
        @ParameterizedTest(name = "session time zone {0}")
        @ValueSource(strings = {"Pacific/Kiritimati", "Pacific/Pago_Pago"})
        void storesTheFirstDayWhateverTheSessionTimeZone(String timeZone) {
            jdbc.sql("set local timezone to '" + timeZone + "'").update();

            adapter.reserve(NOVEMBER, TOKEN);

            assertThat(jdbc.sql("select \"period\"::text from \"balanceUpdatePeriods\"").query(String.class).single())
                    .isEqualTo("2026-11-01");
        }

        @Test
        void doesNotReserveAnExistingMonth() {
            insertInProgress(NOVEMBER, OTHER_TOKEN);

            assertThat(adapter.reserve(NOVEMBER, TOKEN)).isFalse();
            assertThat(adapter.findUnlocked(NOVEMBER)).contains(new InProgress(OTHER_TOKEN));
        }

        @Test
        void findsAMonthByItsPeriodOnly() {
            insertInProgress(OCTOBER, OTHER_TOKEN);

            assertThat(adapter.findUnlocked(OCTOBER)).contains(new InProgress(OTHER_TOKEN));
            assertThat(adapter.findUnlocked(NOVEMBER)).isEmpty();
            assertThat(adapter.lock(NOVEMBER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("ADR-0012/0014: take over only an in-progress month")
    class TakeOver {

        private int rowCount() {
            return jdbc.sql("select count(*) from \"balanceUpdatePeriods\"").query(Integer.class).single();
        }

        @Test
        void takesOverAnInProgressMonthWithANewToken() {
            insertInProgress(NOVEMBER, OTHER_TOKEN);
            adapter.findUnlocked(NOVEMBER);

            adapter.takeOver(NOVEMBER, TOKEN);

            assertThat(adapter.lock(NOVEMBER)).contains(new InProgress(TOKEN));
        }

        @Test
        void takesOverOnlyTheNamedMonth() {
            insertInProgress(OCTOBER, OTHER_TOKEN);
            insertInProgress(NOVEMBER, OTHER_TOKEN);
            adapter.findUnlocked(NOVEMBER);

            adapter.takeOver(NOVEMBER, TOKEN);

            assertThat(adapter.find(NOVEMBER)).contains(new InProgress(TOKEN));
            assertThat(adapter.find(OCTOBER)).contains(new InProgress(OTHER_TOKEN));
        }

        // The October row catches an update that matches by status but not by month: it would change one row and
        // not throw.
        @Test
        void refusesToTakeOverAMissingMonth() {
            insertInProgress(OCTOBER, OTHER_TOKEN);

            assertThatIllegalStateException().isThrownBy(() -> adapter.takeOver(NOVEMBER, TOKEN))
                    .withMessageContaining("2026-11");

            assertThat(exists(NOVEMBER)).isFalse();
            assertThat(adapter.find(OCTOBER)).contains(new InProgress(OTHER_TOKEN));
            assertThat(rowCount()).isOne();
        }

        @Test
        void refusesToTakeOverACompletedMonth() {
            insertCompleted(NOVEMBER, OUTCOME.executedAt());

            assertThatIllegalStateException().isThrownBy(() -> adapter.takeOver(NOVEMBER, TOKEN))
                    .withMessageContaining("2026-11");

            assertThat(adapter.find(NOVEMBER)).contains(new Completed(OUTCOME));
            assertThat(jdbc.sql("""
                    select "createdAt" from "balanceUpdatePeriods" where "period" = date '2026-11-01'
                    """).query(OffsetDateTime.class).single().toInstant()).isEqualTo(OUTCOME.executedAt());
            assertThat(rowCount()).isOne();
        }
    }

    @Nested
    @DisplayName("P16: find without a lock")
    class Find {

        @Test
        void findsAMonthInProgressWithItsToken() {
            insertInProgress(NOVEMBER, TOKEN);

            assertThat(adapter.find(NOVEMBER)).contains(new InProgress(TOKEN));
        }

        @Test
        void findsACompletedMonthWithItsOutcome() {
            insertCompleted(NOVEMBER, OUTCOME.executedAt());

            assertThat(adapter.find(NOVEMBER)).contains(new Completed(OUTCOME));
        }

        @Test
        void findsAMonthByItsPeriodOnly() {
            insertInProgress(OCTOBER, OTHER_TOKEN);
            insertCompleted(NOVEMBER.plusMonths(1), OUTCOME.executedAt());

            assertThat(adapter.find(OCTOBER)).contains(new InProgress(OTHER_TOKEN));
            assertThat(adapter.find(NOVEMBER.plusMonths(1))).contains(new Completed(OUTCOME));
            assertThat(adapter.find(NOVEMBER)).isEmpty();
        }

        // The month is bound as a DATE, so the session time zone must not shift the lookup by a day.
        @ParameterizedTest(name = "session time zone {0}")
        @ValueSource(strings = {"Pacific/Kiritimati", "Pacific/Pago_Pago"})
        void findsTheMonthWhateverTheSessionTimeZone(String timeZone) {
            insertCompleted(NOVEMBER, OUTCOME.executedAt());
            jdbc.sql("set local timezone to '" + timeZone + "'").update();

            assertThat(adapter.find(NOVEMBER)).contains(new Completed(OUTCOME));
        }
    }

    @Nested
    @DisplayName("P6/P12/P13: complete and release only the own month and token")
    class CompleteAndRelease {

        // P6: executedAt survives the round trip to the microsecond, in UTC.
        @Test
        void completesAndReadsBackTheOutcome() {
            adapter.reserve(NOVEMBER, TOKEN);

            assertThat(adapter.complete(NOVEMBER, TOKEN, OUTCOME, NOW)).isTrue();

            assertThat(adapter.lock(NOVEMBER)).contains(new Completed(OUTCOME));
            assertThat(jdbc.sql("select \"completedAt\" from \"balanceUpdatePeriods\"").query(OffsetDateTime.class)
                    .single().toInstant()).isEqualTo(NOW);
        }

        @Test
        void doesNotCompleteWithAForeignToken() {
            insertInProgress(NOVEMBER, OTHER_TOKEN);

            assertThat(adapter.complete(NOVEMBER, TOKEN, OUTCOME, NOW)).isFalse();
            assertThat(adapter.lock(NOVEMBER)).contains(new InProgress(OTHER_TOKEN));
        }

        @Test
        void doesNotCompleteAMissingOrCompletedMonth() {
            insertCompleted(OCTOBER, NOW);

            assertThat(adapter.complete(NOVEMBER, TOKEN, OUTCOME, NOW)).isFalse();
            assertThat(adapter.complete(OCTOBER, NOW, OUTCOME, NOW)).isFalse();
        }

        // Two months in progress with the same token: only the named month is touched.
        @Test
        void completesAndReleasesOnlyItsOwnMonth() {
            insertInProgress(OCTOBER, TOKEN);
            insertInProgress(NOVEMBER, TOKEN);

            assertThat(adapter.complete(NOVEMBER, TOKEN, OUTCOME, NOW)).isTrue();
            adapter.release(OCTOBER, TOKEN);

            assertThat(exists(OCTOBER)).isFalse();
            assertThat(adapter.lock(NOVEMBER)).contains(new Completed(OUTCOME));
        }

        @Test
        void releasesTheOwnReservation() {
            adapter.reserve(NOVEMBER, TOKEN);

            adapter.release(NOVEMBER, TOKEN);

            assertThat(exists(NOVEMBER)).isFalse();
        }

        @Test
        void keepsAReservationWithAForeignToken() {
            insertInProgress(NOVEMBER, OTHER_TOKEN);

            adapter.release(NOVEMBER, TOKEN);

            assertThat(exists(NOVEMBER)).isTrue();
        }

        @Test
        void keepsACompletedMonth() {
            insertCompleted(NOVEMBER, NOW);

            adapter.release(NOVEMBER, NOW);

            assertThat(exists(NOVEMBER)).isTrue();
        }

        // P12: completed months are kept; nothing purges them.
        @Test
        void keepsAnEarlierCompletedMonthWhenTheNextOneCompletes() {
            insertCompleted(OCTOBER, Instant.parse("2026-10-15T00:00:00Z"));
            adapter.reserve(NOVEMBER, TOKEN);

            adapter.complete(NOVEMBER, TOKEN, OUTCOME, NOW);

            assertThat(exists(OCTOBER)).isTrue();
        }
    }
}
