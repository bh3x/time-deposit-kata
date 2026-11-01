package org.ikigaidigital;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

import javax.sql.DataSource;

import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;

/**
 * Cross-origin calls from Swagger UI to {@code POST /balance-updates}, through the real server with the default
 * allowed origin (docs/testing.md R3, ADR-0010). Without a custom request header a bodiless POST is a CORS simple
 * request and needs no preflight, so the actual request is checked as well. The individual CORS rules are covered
 * by the web slice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(PostgresContainerConfiguration.class)
class CrossOriginEndToEndTest {

    private static final String SWAGGER_UI = "http://localhost:8081";
    private static final String BALANCE_UPDATES = "/api/v1/balance-updates";

    @Autowired
    private RestTestClient client;

    @Autowired
    private DataSource dataSource;

    // The actual POST applies the current month, so each test starts without deposits or months.
    @BeforeEach
    void truncateTables() {
        JdbcClient.create(dataSource)
                .sql("truncate \"withdrawals\", \"timeDeposits\", \"balanceUpdatePeriods\" restart identity").update();
    }

    @Test
    void answersPreflightForPostWithContentTypeFromTheSwaggerUiOrigin() {
        client.method(HttpMethod.OPTIONS).uri(BALANCE_UPDATES)
                .header(ORIGIN, SWAGGER_UI)
                .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ACCESS_CONTROL_ALLOW_ORIGIN, SWAGGER_UI);
    }

    @Test
    void addsAllowOriginToAnActualCrossOriginPost() {
        client.post().uri(BALANCE_UPDATES)
                .header(ORIGIN, SWAGGER_UI)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ACCESS_CONTROL_ALLOW_ORIGIN, SWAGGER_UI);
    }
}
