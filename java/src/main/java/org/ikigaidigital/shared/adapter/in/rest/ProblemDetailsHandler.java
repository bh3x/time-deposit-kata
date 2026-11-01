package org.ikigaidigital.shared.adapter.in.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders any exception no other handler takes as a {@code 500} Problem Details response (ADR-0003). Spring MVC's own
 * exceptions (e.g. {@code 405}) are rendered by Spring Boot's Problem Details handler
 * ({@code spring.mvc.problemdetails.enabled}), which Boot registers with {@code @Order(0)}; this catch-all therefore
 * runs last, or it would turn those errors into a {@code 500}. Any further advice must declare a higher precedence
 * than {@link Ordered#LOWEST_PRECEDENCE}, since advices with equal order have no defined sequence.
 * <p>
 * It returns Spring's {@link ProblemDetail}, not the generated {@code ProblemDto}: Boot's handler renders
 * {@code ProblemDetail}, so all errors share one representation, which matches the contract's {@code Problem} schema.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class ProblemDetailsHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    // The contract declares 500 as Problem Details. The exception is logged, never sent: its message may reveal
    // internals.
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unexpected error while handling a request", exception);
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
