package org.ikigaidigital.shared.adapter.in.rest;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

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

    // The generated API interfaces carry @Validated, so Spring's AOP method validation checks their parameter
    // constraints (e.g. Idempotency-Key 1-255 characters) and throws ConstraintViolationException, which Spring Boot's
    // handler does not render. It is a client error the contract declares as 400 (verified in the web slice). The
    // detail names the parameter and the violated constraint, never a method or class name. Assumption: only the
    // generated API raises it today (no other Bean Validation in src/main); once services or entities validate too,
    // their violations are server errors and this mapping must be narrowed to the REST adapters.
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        String detail = exception.getConstraintViolations().stream()
                .map(violation -> lastNodeName(violation.getPropertyPath()) + ": " + violation.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    private static String lastNodeName(Path path) {
        String name = "";
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name;
    }

    // The contract declares 500 as Problem Details. The exception is logged, never sent: its message may reveal
    // internals.
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unexpected error while handling a request", exception);
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
