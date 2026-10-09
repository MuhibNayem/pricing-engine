package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.idempotency.IdempotencyDecision;
import com.saas.pricing.core.model.idempotency.IdempotencyRecord;

import java.time.Duration;
import java.time.Instant;

/**
 * Storage for HTTP {@code Idempotency-Key} headers on money-moving endpoints.
 *
 * <p>Exists because the IETF draft is explicit that for many APIs "duplicated resources are a
 * severe problem from a business perspective", and that duplicate records "involving any kind of
 * money transfer MUST NOT be allowed". A retried {@code POST /invoices} that issues a second invoice
 * is exactly that.
 *
 * <p><strong>The claim must be atomic.</strong> Check-then-insert is a race: two concurrent
 * requests both see an absent key and both proceed. Implementations therefore claim in a single
 * database operation, not in two.
 *
 * <p><strong>Keys are scoped by tenant.</strong> Clients pick these keys and plausible ones collide.
 * Without the tenant in the primary key, a collision serves one tenant's stored response body to
 * another, which is a cross-tenant data leak rather than an inconvenience.
 *
 * <p><strong>{@code complete} and {@code release} take the claim, not the key.</strong> A request
 * that outlives its own TTL may find the key already reclaimed and re-executed by someone else.
 * Fencing on the claim's {@code recordedAt} means the slow original can neither overwrite the newer
 * execution's result nor delete its claim.
 */
public interface IdempotencyKeyStore {

    /**
     * Decides what to do with a request carrying {@code key}.
     *
     * <p>When the answer is {@link IdempotencyDecision.Action#PROCEED}, this method has already
     * <strong>claimed</strong> the key, and {@link IdempotencyDecision#claim()} returns that claim so
     * it can be fenced when completed or released. A concurrent duplicate sees
     * {@code IN_FLIGHT} rather than also proceeding.
     *
     * @param tenantId    owning tenant, part of the key's identity
     * @param key         the caller's Idempotency-Key
     * @param fingerprint digest of the request body
     * @param ttl         how long a claim or result is retained; the policy is published, not incidental
     * @param now         current time
     */
    IdempotencyDecision decide(TenantId tenantId, String key, String fingerprint, Duration ttl, Instant now);

    /**
     * Records the outcome of a claimed key so a later retry can replay it.
     *
     * <p>A no-op if the claim is no longer held: the key may have been released or reclaimed while
     * this request was running, and clobbering the current holder's row would be worse than losing
     * the replay.
     */
    void complete(TenantId tenantId, IdempotencyRecord claim, int httpStatus, String responseBody);

    /**
     * Removes a claim after a failure that persisted nothing, so the caller may retry.
     *
     * <p>Only for failures that are known to have left no trace. Once a side effect has been
     * committed the claim must be held, not released: a retry that is allowed to proceed would
     * perform the side effect a second time.
     */
    void release(TenantId tenantId, IdempotencyRecord claim);
}