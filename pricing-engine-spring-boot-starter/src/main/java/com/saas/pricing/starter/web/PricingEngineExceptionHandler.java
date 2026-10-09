package com.saas.pricing.starter.web;

import com.saas.pricing.starter.tenant.TenantAccessDeniedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    /**
     * Invalid pricing input (unknown plan, malformed quantity, bad currency code, negative
     * quantity). These are caller errors, not server faults.
     */
    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    public ProblemDetail handleBadRequest(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, sanitize(ex));
        problem.setTitle("Invalid pricing request");
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

    private static String sanitize(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "The request could not be processed";
        }
        return message;
    }
}