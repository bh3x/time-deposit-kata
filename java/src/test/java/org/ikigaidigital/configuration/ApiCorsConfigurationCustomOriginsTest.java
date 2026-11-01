package org.ikigaidigital.configuration;

import org.ikigaidigital.timedeposit.application.port.in.ApplyMonthlyInterestUseCase;
import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;

/** A configured {@code app.cors.allowed-origins} list replaces the default (ADR-0010). */
@WebMvcTest(properties = "app.cors.allowed-origins=http://a.test,http://b.test")
@Import(ApiCorsConfiguration.class)
class ApiCorsConfigurationCustomOriginsTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ListTimeDepositsUseCase listTimeDeposits;

    @MockitoBean
    private ApplyMonthlyInterestUseCase applyMonthlyInterest;

    @ParameterizedTest(name = "allows configured origin {0}")
    @ValueSource(strings = {"http://a.test", "http://b.test"})
    void allowsEveryConfiguredOrigin(String origin) {
        assertThat(mvc.options().uri("/api/v1/balance-updates")
                .header(ORIGIN, origin)
                .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .hasStatusOk()
                .hasHeader(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    }

    @Test
    void replacesTheDefaultOrigin() {
        assertThat(mvc.options().uri("/api/v1/balance-updates")
                .header(ORIGIN, "http://localhost:8081")
                .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
