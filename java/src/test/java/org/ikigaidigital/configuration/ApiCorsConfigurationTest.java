package org.ikigaidigital.configuration;

import org.ikigaidigital.timedeposit.application.port.in.ApplyMonthlyInterestUseCase;
import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;

/**
 * Cross-origin rules of the API for the Swagger UI origin (ADR-0010, docs/testing.md R3), with the default
 * {@code app.cors.allowed-origins} from {@code application.yml}. Browsers send request header names in lower case.
 */
@WebMvcTest
@Import(ApiCorsConfiguration.class)
class ApiCorsConfigurationTest {

    private static final String SWAGGER_UI = "http://localhost:8081";
    private static final String TIME_DEPOSITS = "/api/v1/time-deposits";
    private static final String BALANCE_UPDATES = "/api/v1/balance-updates";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ListTimeDepositsUseCase listTimeDeposits;

    @MockitoBean
    private ApplyMonthlyInterestUseCase applyMonthlyInterest;

    @BeforeEach
    void noDeposits() {
        given(listTimeDeposits.listAll()).willReturn(List.of());
    }

    @Nested
    @DisplayName("preflight requests")
    class Preflight {

        @Test
        void answersPreflightForPostWithContentTypeFromSwaggerUiOrigin() {
            MvcTestResult result = mvc.options().uri(BALANCE_UPDATES)
                    .header(ORIGIN, SWAGGER_UI)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
                    .exchange();

            assertThat(result).hasStatusOk().hasHeader(ACCESS_CONTROL_ALLOW_ORIGIN, SWAGGER_UI);
            assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_METHODS)).contains("POST");
            assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_HEADERS))
                    .containsIgnoringCase("content-type");
        }

        // ADR-0013 removed the Idempotency-Key from the contract and from the allowed request headers.
        @Test
        void rejectsPreflightRequestingTheRemovedIdempotencyKey() {
            assertThat(mvc.options().uri(BALANCE_UPDATES)
                    .header(ORIGIN, SWAGGER_UI)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        // A preflight is answered by the framework (ADR-0005) and never runs the operation.
        @Test
        void preflightDoesNotInvokeTheOperation() {
            assertThat(mvc.options().uri(TIME_DEPOSITS)
                    .header(ORIGIN, SWAGGER_UI)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .hasStatusOk();
            assertThat(mvc.options().uri(BALANCE_UPDATES)
                    .header(ORIGIN, SWAGGER_UI)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .hasStatusOk();

            verifyNoInteractions(listTimeDeposits);
        }

        // Origins match exactly: scheme, host and port.
        @ParameterizedTest(name = "rejects origin {0}")
        @ValueSource(strings = {"http://localhost:8082", "https://localhost:8081", "http://127.0.0.1:8081", "null"})
        void rejectsPreflightFromAnotherOrigin(String origin) {
            assertThat(mvc.options().uri(BALANCE_UPDATES)
                    .header(ORIGIN, origin)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        @Test
        void rejectsPreflightRequestingOnlyAHeaderThatIsNotAllowed() {
            assertThat(mvc.options().uri(BALANCE_UPDATES)
                    .header(ORIGIN, SWAGGER_UI)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(ACCESS_CONTROL_REQUEST_HEADERS, "x-debug"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        // Spring answers a mixed request with only the allowed headers; the browser then refuses to send x-debug.
        @Test
        void allowsOnlyTheAllowedHeadersOfAMixedPreflight() {
            MvcTestResult result = mvc.options().uri(BALANCE_UPDATES)
                    .header(ORIGIN, SWAGGER_UI)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(ACCESS_CONTROL_REQUEST_HEADERS, "content-type, x-debug")
                    .exchange();

            assertThat(result).hasStatusOk();
            assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_HEADERS))
                    .containsIgnoringCase("content-type")
                    .doesNotContainIgnoringCase("x-debug");
        }

        @Test
        void rejectsPreflightForAMethodThatIsNotAllowed() {
            assertThat(mvc.options().uri(TIME_DEPOSITS)
                    .header(ORIGIN, SWAGGER_UI)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
        }
    }

    @Nested
    @DisplayName("actual requests")
    class ActualRequests {

        @Test
        void allowsGetFromSwaggerUiOrigin() {
            assertThat(mvc.get().uri(TIME_DEPOSITS).header(ORIGIN, SWAGGER_UI))
                    .hasStatusOk()
                    .hasHeader(ACCESS_CONTROL_ALLOW_ORIGIN, SWAGGER_UI)
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS)
                    .doesNotContainHeader(ACCESS_CONTROL_EXPOSE_HEADERS);
        }

        @Test
        void allowsHeadFromSwaggerUiOrigin() {
            assertThat(mvc.head().uri(TIME_DEPOSITS).header(ORIGIN, SWAGGER_UI))
                    .hasStatusOk()
                    .hasHeader(ACCESS_CONTROL_ALLOW_ORIGIN, SWAGGER_UI);
        }

        // Without this header the browser hides the error from Swagger UI ("Failed to fetch").
        @Test
        void addsAllowOriginToTheProblemResponseOfPost() {
            given(applyMonthlyInterest.applyMonthlyInterest()).willThrow(new IllegalStateException("run failed"));

            assertThat(mvc.post().uri(BALANCE_UPDATES)
                    .header(ORIGIN, SWAGGER_UI))
                    .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .hasHeader(ACCESS_CONTROL_ALLOW_ORIGIN, SWAGGER_UI)
                    .doesNotContainHeader(ACCESS_CONTROL_EXPOSE_HEADERS);
        }

        @Test
        void rejectsRequestFromAnotherOrigin() {
            assertThat(mvc.get().uri(TIME_DEPOSITS).header(ORIGIN, "http://localhost:8082"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);

            verifyNoInteractions(listTimeDeposits);
        }
    }

    @Nested
    @DisplayName("non-cross-origin requests")
    class NonCrossOrigin {

        @Test
        void leavesRequestsWithoutOriginUnchanged() {
            assertThat(mvc.get().uri(TIME_DEPOSITS))
                    .hasStatusOk()
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
            assertThat(mvc.post().uri(BALANCE_UPDATES))
                    .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        // MockMvc serves http://localhost:80; an Origin equal to the request's own is not cross-origin.
        @Test
        void treatsSameOriginRequestAsNonCors() {
            assertThat(mvc.get().uri(TIME_DEPOSITS).header(ORIGIN, "http://localhost"))
                    .hasStatusOk()
                    .doesNotContainHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
        }
    }
}
