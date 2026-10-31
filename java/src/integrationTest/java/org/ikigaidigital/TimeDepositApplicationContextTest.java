package org.ikigaidigital;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application context starts from {@link TimeDepositApplication} against PostgreSQL, and the list use case
 * is wired through to the database (ADR-0008's context test; ADR-0009). It commits for real, so tables are truncated
 * before each test. The REST step adds the end-to-end tests over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfiguration.class)
class TimeDepositApplicationContextTest {

    private static final UUID PUBLIC_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private ListTimeDepositsUseCase listTimeDeposits;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @BeforeEach
    void truncateTables() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate \"withdrawals\", \"timeDeposits\", \"idempotencyKeys\" restart identity").update();
    }

    // No test transaction here: the withdrawals must already be loaded when the use case returns (no lazy loading).
    @Test
    void listsTimeDepositsWithWithdrawalsFromTheDatabaseThroughTheUseCase() {
        Integer id = jdbc.sql("""
                        insert into "timeDeposits" ("publicId", "planType", "days", "balance")
                        values (?, ?, ?, ?) returning "id"
                        """)
                .params(PUBLIC_ID, "basic", 400, new BigDecimal("1000.00"))
                .query(Integer.class).single();
        jdbc.sql("insert into \"withdrawals\" (\"timeDepositId\", \"amount\", \"date\") values (?, ?, ?)")
                .params(id, new BigDecimal("100.00"), LocalDate.parse("2026-06-01"))
                .update();

        TimeDepositView view = listTimeDeposits.listAll().getFirst();

        assertThat(view.id()).isEqualTo(PUBLIC_ID);
        assertThat(view.withdrawals()).singleElement()
                .satisfies(w -> assertThat(w.amount()).isEqualByComparingTo("100.00"));
    }
}
