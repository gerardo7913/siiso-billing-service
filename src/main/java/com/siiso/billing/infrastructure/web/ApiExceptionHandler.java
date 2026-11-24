package com.siiso.billing.infrastructure.web;

import com.siiso.billing.domain.BillingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Errors as RFC 9457 problem details ({@code application/problem+json}). {@code detail} is
 * user-facing (Spanish); {@code type} is a stable, machine-readable code the front end can
 * switch on; internal details are only logged.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);


    private static URI problemType(String code) {
        return URI.create("urn:siiso:problem:" + code);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(problemType(type));
        problem.setTitle(title);
        return problem;
    }

    @ExceptionHandler(BillingException.NotFound.class)
    ProblemDetail notFound(BillingException.NotFound ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = ex.getRequiredType() == java.time.LocalDate.class
                ? "Usa el formato de fecha AAAA-MM-DD."
                : "Valor inválido.";
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-search", "Invalid parameter", message);
        problem.setProperty("errors", Map.of(ex.getName(), message));
        return problem;
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail legacyUnavailable(RuntimeException ex) {
        log.error("Data source unavailable", ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "data-unavailable", "Data unavailable",
                "La información no está disponible en este momento. Inténtalo más tarde.");
    }

    @ExceptionHandler(BillingException.Invalid.class)
    ProblemDetail invalid(BillingException.Invalid ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-rate", "Invalid data", ex.getMessage());
        problem.setProperty("errors", ex.errors());
        return problem;
    }

    @ExceptionHandler(BillingException.Conflict.class)
    ProblemDetail conflict(BillingException.Conflict ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "rate-conflict", "Conflict", ex.getMessage());
        problem.setProperty("errors", Map.of(ex.field(), ex.getMessage()));
        return problem;
    }

    @ExceptionHandler(BillingException.Stale.class)
    ProblemDetail stale(BillingException.Stale ex) {
        return problem(HttpStatus.CONFLICT, "stale-rate-plan", "Stale rate plan", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "access-denied", "Access denied", "No tienes acceso a este recurso.");
    }

    /** Anything else: logged with its cause, answered without internal details. */
    @ExceptionHandler(RuntimeException.class)
    ProblemDetail unexpected(RuntimeException ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Ocurrió un error inesperado. Inténtalo de nuevo más tarde.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled error", ex);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
