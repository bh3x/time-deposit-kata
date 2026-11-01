package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.TimeDepositRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The locks ADR-0012 relies on, across two connections: a holder connection takes a lock and keeps it while the
 * adapter runs in its own transaction (scenarios P9, P15 and the two items ADR-0012 marks unverified). A missing
 * SKIP LOCKED or an unexpected wait fails fast with lock_timeout (55P03) instead of hanging; no Thread.sleep.
 * Data is committed, so each test cleans up after itself.
 */
@DataJpaTest
@Import({PostgresContainerConfiguration.class, TimeDepositPersistenceAdapter.class,
        BalanceUpdatePeriodPersistenceAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BalanceUpdateLockingTest {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final Instant NOW = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final String LOCK_NOVEMBER =
            "select 1 from \"balanceUpdatePeriods\" where \"period\" = date '2026-11-01' for update";

    @Autowired
    private TimeDepositPersistenceAdapter deposits;

    @Autowired
    private BalanceUpdatePeriodPersistenceAdapter periods;

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
        jdbc.sql("delete from \"withdrawals\"").update();
        jdbc.sql("delete from \"timeDeposits\"").update();
    }

    // Runs the action in an adapter transaction that gives up on any lock after two seconds.
    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.sql("set local lock_timeout = '2s'").update();
            return action.get();
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

    private void insertInProgress(YearMonth period, Instant createdAt) {
        jdbc.sql("""
                insert into "balanceUpdatePeriods" ("period", "status", "createdAt") values (?, 'IN_PROGRESS', ?)
                """).params(period.atDay(1), createdAt.atOffset(ZoneOffset.UTC)).update();
    }

    // P9: a month row locked by a running run is skipped, so the duplicate gets 409 instead of waiting.
    @Test
    void skipsAMonthLockedByARun() throws SQLException {
        insertInProgress(NOVEMBER, NOW);

        whileLockedBy(LOCK_NOVEMBER, () -> assertThat(inTransaction(() -> periods.findUnlocked(NOVEMBER))).isEmpty());
    }

    // ADR-0012, unverified until then: ON CONFLICT DO NOTHING does not wait for a row that is only locked.
    @Test
    void reservationDoesNotWaitForALockedOnlyRow() throws SQLException {
        insertInProgress(NOVEMBER, NOW);

        whileLockedBy(LOCK_NOVEMBER,
                () -> assertThat(inTransaction(() -> periods.reserve(NOVEMBER, NOW.plusSeconds(1)))).isFalse());
    }

    // P15: a locked month (e.g. a run of the previous month) never blocks reserving another month.
    @Test
    void reservingAnotherMonthDoesNotWaitForALockedMonth() throws SQLException {
        insertInProgress(OCTOBER, NOW.minusSeconds(3600));

        whileLockedBy("select 1 from \"balanceUpdatePeriods\" where \"period\" = date '2026-10-01' for update",
                () -> assertThat(inTransaction(() -> periods.reserve(NOVEMBER, NOW))).isTrue());
    }

    // S6: the JPA balance write and the JdbcClient period statements join the same Spring transaction, so the month's
    // switch and the balances commit or roll back together (ADR-0012, E; ADR-0013).
    @Test
    void balanceWritesAndPeriodStatementsRollBackTogether() {
        int id = new TimeDepositRows(jdbc).insertDeposit("basic", 31, "1000.00");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var deposit = deposits.loadAllForUpdate().getFirst();
            deposit.setBalance(1000.83);
            deposits.saveBalances(List.of(deposit));
            periods.reserve(NOVEMBER, NOW);
            status.setRollbackOnly();
        });

        assertThat(jdbc.sql("select \"balance\"::text from \"timeDeposits\" where \"id\" = ?").param(id)
                .query(String.class).single()).isEqualTo("1000.00");
        assertThat(jdbc.sql("select count(*) from \"balanceUpdatePeriods\"").query(Integer.class).single()).isZero();
    }

    // ADR-0012, A, unverified until here: Hibernate's PESSIMISTIC_WRITE on PostgreSQL locks the deposits
    // FOR NO KEY UPDATE, not FOR UPDATE (observed: a FOR KEY SHARE is not blocked). That serialises runs, since
    // FOR NO KEY UPDATE conflicts with itself, and does not block the FOR KEY SHARE of a new withdrawal's foreign-key
    // check; a future writer of balances still locks the deposit row first (ADR-0012, A), so nothing is lost. The
    // withdrawals themselves are not locked.
    @Test
    void lockingLoadBlocksOtherRunsButNotForeignKeyChecks() {
        TimeDepositRows rows = new TimeDepositRows(jdbc);
        int deposit = rows.insertDeposit("basic", 31, "10.00");
        rows.insertWithdrawal(deposit, "1.00", "2026-01-01");

        inTransaction(() -> {
            assertThat(deposits.loadAllForUpdate()).hasSize(1);
            try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
                other.setAutoCommit(false);
                assertThatThrownBy(() -> statement.execute("select 1 from \"timeDeposits\" for no key update nowait"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE);
                other.rollback();
                statement.execute("select 1 from \"timeDeposits\" for key share nowait");
                statement.execute("select 1 from \"withdrawals\" for update nowait");
                other.rollback();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }
}
