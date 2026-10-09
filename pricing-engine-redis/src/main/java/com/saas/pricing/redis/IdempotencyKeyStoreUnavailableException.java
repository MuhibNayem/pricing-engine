package com.saas.pricing.redis;

/**
 * Raised when Redis cannot be reached or answers with something this adapter cannot interpret.
 *
 * <p>Named so the failure is unmistakable in a stack trace: it is not a bug, it is the adapter
 * refusing to proceed without deduplication. Treating this as "no claim held, go ahead" would
 * re-enable the duplicate charge the store exists to prevent.</p>
 *
 * <p>A host that would rather trade that risk for availability can catch this and proceed
 * deliberately. That is a business decision about double-charging, not a configuration detail, so it
 * is not made here.</p>
 */
public class IdempotencyKeyStoreUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyStoreUnavailableException(String message) {
        super(message);
    }

    public IdempotencyKeyStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}