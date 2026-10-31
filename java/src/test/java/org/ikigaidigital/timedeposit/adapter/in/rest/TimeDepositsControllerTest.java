package org.ikigaidigital.timedeposit.adapter.in.rest;

import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aDeposit;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aView;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aWithdrawal;
import static org.mockito.BDDMockito.given;

/**
 * Web slice for {@code GET /api/v1/time-deposits} (docs/testing.md G1–G3, G5–G7): HTTP mapping and the JSON
 * representation of the contract, with the use case mocked. Bodies are compared strictly, so a missing or extra field
 * (e.g. an internal key) fails the test.
 */
@WebMvcTest(TimeDepositsController.class)
class TimeDepositsControllerTest {

    private static final String TIME_DEPOSITS = "/api/v1/time-deposits";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ListTimeDepositsUseCase listTimeDeposits;

    @Nested
    @DisplayName("GET /time-deposits")
    class ListTimeDeposits {

        @Test
        void returnsAnEmptyArrayWhenNoDepositsExist() {
            given(listTimeDeposits.listAll()).willReturn(List.of());

            assertThat(mvc.get().uri(TIME_DEPOSITS))
                    .hasStatusOk()
                    .hasContentType(MediaType.APPLICATION_JSON)
                    .bodyJson().isStrictlyEqualTo("[]");
        }

        @Test
        void mapsEveryFieldOfADepositAndItsWithdrawals() {
            given(listTimeDeposits.listAll()).willReturn(List.of(
                    aView(1, aDeposit("premium", 25000.0, 120), aWithdrawal(2, "500.00", "2026-09-15"))));

            assertThat(mvc.get().uri(TIME_DEPOSITS))
                    .hasStatusOk()
                    .hasContentType(MediaType.APPLICATION_JSON)
                    .bodyJson().isStrictlyEqualTo("""
                            [{"id": "00000000-0000-4000-8000-000000000001", "planType": "premium",
                              "balance": "25000.00", "days": 120,
                              "withdrawals": [{"id": "00000000-0000-4000-8000-000000000002",
                                               "amount": "500.00", "date": "2026-09-15"}]}]
                            """);
        }

        // G6: the fixture's internal id is 1; the response carries the public UUID and nothing else as an id.
        @Test
        void exposesThePublicIdAndNoInternalKey() {
            given(listTimeDeposits.listAll()).willReturn(List.of(aView(7, aDeposit("basic", 1.0, 1))));

            assertThat(mvc.get().uri(TIME_DEPOSITS)).bodyJson()
                    .extractingPath("$[0]").asMap()
                    .containsOnlyKeys("id", "planType", "balance", "days", "withdrawals")
                    .containsEntry("id", "00000000-0000-4000-8000-000000000007");
        }

        // G3: an empty list is rendered, never omitted.
        @Test
        void rendersADepositWithoutWithdrawalsWithAnEmptyArray() {
            given(listTimeDeposits.listAll()).willReturn(List.of(aView(1, aDeposit("basic", 1000.0, 45))));

            assertThat(mvc.get().uri(TIME_DEPOSITS)).bodyJson().isStrictlyEqualTo("""
                    [{"id": "00000000-0000-4000-8000-000000000001", "planType": "basic",
                      "balance": "1000.00", "days": 45, "withdrawals": []}]
                    """);
        }

        @Test
        void keepsZeroValues() {
            given(listTimeDeposits.listAll()).willReturn(List.of(aView(1, aDeposit("basic", 0.0, 0))));

            assertThat(mvc.get().uri(TIME_DEPOSITS)).bodyJson().isStrictlyEqualTo("""
                    [{"id": "00000000-0000-4000-8000-000000000001", "planType": "basic",
                      "balance": "0.00", "days": 0, "withdrawals": []}]
                    """);
        }

        // G5: one sample; MoneyFormatterTest owns the rounding cases.
        @Test
        void formatsMoneyAsTwoDecimalStrings() {
            given(listTimeDeposits.listAll()).willReturn(List.of(
                    aView(1, aDeposit("basic", 1000.9300000000001, 400), aWithdrawal(2, "500", "2026-06-01"))));

            assertThat(mvc.get().uri(TIME_DEPOSITS)).bodyJson()
                    .hasPathSatisfying("$[0].balance", balance -> assertThat(balance).isEqualTo("1000.93"))
                    .hasPathSatisfying("$[0].withdrawals[0].amount", amount -> assertThat(amount).isEqualTo("500.00"));
        }

        // G7: planType is open (x-extensible-enum) and case-sensitive, so it is passed through unchanged.
        @Test
        void listsUnknownPlanTypesUnchanged() {
            given(listTimeDeposits.listAll()).willReturn(List.of(
                    aView(1, aDeposit("gold", 1.0, 31)), aView(2, aDeposit("Basic", 1.0, 31))));

            assertThat(mvc.get().uri(TIME_DEPOSITS)).bodyJson()
                    .extractingPath("$[*].planType").asArray().containsExactlyInAnyOrder("gold", "Basic");
        }

        // Order is not part of the contract (ADR-0004), but each withdrawal must stay with its deposit.
        @Test
        void listsEveryDepositReturnedByTheUseCase() {
            given(listTimeDeposits.listAll()).willReturn(List.of(
                    aView(1, aDeposit("basic", 100.0, 31), aWithdrawal(11, "1.00", "2026-01-01")),
                    aView(2, aDeposit("student", 200.0, 365)),
                    aView(3, aDeposit("premium", 300.0, 46), aWithdrawal(31, "3.00", "2026-03-03"))));

            assertThat(mvc.get().uri(TIME_DEPOSITS)).bodyJson().isEqualTo("""
                    [{"id": "00000000-0000-4000-8000-000000000003", "planType": "premium", "balance": "300.00",
                      "days": 46, "withdrawals": [{"id": "00000000-0000-4000-8000-000000000031",
                                                   "amount": "3.00", "date": "2026-03-03"}]},
                     {"id": "00000000-0000-4000-8000-000000000001", "planType": "basic", "balance": "100.00",
                      "days": 31, "withdrawals": [{"id": "00000000-0000-4000-8000-000000000011",
                                                   "amount": "1.00", "date": "2026-01-01"}]},
                     {"id": "00000000-0000-4000-8000-000000000002", "planType": "student", "balance": "200.00",
                      "days": 365, "withdrawals": []}]
                    """, JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE));
        }
    }

    @Nested
    @DisplayName("POST /balance-updates")
    class CreateBalanceUpdate {

        // Placeholder until the POST step implements the operation; that step replaces this test on purpose. Until then
        // it fails like any unexpected error: a 500 the contract declares.
        @Test
        void isNotImplementedYet() {
            assertThat(mvc.post().uri("/api/v1/balance-updates"))
                    .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Nested
    @DisplayName("errors as Problem Details (ADR-0003)")
    class Errors {

        // R5: no stack trace or exception message reaches the client, and absent optional fields are omitted.
        @Test
        void rendersAnUnexpectedErrorAsInternalServerError() {
            given(listTimeDeposits.listAll()).willThrow(new IllegalStateException("secret internal detail"));

            assertThat(mvc.get().uri(TIME_DEPOSITS))
                    .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPathSatisfying("$.status", status -> assertThat(status).isEqualTo(500))
                    .hasPathSatisfying("$.title", title -> assertThat(title).isEqualTo("Internal Server Error"))
                    .doesNotHavePath("$.detail");
            assertThat(mvc.get().uri(TIME_DEPOSITS)).body().asString()
                    .doesNotContain("secret internal detail")
                    .doesNotContain("null");
        }

        // The catch-all must not take over Spring MVC's own exceptions: a wrong method stays a 405.
        @Test
        void keepsSpringMvcErrorsAsTheirOwnStatus() {
            assertThat(mvc.post().uri(TIME_DEPOSITS))
                    .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .hasHeader(HttpHeaders.ALLOW, "GET")
                    .bodyJson().hasPathSatisfying("$.status", status -> assertThat(status).isEqualTo(405));
        }
    }
}
