package org.ikigaidigital.timedeposit.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.TimeDepositRows;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read adapter fulfils {@code LoadTimeDepositViewsPort} against PostgreSQL (scenarios G1, G3, G4, G7 and the read
 * half of S5 in docs/testing.md). Rows are inserted with plain SQL, so the tests exercise the real mapping.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({PostgresContainerConfiguration.class, TimeDepositPersistenceAdapter.class})
class TimeDepositPersistenceAdapterTest {

    @Autowired
    private TimeDepositPersistenceAdapter adapter;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    private JdbcClient jdbc;
    private TimeDepositRows rows;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        rows = new TimeDepositRows(jdbc);
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("00000000-0000-4000-8000-" + suffix);
    }

    @Nested
    @DisplayName("G1/G7: maps every column into the view")
    class Mapping {

        @Test
        void returnsAnEmptyUnmodifiableListWhenNoDepositsExist() {
            List<TimeDepositView> views = adapter.loadAll();

            assertThat(views).isEmpty();
            assertThatThrownBy(() -> views.add(null)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void mapsEveryDepositColumnIntoTheView() {
            rows.insertDeposit(uuid("000000000001"), "premium", 46, "1234.56");

            List<TimeDepositView> views = adapter.loadAll();
            TimeDepositView view = views.getFirst();

            assertThatThrownBy(() -> views.add(view)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(view.id()).isEqualTo(uuid("000000000001"));
            assertThat(view.terms().getPlanType()).isEqualTo("premium");
            assertThat(view.terms().getBalance()).isEqualTo(1234.56);
            assertThat(view.terms().getDays()).isEqualTo(46);
        }

        // S5, read half: NUMERIC(15,2) becomes the nearest double. At the column maximum one unit in the last place
        // is 2^-9, so 9,999,999,999,999.99 reads as 9,999,999,999,999.990234375 — never 10^13.
        @ParameterizedTest(name = "balance {0}")
        @ValueSource(strings = {"0.00", "0.01", "1000.93", "1000000000.00", "9999999999999.99"})
        void readsBalancesAsTheNearestDouble(String balance) {
            rows.insertDeposit("basic", 31, balance);

            assertThat(adapter.loadAll().getFirst().terms().getBalance()).isEqualTo(Double.parseDouble(balance));
        }

        // Plan types are free text (no CHECK, ADR-0006) and days are passed through without overflow.
        @ParameterizedTest(name = "plan \"{0}\", days {1}")
        @CsvSource({"gold, 0", "Basic, 31", "' basic ', 2147483647"})
        void returnsPlanTypesAndDaysUnchanged(String planType, int days) {
            rows.insertDeposit(planType, days, "100.00");

            TimeDepositView view = adapter.loadAll().getFirst();

            assertThat(view.terms().getPlanType()).isEqualTo(planType);
            assertThat(view.terms().getDays()).isEqualTo(days);
        }

        @Test
        void mapsEveryWithdrawalColumn() {
            int deposit = rows.insertDeposit("basic", 800, "100.00");
            rows.insertWithdrawal(uuid("000000000101"), deposit, "9999999999999.99", "2028-02-29");
            rows.insertWithdrawal(uuid("000000000102"), deposit, "0.01", "2026-01-01");

            List<WithdrawalView> withdrawals = adapter.loadAll().getFirst().withdrawals();

            assertThat(withdrawals).hasSize(2);
            assertThat(withdrawals).anySatisfy(w -> {
                assertThat(w.id()).isEqualTo(uuid("000000000101"));
                assertThat(w.amount()).isEqualByComparingTo("9999999999999.99");
                assertThat(w.date()).isEqualTo(LocalDate.of(2028, 2, 29));
            });
            assertThat(withdrawals).anySatisfy(w -> {
                assertThat(w.id()).isEqualTo(uuid("000000000102"));
                assertThat(w.amount()).isEqualByComparingTo("0.01");
                assertThat(w.date()).isEqualTo(LocalDate.of(2026, 1, 1));
            });
        }
    }

    @Nested
    @DisplayName("G3: withdrawals belong to their deposit, which is listed even without any")
    class Withdrawals {

        @Test
        void returnsAnEmptyWithdrawalListForADepositWithout() {
            int one = rows.insertDeposit(uuid("000000000001"), "basic", 31, "1.00");
            rows.insertDeposit(uuid("000000000002"), "basic", 31, "1.00");
            int three = rows.insertDeposit(uuid("000000000003"), "basic", 31, "1.00");
            rows.insertWithdrawal(one, "1.00", "2026-01-01");
            for (int i = 0; i < 3; i++) {
                rows.insertWithdrawal(three, "1.00", "2026-01-01");
            }

            List<TimeDepositView> views = adapter.loadAll();

            assertThat(views).hasSize(3);
            assertThat(views).filteredOn(v -> v.id().equals(uuid("000000000002")))
                    .singleElement()
                    .satisfies(v -> assertThat(v.withdrawals()).isNotNull().isEmpty());
        }

        // A fetch join returns one row per withdrawal; the result must still contain each deposit once.
        @Test
        void assignsEachWithdrawalOnlyToItsDepositOnce() {
            int first = rows.insertDeposit(uuid("000000000001"), "basic", 31, "1.00");
            int second = rows.insertDeposit(uuid("000000000002"), "basic", 31, "1.00");
            rows.insertWithdrawal(uuid("000000000101"), first, "1.00", "2026-01-01");
            rows.insertWithdrawal(uuid("000000000201"), second, "1.00", "2026-01-02");
            rows.insertWithdrawal(uuid("000000000102"), first, "1.00", "2026-01-03");
            rows.insertWithdrawal(uuid("000000000202"), second, "1.00", "2026-01-04");
            rows.insertWithdrawal(uuid("000000000103"), first, "1.00", "2026-01-05");

            List<TimeDepositView> views = adapter.loadAll();

            assertThat(views).extracting(TimeDepositView::id)
                    .containsExactlyInAnyOrder(uuid("000000000001"), uuid("000000000002"));
            assertThat(views).filteredOn(v -> v.id().equals(uuid("000000000001"))).singleElement()
                    .satisfies(v -> assertThat(v.withdrawals()).extracting(WithdrawalView::id)
                            .containsExactlyInAnyOrder(uuid("000000000101"), uuid("000000000102"),
                                    uuid("000000000103")));
            assertThat(views).filteredOn(v -> v.id().equals(uuid("000000000002"))).singleElement()
                    .satisfies(v -> assertThat(v.withdrawals()).extracting(WithdrawalView::id)
                            .containsExactlyInAnyOrder(uuid("000000000201"), uuid("000000000202")));
        }
    }

    // G4 is the one place where order is the requirement (ADR-0004, ADR-0009). An UPDATE moves a row to the end of
    // the PostgreSQL heap, so a query without ORDER BY would return it last; public ids are chosen in reverse order.
    @Nested
    @DisplayName("G4: deterministic order")
    class Ordering {

        // Eight deposits; every second one is updated, which moves it to the end of the heap, and two of them get
        // withdrawals, so neither heap order nor join order matches the internal key by accident.
        @Test
        void ordersDepositsByInternalKey() {
            List<UUID> expected = new ArrayList<>();
            for (int i = 8; i >= 1; i--) {
                UUID publicId = uuid("%012d".formatted(i));
                int id = rows.insertDeposit(publicId, "basic", 31, "1.00");
                expected.add(publicId);
                if (i % 3 == 0) {
                    rows.insertWithdrawal(id, "0.50", "2026-01-01");
                }
            }
            for (int i = 8; i >= 1; i -= 2) {
                jdbc.sql("update \"timeDeposits\" set \"balance\" = 2.00 where \"publicId\" = ?")
                        .param(uuid("%012d".formatted(i))).update();
            }

            assertThat(adapter.loadAll()).extracting(TimeDepositView::id).containsExactlyElementsOf(expected);
        }

        @Test
        void ordersWithdrawalsByDateThenInternalKey() {
            int deposit = rows.insertDeposit("basic", 800, "100.00");
            rows.insertWithdrawal(uuid("000000000904"), deposit, "1.00", "2026-03-01");
            rows.insertWithdrawal(uuid("000000000903"), deposit, "1.00", "2026-01-15");
            rows.insertWithdrawal(uuid("000000000902"), deposit, "1.00", "2026-03-01");
            rows.insertWithdrawal(uuid("000000000901"), deposit, "1.00", "2026-01-15");
            jdbc.sql("update \"withdrawals\" set \"amount\" = 2.00 where \"publicId\" = ?")
                    .param(uuid("000000000904")).update();

            assertThat(adapter.loadAll().getFirst().withdrawals()).extracting(WithdrawalView::id)
                    .containsExactly(uuid("000000000903"), uuid("000000000901"), uuid("000000000904"),
                            uuid("000000000902"));
        }
    }

    // Any other load path (here: find plus lazy loading, as a later write adapter might) keeps the withdrawal order.
    @Test
    void ordersWithdrawalsWhenLoadedOutsideTheListQuery() {
        int deposit = rows.insertDeposit("basic", 800, "100.00");
        rows.insertWithdrawal(uuid("000000000904"), deposit, "1.00", "2026-03-01");
        rows.insertWithdrawal(uuid("000000000903"), deposit, "1.00", "2026-01-15");
        rows.insertWithdrawal(uuid("000000000902"), deposit, "1.00", "2026-03-01");
        jdbc.sql("update \"withdrawals\" set \"amount\" = 2.00 where \"publicId\" = ?")
                .param(uuid("000000000904")).update();

        TimeDepositEntity entity = entityManager.find(TimeDepositEntity.class, deposit);

        assertThat(entity.getWithdrawals()).extracting(WithdrawalEntity::getPublicId)
                .containsExactly(uuid("000000000903"), uuid("000000000904"), uuid("000000000902"));
    }

    @Nested
    @DisplayName("balance update: locking load and cent-precise write (P4, S5, ADR-0012)")
    class BalanceUpdates {

        // Updating the first row moves it to the end of the heap, so without ORDER BY it would come back last; the
        // order matters because every run must lock the rows in the same order (ADR-0012, A).
        @Test
        void loadsEveryDepositOrderedByInternalKey() {
            int first = rows.insertDeposit("premium", 46, "20.00");
            int second = rows.insertDeposit("basic", 31, "10.00");
            jdbc.sql("update \"timeDeposits\" set \"balance\" = 21.00 where \"id\" = ?").param(first).update();

            List<TimeDeposit> deposits = adapter.loadAllForUpdate();

            assertThat(deposits).extracting(TimeDeposit::getId).containsExactly(first, second);
            assertThat(deposits.getFirst()).satisfies(d -> {
                assertThat(d.getPlanType()).isEqualTo("premium");
                assertThat(d.getDays()).isEqualTo(46);
                assertThat(d.getBalance()).isEqualTo(21.00);
            });
        }

        // S5, write half: the double sum keeps binary noise (known issue #2), above the cent (1000.9300000000001) or
        // below it (0.7999999999999999); the column stores the exact cent sum either way.
        @ParameterizedTest(name = "{0} + {1} stored as {2}")
        @CsvSource({"1000.10, 0.83, 1000.93", "0.70, 0.10, 0.80"})
        void writesBalancesAtCentPrecision(String balance, double interest, String stored) {
            int id = rows.insertDeposit("basic", 31, balance);
            TimeDeposit deposit = adapter.loadAllForUpdate().getFirst();
            deposit.setBalance(deposit.getBalance() + interest);

            adapter.saveBalances(List.of(deposit));

            assertThat(storedBalance(id)).isEqualTo(stored);
            assertThat(adapter.loadAllForUpdate().getFirst().getBalance()).isEqualTo(Double.parseDouble(stored));
        }

        // P4: a balance update writes nothing but the balance.
        @Test
        void keepsEverythingButTheBalance() {
            int id = rows.insertDeposit(uuid("000000000001"), "Basic", 31, "10.00");
            rows.insertWithdrawal(uuid("000000000901"), id, "1.00", "2026-01-01");
            TimeDeposit deposit = adapter.loadAllForUpdate().getFirst();
            deposit.setBalance(11.00);

            adapter.saveBalances(List.of(deposit));

            assertThat(jdbc.sql("""
                    select "publicId"::text || '|' || "planType" || '|' || "days" || '|' || "balance"
                    from "timeDeposits" where "id" = ?""").param(id).query(String.class).single())
                    .isEqualTo(uuid("000000000001") + "|Basic|31|11.00");
            assertThat(jdbc.sql("select count(*) from \"withdrawals\" where \"timeDepositId\" = ? and \"amount\" = 1.00")
                    .param(id).query(Integer.class).single()).isEqualTo(1);
        }

        @Test
        void acceptsTheColumnMaximum() {
            int id = rows.insertDeposit("basic", 31, "1.00");
            TimeDeposit deposit = adapter.loadAllForUpdate().getFirst();
            deposit.setBalance(9_999_999_999_999.99);

            adapter.saveBalances(List.of(deposit));

            assertThat(storedBalance(id)).isEqualTo("9999999999999.99");
        }

        // ADR-0012, E: the column is the guard for balances of 10^13 or more; the failure aborts the run.
        @Test
        void rejectsABalanceAboveTheColumnMaximum() {
            rows.insertDeposit("basic", 31, "1.00");
            TimeDeposit deposit = adapter.loadAllForUpdate().getFirst();
            deposit.setBalance(10_000_000_000_000.00);

            assertThatThrownBy(() -> adapter.saveBalances(List.of(deposit)))
                    .rootCause().isInstanceOf(SQLException.class)
                    .extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo("22003");
        }

        @Test
        void failsForADepositThatIsNotStored() {
            TimeDeposit unknown = new TimeDeposit(Integer.MAX_VALUE, "basic", 1.00, 31);

            assertThatIllegalStateException().isThrownBy(() -> adapter.saveBalances(List.of(unknown)));
        }

        private String storedBalance(int id) {
            return jdbc.sql("select \"balance\"::text from \"timeDeposits\" where \"id\" = ?").param(id)
                    .query(String.class).single();
        }
    }

    // The port promises a consistent snapshot; this adapter delivers it by reading everything in one statement.
    @Test
    void readsAllDepositsWithOneStatement() {
        for (int d = 1; d <= 3; d++) {
            int deposit = rows.insertDeposit("basic", 31, "1.00");
            rows.insertWithdrawal(deposit, "1.00", "2026-01-01");
            rows.insertWithdrawal(deposit, "1.00", "2026-01-02");
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<TimeDepositView> views = adapter.loadAll();

        assertThat(views).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }
}
