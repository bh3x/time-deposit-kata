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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/balance-updates} end to end (docs/testing.md P1, P10, P11, P14, S6): HTTP, controller, use case,
 * persistence and PostgreSQL, with real commits and a fixed clock. Overlaps are forced, not hoped for: the test holds
 * the deposit rows locked in its own JDBC transaction, starts the requests, waits until PostgreSQL reports them
 * waiting for a lock (pg_locks, polled with a deadline — no fixed sleep), then releases. The interest rules themselves
 * are pinned at the calculator; one sample travels the whole way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({PostgresContainerConfiguration.class, ApplyMonthlyInterestEndToEndTest.FixedClock.class})
class ApplyMonthlyInterestEndToEndTest {

    private static final String BALANCE_UPDATES = "/api/v1/balance-updates";
    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final Duration DEADLINE = Duration.ofSeconds(10);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;
    private TimeDepositRows rows;
    private final ExecutorService requests = Executors.newFixedThreadPool(2);

    @BeforeEach
    void truncateTables() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate \"withdrawals\", \"timeDeposits\", \"idempotencyKeys\" restart identity").update();
        rows = new TimeDepositRows(jdbc);
    }

    @AfterEach
    void stopRequests() {
        requests.shutdownNow();
    }

    private EntityExchangeResult<String> post(String idempotencyKey) {
        RestTestClient.RequestBodySpec request = client.post().uri(BALANCE_UPDATES);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange().expectBody(String.class).returnResult();
    }

    private CompletableFuture<EntityExchangeResult<String>> postAsync(String idempotencyKey) {
        return CompletableFuture.supplyAsync(() -> post(idempotencyKey), requests);
    }

    private String balanceOf(int id) {
        return jdbc.sql("select \"balance\"::text from \"timeDeposits\" where \"id\" = ?").param(id)
                .query(String.class).single();
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
    }

    // P10: two runs that overlap are serialised by the row locks; both months of interest are kept.
    @Test
    void overlappingRunsKeepBothMonthsOfInterest() throws Exception {
        int id = rows.insertDeposit("basic", 31, "1000.00");
        List<CompletableFuture<EntityExchangeResult<String>>> runs = new ArrayList<>();

        whileDepositsAreLocked(() -> {
            runs.add(postAsync(null));
            runs.add(postAsync(null));
            awaitSessionsWaitingForALock(2);
        });

        assertThat(runs.get(0).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(runs.get(1).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(balanceOf(id)).isEqualTo("1001.66");
    }

    // P10: a keyed and an unkeyed run serialise the same way.
    @Test
    void overlappingKeyedAndUnkeyedRunsKeepBothMonthsOfInterest() throws Exception {
        int id = rows.insertDeposit("basic", 31, "1000.00");
        List<CompletableFuture<EntityExchangeResult<String>>> runs = new ArrayList<>();

        whileDepositsAreLocked(() -> {
            runs.add(postAsync("k"));
            runs.add(postAsync(null));
            awaitSessionsWaitingForALock(2);
        });

        assertThat(runs.get(0).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(runs.get(1).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(balanceOf(id)).isEqualTo("1001.66");
        assertThat(jdbc.sql("select \"status\" from \"idempotencyKeys\" where \"idempotencyKey\" = 'k'")
                .query(String.class).single()).isEqualTo("COMPLETED");
    }

    // P11, S6: a balance the column cannot hold aborts the whole run: no balance changes, no key is kept, and a retry
    // with the same key runs again (and fails again) instead of getting 409.
    @Test
    void abortsTheWholeRunWhenOneBalanceExceedsTheColumn() {
        int small = rows.insertDeposit("basic", 31, "1000.00");
        rows.insertDeposit("basic", 31, "9999999999999.99");

        for (int attempt = 1; attempt <= 2; attempt++) {
            EntityExchangeResult<String> result = post("k");
            assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }

        assertThat(balanceOf(small)).isEqualTo("1000.00");
        assertThat(jdbc.sql("select count(*) from \"idempotencyKeys\"").query(Integer.class).single()).isZero();
    }

    // P14: while a keyed run waits for the deposits, a duplicate gets 409 at once instead of waiting behind it; its
    // retry after the run replays the outcome, and interest is applied once.
    @Test
    void aDuplicateGetsConflictWithoutWaitingAndThenTheReplay() throws Exception {
        int id = rows.insertDeposit("basic", 31, "1000.00");
        List<CompletableFuture<EntityExchangeResult<String>>> original = new ArrayList<>();

        whileDepositsAreLocked(() -> {
            original.add(postAsync("k"));
            awaitSessionsWaitingForALock(1);
            EntityExchangeResult<String> duplicate = postAsync("k").get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
            assertThat(duplicate.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicate.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        });

        EntityExchangeResult<String> applied = original.getFirst().get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertThat(applied.getStatus()).isEqualTo(HttpStatus.OK);
        EntityExchangeResult<String> replayed = post("k");
        assertThat(replayed.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getResponseBody()).isEqualTo(applied.getResponseBody());
        assertThat(balanceOf(id)).isEqualTo("1000.83");
    }
}
