package org.ikigaidigital;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.TimeDepositRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;

/**
 * The HTTP routes of the running application (docs/testing.md R1, R2, R4; ADR-0005): only the two contract operations
 * are served, with HEAD, OPTIONS and 405 derived from them; everything else, including Spring Boot's {@code /error}
 * and the static-resource handler's paths, is a 404 Problem Details. Through the real server, because those routes
 * exist only in the full context. The 405 body format is owned by the web slice; here status, content type and
 * {@code Allow} are checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(PostgresContainerConfiguration.class)
class RouteScopeEndToEndTest {

    private static final String TIME_DEPOSITS = "/api/v1/time-deposits";
    private static final String BALANCE_UPDATES = "/api/v1/balance-updates";

    @Autowired
    private RestTestClient client;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;
    private int deposit;

    @BeforeEach
    void seedOneDeposit() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate \"withdrawals\", \"timeDeposits\", \"idempotencyKeys\" restart identity").update();
        deposit = new TimeDepositRows(jdbc).insertDeposit("basic", 31, "1000.00");
    }

    private EntityExchangeResult<byte[]> exchange(HttpMethod method, String path, String... headerPairs) {
        RestTestClient.RequestBodySpec request = client.method(method).uri(path);
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        return request.exchange().expectBody().returnResult();
    }

    private static void assertNotFoundProblem(EntityExchangeResult<byte[]> result) {
        assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                .contains("\"status\":404").contains("\"title\":\"Not Found\"");
        assertThat(result.getResponseHeaders().get(ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    // Allow holds the declared method and nothing beyond the derived HEAD and OPTIONS (RFC 9110).
    private static void assertAllows(EntityExchangeResult<byte[]> result, HttpMethod declared) {
        Set<HttpMethod> allowed = result.getResponseHeaders().getAllow();
        assertThat(allowed).contains(declared).isSubsetOf(declared, HttpMethod.HEAD, HttpMethod.OPTIONS);
    }

    private void assertBalanceUnchanged() {
        assertThat(jdbc.sql("select \"balance\"::text from \"timeDeposits\" where \"id\" = ?").param(deposit)
                .query(String.class).single()).isEqualTo("1000.00");
    }

    // R1: no framework, documentation, monitoring or static route.
    @ParameterizedTest(name = "GET {0} -> 404")
    @ValueSource(strings = {"/no-such-path", "/error", "/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html",
            "/swagger-ui/index.html", "/actuator", "/actuator/health", "/index.html", "/favicon.ico", "/",
            "/openapi.yaml", "/openapi/openapi.yaml"})
    void answersUnknownPathsWithNotFoundProblem(String path) {
        assertNotFoundProblem(exchange(HttpMethod.GET, path));
    }

    // R1: only the exact contract paths match (no trailing slash, case-sensitive, base path required).
    @ParameterizedTest(name = "GET {0} -> 404")
    @ValueSource(strings = {"/api/v1/time-deposits/", "/api/v1/Time-Deposits", "/API/V1/time-deposits",
            "/api/v2/time-deposits", "/api/v1", "/api/v1/", "/api/v1/time-deposits/123", "/api/v1/time-deposits.json",
            "/time-deposits"})
    void answersNearMissesOfTheListPathWithNotFoundProblem(String path) {
        assertNotFoundProblem(exchange(HttpMethod.GET, path));
    }

    @Test
    void answersANearMissOfTheBalanceUpdatePathWithoutApplyingInterest() {
        assertNotFoundProblem(exchange(HttpMethod.POST, BALANCE_UPDATES + "/"));
        assertBalanceUnchanged();
    }

    @ParameterizedTest(name = "{0} /error -> 404")
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void answersTheErrorPathWithNotFoundForEveryMethod(String method) {
        assertNotFoundProblem(exchange(HttpMethod.valueOf(method), "/error"));
    }

    // A browser asking for HTML gets a 404, never Spring Boot's whitelabel page.
    @Test
    void answersABrowserWithNotFoundAndNoHtml() {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.GET, "/no-such-path", HttpHeaders.ACCEPT,
                MediaType.TEXT_HTML_VALUE);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getResponseHeaders().getContentType()).isNotEqualTo(MediaType.TEXT_HTML);
        assertThat(new String(result.getResponseBodyContent() == null ? new byte[0] : result.getResponseBodyContent(),
                StandardCharsets.UTF_8)).doesNotContainIgnoringCase("<html");
    }

    // R2
    @ParameterizedTest(name = "{0} " + TIME_DEPOSITS + " -> 405")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void rejectsAWrongMethodOnTheListPath(String method) {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.valueOf(method), TIME_DEPOSITS);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertAllows(result, HttpMethod.GET);
    }

    // R2
    @ParameterizedTest(name = "{0} " + BALANCE_UPDATES + " -> 405")
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE"})
    void rejectsAWrongMethodOnTheBalanceUpdatePathWithoutApplyingInterest(String method) {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.valueOf(method), BALANCE_UPDATES);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertAllows(result, HttpMethod.POST);
        assertBalanceUnchanged();
    }

    // R4: HEAD is derived from the GET mapping and has no body.
    @Test
    void answersHeadOnTheListPathWithoutABody() {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.HEAD, TIME_DEPOSITS);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.getResponseBodyContent()).isNullOrEmpty();
    }

    // R4: HEAD exists only for GET, so it never runs the balance update.
    @Test
    void rejectsHeadOnTheBalanceUpdatePathWithoutApplyingInterest() {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.HEAD, BALANCE_UPDATES);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertAllows(result, HttpMethod.POST);
        assertBalanceUnchanged();
    }

    // R4: OPTIONS is answered from the mappings, with Allow and without running the operation.
    @Test
    void answersOptionsOnTheListPathFromItsMapping() {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.OPTIONS, TIME_DEPOSITS);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertAllows(result, HttpMethod.GET);
    }

    @Test
    void answersOptionsOnTheBalanceUpdatePathWithoutApplyingInterest() {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.OPTIONS, BALANCE_UPDATES);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertAllows(result, HttpMethod.POST);
        assertBalanceUnchanged();
    }

    // R1/R4: without the static-resource handler, OPTIONS on an unknown path is not answered with 200.
    @ParameterizedTest(name = "OPTIONS {0} -> 404")
    @ValueSource(strings = {"/no-such-path", "/api/v1/unknown", "/", "/error"})
    void answersOptionsOnAnUnknownPathWithNotFound(String path) {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.OPTIONS, path);

        assertNotFoundProblem(result);
        assertThat(result.getResponseHeaders().getAllow()).isEmpty();
    }

    // R3 (negative): CORS adds no route, so a preflight for an unknown path is a plain 404 without an allow-origin,
    // even inside the /api/** CORS mapping (ADR-0010).
    @ParameterizedTest(name = "preflight for {0} refused")
    @ValueSource(strings = {"/api/v1/unknown", "/no-such-path"})
    void refusesAPreflightForAnUnknownPath(String path) {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.OPTIONS, path,
                ORIGIN, "http://localhost:8081",
                ACCESS_CONTROL_REQUEST_METHOD, "POST",
                ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key");

        assertNotFoundProblem(result);
    }
}
