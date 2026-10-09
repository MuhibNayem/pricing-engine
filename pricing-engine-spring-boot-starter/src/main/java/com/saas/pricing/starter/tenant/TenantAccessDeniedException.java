package com.saas.pricing.starter.tenant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when a caller's tenant cannot be established, or when a request claims a tenant other
 * than the one the caller is authorised for.
 *
 * <p>Mapped to HTTP 403 by the starter's problem-detail handler. The message deliberately does not
 * echo the requested tenant, so the API cannot be used to probe which tenants exist.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class TenantAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantAccessDeniedException(String message) {
        super(message);
    }

    public TenantAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}