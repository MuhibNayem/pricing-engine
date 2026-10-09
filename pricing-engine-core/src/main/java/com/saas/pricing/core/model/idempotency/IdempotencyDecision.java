package com.saas.pricing.core.model.idempotency;

/**
 * What should happen to a request carrying an {@code Idempotency-Key}.
 *
 * <p>Modelled on the IETF {@code Idempotency-Key} header (Standards Track), so the engine's
 * behaviour is an implementation of a published contract rather than a private convention:
 *
 * <ul>
 *   <li><strong>PROCEED</strong> — the key is new, or the previous attempt had expired. Run the
 *       action and record the outcome. Carries the claim in {@link #claim()} so completion can be
 *       fenced against a later reclaim.</li>
 *   <li><strong>REPLAY</strong> — the same request already completed. Return the stored response
 *       <em>verbatim</em>, including its original error status, because the client asked for that
 *       request and the answer has not changed.</li>
 *   <li><strong>IN_FLIGHT</strong> — an identical attempt is still running. <strong>409</strong>.
 *       Never return a partial result: a client that sees half an invoice is worse than one that
 *       is told to wait.</li>
 *   <li><strong>CONFLICT</strong> — the key exists but the payload differs. <strong>422</strong>.
 *       Silently replaying here would tell the caller it had created something it had not.</li>
 * </ul>
 *
 * @param action   what the server should do
 * @param httpStatus the status to return; 0 only for {@link Action#PROCEED}, where none applies
 * @param stored   the existing record for REPLAY/IN_FLIGHT/CONFLICT, or the new claim for PROCEED
 */
public record IdempotencyDecision(Action action, int httpStatus, IdempotencyRecord stored) {

    /** What the server should do. */
    public enum Action {
        /** Run the request and record its outcome. */
        PROCEED,
        /** Return the stored response unchanged. */
        REPLAY,
        /** 409: an identical attempt is still running. */
        IN_FLIGHT,
        /** 422: the key was reused for a different payload. */
        CONFLICT
    }

    public IdempotencyDecision {
        if (action == null) {
            throw new IllegalArgumentException("action cannot be null");
        }
        if (stored == null) {
            throw new IllegalArgumentException("stored cannot be null");
        }
    }

    /** Proceed, having claimed {@code claim}. */
    public static IdempotencyDecision proceed(IdempotencyRecord claim) {
        return new IdempotencyDecision(Action.PROCEED, 0, claim);
    }

    /**
     * Proceed without an idempotency claim, because the caller supplied no key.
     *
     * <p>The synthetic record is never persisted; it exists only so {@link #stored()} is total.
     */
    public static IdempotencyDecision proceedUnkeyed(String placeholderKey) {
        var now = java.time.Instant.EPOCH;
        return new IdempotencyDecision(Action.PROCEED, 0,
            IdempotencyRecord.claim(com.saas.pricing.core.model.TenantId.of("_unkeyed"),
                placeholderKey, "unkeyed", now, java.time.Duration.ofNanos(1)));
    }

    public static IdempotencyDecision replay(IdempotencyRecord stored) {
        return new IdempotencyDecision(Action.REPLAY, stored.responseStatus(), stored);
    }

    public static IdempotencyDecision inFlight(IdempotencyRecord stored) {
        // 409 Conflict: the original is still running and its result is not knowable yet.
        return new IdempotencyDecision(Action.IN_FLIGHT, 409, stored);
    }

    public static IdempotencyDecision conflict(IdempotencyRecord stored) {
        // 422 Unprocessable Entity: the key is valid but was reused for a different request.
        return new IdempotencyDecision(Action.CONFLICT, 422, stored);
    }

    public boolean shouldExecute() {
        return action == Action.PROCEED;
    }

    /** The claim made by {@link Action#PROCEED}, to be fenced by complete/release. */
    public IdempotencyRecord claim() {
        return stored;
    }
}