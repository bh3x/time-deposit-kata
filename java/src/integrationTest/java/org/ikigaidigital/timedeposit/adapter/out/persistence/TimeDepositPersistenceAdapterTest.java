package org.ikigaidigital.timedeposit.adapter.out.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

            TimeDepositView view = adapter.loadAll().getFirst();

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

        @Test
        void ordersDepositsByInternalKey() {
            rows.insertDeposit(uuid("ffffffffffff"), "basic", 31, "3.00");
            rows.insertDeposit(uuid("888888888888"), "basic", 31, "2.00");
            rows.insertDeposit(uuid("000000000000"), "basic", 31, "1.00");
            jdbc.sql("update \"timeDeposits\" set \"balance\" = 4.00 where \"publicId\" = ?")
                    .param(uuid("ffffffffffff")).update();

            assertThat(adapter.loadAll()).extracting(TimeDepositView::id)
                    .containsExactly(uuid("ffffffffffff"), uuid("888888888888"), uuid("000000000000"));
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
