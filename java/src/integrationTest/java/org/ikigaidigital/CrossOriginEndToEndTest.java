package org.ikigaidigital;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;

/**
 * The preflight Swagger UI sends before {@code POST /balance-updates}, through the real server with the default
 * allowed origin (docs/testing.md R3, ADR-0010). The individual CORS rules are covered by the web slice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(PostgresContainerConfiguration.class)
class CrossOriginEndToEndTest {

    private static final String SWAGGER_UI = "http://localhost:8081";

    @Autowired
    private RestTestClient client;

    @Test
    void answersPreflightForPostWithIdempotencyKeyFromTheSwaggerUiOrigin() {
        client.method(HttpMethod.OPTIONS).uri("/api/v1/balance-updates")
                .header(ORIGIN, SWAGGER_UI)
                .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ACCESS_CONTROL_ALLOW_ORIGIN, SWAGGER_UI);
    }
}
