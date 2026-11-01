package org.ikigaidigital.timedeposit;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

import javax.sql.DataSource;

import static org.ikigaidigital.timedeposit.TimeDepositRows.uuid;

/**
 * {@code GET /api/v1/time-deposits} end to end (docs/testing.md G1): HTTP, controller, use case, persistence adapter
 * and PostgreSQL. It commits for real, so tables are truncated before each test. The JSON shape and the money edges
 * are owned by the web slice and the unit tests; here one realistic data set travels the whole way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(PostgresContainerConfiguration.class)
class ListTimeDepositsEndToEndTest {

    @Autowired
    private RestTestClient client;

    @Autowired
    private DataSource dataSource;

    private TimeDepositRows rows;

    @BeforeEach
    void truncateTables() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate \"withdrawals\", \"timeDeposits\", \"idempotencyKeys\" restart identity").update();
        rows = new TimeDepositRows(jdbc);
    }

    @Test
    void listsEveryStoredDepositWithItsWithdrawals() {
        int basic = rows.insertDeposit(uuid(1), "basic", 400, "1000.93");
        rows.insertWithdrawal(uuid(11), basic, "100.00", "2026-06-01");
        rows.insertWithdrawal(uuid(12), basic, "50.00", "2026-07-01");
        rows.insertDeposit(uuid(2), "gold", 0, "0.00");

        // Lenient: the order of deposits is not part of the contract (ADR-0004).
        client.get().uri("/api/v1/time-deposits")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().json("""
                        [{"id": "00000000-0000-4000-8000-000000000002", "planType": "gold", "balance": "0.00",
                          "days": 0, "withdrawals": []},
                         {"id": "00000000-0000-4000-8000-000000000001", "planType": "basic", "balance": "1000.93",
                          "days": 400,
                          "withdrawals": [{"id": "00000000-0000-4000-8000-000000000011", "amount": "100.00",
                                           "date": "2026-06-01"},
                                          {"id": "00000000-0000-4000-8000-000000000012", "amount": "50.00",
                                           "date": "2026-07-01"}]}]
                        """, JsonCompareMode.LENIENT);
    }
}
