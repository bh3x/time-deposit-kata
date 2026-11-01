package org.ikigaidigital.timedeposit;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/balance-updates} end to end (docs/testing.md P1, P6, P10, P11, P14, S6): HTTP, controller, use
 * case, persistence and PostgreSQL, with real commits and a test clock the tests can move. Overlaps are forced, not
 * hoped for: the test holds the deposit rows locked in its own JDBC transaction, starts the requests, waits until
 * PostgreSQL reports them waiting for a lock (pg_locks, polled with a deadline — no fixed sleep), then releases. The
 * month rules themselves (boundaries, missed months, takeover) are owned by the application tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({PostgresContainerConfiguration.class, ApplyMonthlyInterestEndToEndTest.TestClock.class})
class ApplyMonthlyInterestEndToEndTest {

    private static final String BALANCE_UPDATES = "/api/v1/balance-updates";
    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final Duration DEADLINE = Duration.ofSeconds(10);

    /** A clock the tests set; thread-safe, because requests read it on server threads. */
    static final class SettableClock extends Clock {

        private final AtomicReference<Instant> now;

        SettableClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock(new AtomicReference<>(NOW));
        }
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SettableClock clock;

    private JdbcClient jdbc;
    private TimeDepositRows rows;
    private final ExecutorService requests = Executors.newFixedThreadPool(2);

    @BeforeEach
    void startFromAnEmptyMonth() {
        clock.set(NOW);
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate \"withdrawals\", \"timeDeposits\", \"balanceUpdatePeriods\" restart identity").update();
        rows = new TimeDepositRows(jdbc);
    }

    @AfterEach
    void stopRequests() {
        requests.shutdownNow();
    }

    private EntityExchangeResult<String> post() {
        return client.post().uri(BALANCE_UPDATES).exchange().expectBody(String.class).returnResult();
    }

    private CompletableFuture<EntityExchangeResult<String>> postAsync() {
        return CompletableFuture.supplyAsync(this::post, requests);
    }

    private String balanceOf(int id) {
        return jdbc.sql("select \"balance\"::text from \"timeDeposits\" where \"id\" = ?").param(id)
                .query(String.class).single();
    }

    private List<String> completedPeriods() {
        return jdbc.sql("""
                select "period"::text from "balanceUpdatePeriods" where "status" = 'COMPLETED' order by "period"
                """).query(String.class).list();
    }

    // Holds all deposit rows locked on a separate connection while the action runs, then releases them.
    private void whileDepositsAreLocked(ThrowingRunnable action) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("select 1 from \"timeDeposits\" for update");
                action.run();
            } finally {
                holder.rollback();
            }
        }
    }

    // Polls until the given number of sessions waits for a lock, or fails after the deadline.
    private void awaitSessionsWaitingForALock(int sessions) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (waitingSessions() < sessions) {
            assertThat(System.nanoTime()).as("sessions waiting for a lock before the deadline").isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private int waitingSessions() {
        return jdbc.sql("select count(distinct pid) from pg_locks where not granted").query(Integer.class).single();
    }

    private static EntityExchangeResult<String> await(CompletableFuture<EntityExchangeResult<String>> future)
            throws Exception {
        return future.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // P1, P2: one month of interest for every stored deposit, unchanged ones included in the count.
    @Test
    void appliesOneMonthOfInterestToAllStoredDeposits() {
        int earning = rows.insertDeposit("basic", 31, "1000.00");
        int unchanged = rows.insertDeposit("basic", 30, "1000.00");

        client.post().uri(BALANCE_UPDATES).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().json("""
                        {"processedCount": 2, "executedAt": "2026-11-01T10:15:30.123456Z"}
                        """, JsonCompareMode.STRICT);

        assertThat(balanceOf(earning)).isEqualTo("1000.83");
        assertThat(balanceOf(unchanged)).isEqualTo("1000.00");
        assertThat(completedPeriods()).containsExactly("2026-11-01");
    }

    // P3, P6: a later request in the same month returns the month's outcome unchanged and applies nothing.
    @Test
    void replaysTheMonthWithTheSameBody() {
        int id = rows.insertDeposit("basic", 31, "1000.00");
        EntityExchangeResult<String> first = post();
        clock.set(NOW.plus(Duration.ofHours(1)));

        EntityExchangeResult<String> second = post();

        assertThat(second.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(second.getResponseBody()).isEqualTo(first.getResponseBody());
        assertThat(balanceOf(id)).isEqualTo("1000.83");
    }

    // P10: two requests of one month that overlap apply it once; the other gets 409 or the month's outcome.
    @Test
    void overlappingRequestsOfOneMonthApplyItOnce() throws Exception {
        int id = rows.insertDeposit("basic", 31, "1000.00");
        List<CompletableFuture<EntityExchangeResult<String>>> runs = new ArrayList<>();

        whileDepositsAreLocked(() -> {
            runs.add(postAsync());
            awaitSessionsWaitingForALock(1);
            runs.add(postAsync());
            assertThat(await(runs.get(1)).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        });

        assertThat(await(runs.get(0)).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(balanceOf(id)).isEqualTo("1000.83");
        assertThat(completedPeriods()).containsExactly("2026-11-01");
    }

    // P10: requests of two months that overlap (the clock crosses midnight between them) both wait on the deposit
    // locks and both apply their month; nothing is lost (ADR-0012, A; ADR-0013).
    @Test
    void overlappingRequestsOfTwoMonthsBothApply() throws Exception {
        int id = rows.insertDeposit("basic", 31, "1000.00");
        clock.set(Instant.parse("2026-10-31T23:59:59.999999Z"));
        List<CompletableFuture<EntityExchangeResult<String>>> runs = new ArrayList<>();

        whileDepositsAreLocked(() -> {
            runs.add(postAsync());
            awaitSessionsWaitingForALock(1);
            clock.set(Instant.parse("2026-11-01T00:00:00Z"));
            runs.add(postAsync());
            awaitSessionsWaitingForALock(2);
        });

        assertThat(await(runs.get(0)).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(await(runs.get(1)).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(balanceOf(id)).isEqualTo("1001.66");
        assertThat(completedPeriods()).containsExactly("2026-10-01", "2026-11-01");
    }

    // P11, S6: a balance the column cannot hold aborts the whole run: no balance changes, the month is released, and
    // a retry runs (and fails) again instead of replaying or getting 409.
    @Test
    void abortsTheWholeRunWhenOneBalanceExceedsTheColumn() {
        int small = rows.insertDeposit("basic", 31, "1000.00");
        rows.insertDeposit("basic", 31, "9999999999999.99");

        for (int attempt = 1; attempt <= 2; attempt++) {
            EntityExchangeResult<String> result = post();
            assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }

        assertThat(balanceOf(small)).isEqualTo("1000.00");
        assertThat(jdbc.sql("select count(*) from \"balanceUpdatePeriods\"").query(Integer.class).single()).isZero();
    }

    // P14: while the month's run waits for the deposits, a duplicate gets 409 at once instead of waiting behind it;
    // its retry after the run replays the outcome, and interest is applied once.
    @Test
    void aDuplicateGetsConflictWithoutWaitingAndThenTheReplay() throws Exception {
        int id = rows.insertDeposit("basic", 31, "1000.00");
        List<CompletableFuture<EntityExchangeResult<String>>> original = new ArrayList<>();

        whileDepositsAreLocked(() -> {
            original.add(postAsync());
            awaitSessionsWaitingForALock(1);
            EntityExchangeResult<String> duplicate = await(postAsync());
            assertThat(duplicate.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicate.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        });

        EntityExchangeResult<String> applied = await(original.getFirst());
        assertThat(applied.getStatus()).isEqualTo(HttpStatus.OK);
        EntityExchangeResult<String> replayed = post();
        assertThat(replayed.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getResponseBody()).isEqualTo(applied.getResponseBody());
        assertThat(balanceOf(id)).isEqualTo("1000.83");
    }
}
