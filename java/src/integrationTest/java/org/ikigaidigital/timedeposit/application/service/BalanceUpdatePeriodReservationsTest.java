package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;
import org.ikigaidigital.timedeposit.application.service.BalanceUpdatePeriodReservations.Reservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reservation of ADR-0014 against PostgreSQL's real {@code SKIP LOCKED} (scenario P16), which the in-memory fake
 * of the application tests can only imitate: a holder connection keeps the month row locked while the reservation
 * runs in its own transaction. The persistence adapter is reached through its port; it is package-private, so it is
 * picked up by a component scan of its package rather than imported by class. Data is committed, so the test cleans
 * up after itself; a wait fails fast with lock_timeout instead of hanging.
 */
@DataJpaTest
@Import({PostgresContainerConfiguration.class, BalanceUpdatePeriodReservationsTest.PersistenceAdapters.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BalanceUpdatePeriodReservationsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final BalanceUpdateOutcome OUTCOME =
            new BalanceUpdateOutcome(3, Instant.parse("2026-11-01T08:00:00.654321Z"));
    private static final String LOCK_NOVEMBER =
            "select 1 from \"balanceUpdatePeriods\" where \"period\" = date '2026-11-01' for update";

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan("org.ikigaidigital.timedeposit.adapter.out.persistence")
    static class PersistenceAdapters {
    }

    @Autowired
    private RecordBalanceUpdatePort periods;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from \"balanceUpdatePeriods\"").update();
    }

    // Runs the reservation in its own transaction that gives up on any lock after two seconds.
    private Reservation reserveInTransaction() {
        BalanceUpdatePeriodReservations reservations =
                new BalanceUpdatePeriodReservations(periods, Clock.fixed(NOW, ZoneOffset.UTC));
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.sql("set local lock_timeout = '2s'").update();
            return reservations.reserve();
        });
    }

    // Holds the lock taken by the given statement on a second connection while the action runs, then rolls back.
    private void whileLockedBy(String lockingStatement, Runnable action) throws SQLException {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute(lockingStatement);
                action.run();
            } finally {
                holder.rollback();
            }
        }
    }

    // A completed month held locked by another transaction (e.g. a concurrent request of the month) is replayed,
    // not reported as in progress (ADR-0014).
    @Test
    void replaysACompletedMonthLockedByAnotherTransaction() throws SQLException {
        OffsetDateTime executedAt = OUTCOME.executedAt().atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                insert into "balanceUpdatePeriods"
                    ("period", "status", "processedCount", "executedAt", "createdAt", "completedAt")
                values (date '2026-11-01', 'COMPLETED', ?, ?, ?, ?)
                """).params(OUTCOME.processedCount(), executedAt, executedAt, executedAt).update();

        whileLockedBy(LOCK_NOVEMBER,
                () -> assertThat(reserveInTransaction()).isEqualTo(new Reservation.Replay(OUTCOME)));
    }
}
