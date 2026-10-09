package com.saas.pricing.starter.web;

import com.saas.pricing.starter.tenant.TenantAccessDeniedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;

/**
 * Maps exceptions to RFC 9457 {@code application/problem+json} responses.
 *
 * <p>Without this, a malformed request returns a stack trace with HTTP 500, which tells a caller
 * (and an attacker) the internal type and message of every failure path.
 */
@RestControllerAdvice
public class PricingEngineExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PricingEngineExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://docs.aequitas.example/problems/";

    /**
 * Work shed by admission control, mapped to the status that tells the caller what to do.
 *
 * <p>429 means <em>your</em> quota and the engine is healthy — wait the {@code Retry-After} and
 * slow down. 503 means the engine itself is saturated and no tenant's quota is at fault. They are
 * not interchangeable: answering 503 to a tenant that is merely over its own rate makes healthy
 * clients believe the platform is down, and hides the real signal from monitoring.</p>
 *
 * <p>{@code Retry-After} is set on both. Omitting it leaves every client to invent its own backoff,
 * which produces synchronised retries that immediately re-saturate the thing that just shed them.</p>
 */
@ExceptionHandler(com.saas.pricing.core.spi.LoadShedException.class)
public ResponseEntity<ProblemDetail> handleLoadShed(com.saas.pricing.core.spi.LoadShedException ex) {
    boolean rateLimited = ex.rateLimited();
    HttpStatus status = rateLimited ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    problem.setTitle(rateLimited ? "Rate limit exceeded" : "Service temporarily overloaded");
    problem.setType(URI.create(PROBLEM_BASE + (rateLimited ? "rate-limited" : "overloaded")));
    problem.setProperty("code", rateLimited ? "RATE_LIMITED" : "ENGINE_OVERLOADED");
    problem.setProperty("retryAfterSeconds", Math.max(0L, ex.retryAfter().toSeconds()));

    long retryAfterSeconds = Math.max(1L, ex.retryAfter().toSeconds());
    return ResponseEntity.status(status)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
            .body(problem);
}

/** Tenant mismatch or missing tenant. Never echo the requested tenant back to the caller. */
    @ExceptionHandler(TenantAccessDeniedException.class)
    public ProblemDetail handleTenantAccessDenied(TenantAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "The caller is not authorised to act on this tenant");
        problem.setTitle("Tenant access denied");
        problem.setType(URI.create(PROBLEM_BASE + "tenant-access-denied"));
        problem.setProperty("code", "TENANT_ACCESS_DENIED");
        return problem;
    }

    /** Bean-validation failures on request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body failed validation");
        problem.setTitle("Validation failed");
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Method parameter validation failures (e.g. collection element validation). */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(org.springframework.web.method.annotation.HandlerMethodValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request parameter or body failed validation");
        problem.setTitle("Validation failed");
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setProperty("code", "VALIDATION_FAILED");
        return problem;
    }

    /**
     * Invalid pricing input (unknown plan, malformed quantity, bad currency code, negative
     * quantity). These are caller errors, not server faults.
     *
     * <p>{@link NullPointerException} is deliberately NOT mapped here: a null pointer is a server
     * bug, and answering 400 for it hides the defect behind "bad request" forever.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, sanitize(ex));
        problem.setTitle("Invalid pricing request");
        problem.setType(URI.create(PROBLEM_BASE + "invalid-request"));
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }

    /** A referenced document does not exist. */
    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ProblemDetail handleNotFound(java.util.NoSuchElementException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, sanitize(ex));
        problem.setTitle("Resource not found");
        problem.setType(URI.create(PROBLEM_BASE + "not-found"));
        problem.setProperty("code", "NOT_FOUND");
        return problem;
    }

    /**
     * Preserves the status a controller deliberately raised (409 for an in-flight idempotency
     * claim, 422 for key reuse). Without this handler the catch-all below would turn every one of
     * them into a 500.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(org.springframework.web.server.ResponseStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(),
            ex.getReason() == null ? "Request rejected" : ex.getReason());
        problem.setTitle("Request rejected");
        problem.setType(URI.create(PROBLEM_BASE + "request-rejected"));
        problem.setProperty("code", "REQUEST_REJECTED");
        return problem;
    }

    /**
     * Missing request headers, parameters, path variables, type conversion failures, or
     * unreadable JSON bodies. These are caller client errors, mapped to 400 Bad Request.
     */
    @ExceptionHandler({
        org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ProblemDetail handleBindingAndConversionErrors(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, sanitize(ex));
        problem.setTitle("Invalid request");
        problem.setType(URI.create(PROBLEM_BASE + "invalid-request"));
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }

    /** Engine misconfiguration or a missing required resource. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "The pricing engine is not correctly configured");
        problem.setTitle("Pricing engine unavailable");
        problem.setType(URI.create(PROBLEM_BASE + "engine-misconfigured"));
        problem.setProperty("code", "ENGINE_MISCONFIGURED");
        // Log the detail; never return it to the caller.
        log.error("Pricing engine misconfiguration", ex);
        return problem;
    }

    /** Anything unforeseen: log fully, return an opaque message. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected pricing engine failure", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred while rating the request");
        problem.setTitle("Internal error");
        problem.setType(URI.create(PROBLEM_BASE + "internal-error"));
        problem.setProperty("code", "INTERNAL_ERROR");
        return problem;
    }

    private static String sanitize(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "The request could not be processed";
        }
        return message;
    }
}