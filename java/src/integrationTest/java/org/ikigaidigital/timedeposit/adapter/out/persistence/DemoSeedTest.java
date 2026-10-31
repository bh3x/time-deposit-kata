package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario S7: the demo seed is applied only with the demo profile and covers the cases ADR-0006 lists. Only presence
 * is checked here; the interest each case earns is pinned by the characterization tests.
 */
@DataJpaTest
@ActiveProfiles("demo")
@Import(PostgresContainerConfiguration.class)
class DemoSeedTest {

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "basic at 30 days     | \"planType\" = 'basic'   and \"days\" = 30",
            "basic at 31 days     | \"planType\" = 'basic'   and \"days\" = 31",
            "student at 30 days   | \"planType\" = 'student' and \"days\" = 30",
            "student at 31 days   | \"planType\" = 'student' and \"days\" = 31",
            "student at 365 days  | \"planType\" = 'student' and \"days\" = 365",
            "student at 366 days  | \"planType\" = 'student' and \"days\" = 366",
            "premium at 45 days   | \"planType\" = 'premium' and \"days\" = 45",
            "premium at 46 days   | \"planType\" = 'premium' and \"days\" = 46",
            "days 0               | \"days\" = 0",
            "unknown plan type    | lower(\"planType\") not in ('basic', 'student', 'premium') and \"days\" > 30",
            "wrong case 'Basic'   | \"planType\" = 'Basic' and \"days\" > 30",
            "zero balance         | \"balance\" = 0",
            "large balance        | \"balance\" = 1000000000.00",
            "half-cent edge 6.00  | \"planType\" = 'basic' and \"days\" > 30 and \"balance\" = 6.00",
            "half-cent edge 18.00 | \"planType\" = 'basic' and \"days\" > 30 and \"balance\" = 18.00",
            "several withdrawals  | (select count(*) from \"withdrawals\" w where w.\"timeDepositId\" = d.\"id\") >= 2",
            "one withdrawal       | (select count(*) from \"withdrawals\" w where w.\"timeDepositId\" = d.\"id\") = 1",
            "no withdrawals       | not exists (select 1 from \"withdrawals\" w where w.\"timeDepositId\" = d.\"id\")"})
    void coversEveryCaseListedInAdr0006(String description, String condition) {
        Integer matches = jdbc.sql("select count(*) from \"timeDeposits\" d where " + condition)
                .query(Integer.class).single();

        assertThat(matches).as(description).isPositive();
    }

    // Exact counts and per-deposit withdrawals: a typo in a withdrawal's deposit id would silently drop the row.
    @Test
    void containsExactlyTheListedRows() {
        assertThat(count("timeDeposits")).isEqualTo(15);
        assertThat(count("withdrawals")).isEqualTo(6);
        assertThat(withdrawalsOf("00000000-0000-4000-8000-000000000005")).isEqualTo(3);
        assertThat(withdrawalsOf("00000000-0000-4000-8000-000000000006")).isEqualTo(1);
        assertThat(withdrawalsOf("00000000-0000-4000-8000-000000000013")).isEqualTo(2);
    }

    @Test
    void isAppliedAsARepeatableMigrationFromItsOwnLocation() {
        Boolean succeeded = jdbc.sql("""
                        select success from flyway_schema_history
                        where version is null and script = 'R__demo_seed.sql'
                        """)
                .query(Boolean.class).optional().orElse(false);

        assertThat(succeeded).isTrue();
        assertThat(new ClassPathResource("db/migration/R__demo_seed.sql").exists()).isFalse();
    }

    // Repeatable migrations re-run when their checksum changes, so the seed must not duplicate rows.
    @Test
    void canBeAppliedAgainWithoutDuplicates() throws Exception {
        int deposits = count("timeDeposits");
        int withdrawals = count("withdrawals");

        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/seed/R__demo_seed.sql"));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }

        assertThat(count("timeDeposits")).isEqualTo(deposits);
        assertThat(count("withdrawals")).isEqualTo(withdrawals);
    }

    private int withdrawalsOf(String depositPublicId) {
        return jdbc.sql("""
                        select count(*) from "withdrawals" w join "timeDeposits" d on d."id" = w."timeDepositId"
                        where d."publicId" = ?::uuid
                        """)
                .param(depositPublicId).query(Integer.class).single();
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from \"" + table + "\"").query(Integer.class).single();
    }
}
