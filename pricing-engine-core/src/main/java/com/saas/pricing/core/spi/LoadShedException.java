package com.saas.pricing.core.spi;

import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when admission control sheds work before it reaches the pricing path.
 *
 * <p>Refusing early with a typed reason is materially better than the alternatives inside a billing
 * system: a caller that is told "your quota" can slow down and retry, and a caller that is told "we
 * are saturated" knows to back off without believing the service is broken. Letting the work in and
 * failing partway means a timeout, or worse, a partially applied charge.</p>
 *
 * <p>{@link #rateLimited()} maps to HTTP 429 and {@link #overloaded()} to HTTP 503. They are not
 * interchangeable: answering 503 to one tenant that is merely over its own quota is a false outage
 * signal that makes healthy clients back off, and it hides the real problem from monitoring.</p>
 */
public class LoadShedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AdmissionController.Shedding reason;
    private final Duration retryAfter;

    public LoadShedException(AdmissionController.Shedding reason, Duration retryAfter) {
        super(describe(reason));
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter cannot be null");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter cannot be negative");
        }
    }

    /** Builds the exception for a shed admission. Fails loudly if handed an admitted one. */
    public static LoadShedException from(AdmissionController.Admission admission) {
        Objects.requireNonNull(admission, "admission cannot be null");
        if (admission.admitted()) {
            throw new IllegalArgumentException("an admitted result cannot be converted into a shed exception");
        }
        return new LoadShedException(admission.reason(), admission.retryAfter());
    }

    private static String describe(AdmissionController.Shedding reason) {
        return switch (reason) {
            case RATE_LIMITED -> "Rate limit exceeded for this tenant";
            case OVERLOADED -> "Pricing engine is at capacity";
        };
    }

    /** HTTP 429 - this tenant's own quota. */
    public boolean rateLimited() {
        return reason == AdmissionController.Shedding.RATE_LIMITED;
    }

    /** HTTP 503 - the engine itself is saturated, for every tenant. */
    public boolean overloaded() {
        return reason == AdmissionController.Shedding.OVERLOADED;
    }

    /** Why the work was shed. */
    public AdmissionController.Shedding reason() {
        return reason;
    }

    /** The caller's floor for how long to wait before retrying. Never retry sooner. */
    public Duration retryAfter() {
        return retryAfter;
    }
}