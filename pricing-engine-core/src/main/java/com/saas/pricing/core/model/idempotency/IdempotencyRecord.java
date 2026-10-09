package com.saas.pricing.core.model.idempotency;

import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One recorded HTTP request, keyed by the caller's {@code Idempotency-Key}.
 *
 * <h2>Why a fingerprint is stored, not just the key</h2>
 * Without a fingerprint, a client that reuses one key for a <em>different</em> request silently gets
 * the first request's response — it believes it created something it did not. Storing a digest of
 * the request lets the server tell "this is a retry" (replay the stored result) from "this is a
 * different request wearing an old key" (reject).
 *
 * <h2>Why the key is scoped to a tenant</h2>
 * Idempotency keys are chosen by clients, and plausible ones collide: {@code invoice-2026-10-08},
 * a UUID derived from a timestamp, or a key minted once and reused by a retrying proxy. Without
 * tenant scoping, tenant B reusing a key that tenant A already used is served <strong>tenant A's
 * stored response body</strong> — a cross-tenant data leak, not merely a nuisance. Tenant is
 * therefore part of the primary key, not metadata.
 *
 * <h2>Status semantics (IETF draft, Standards Track)</h2>
 * <ul>
 *   <li>{@link Status#IN_FLIGHT} — the first request is still running. A concurrent duplicate gets
 *       <strong>409 Conflict</strong>, never the partial result.</li>
 *   <li>{@link Status#COMPLETED} — replay the stored outcome, success or failure.</li>
 *   <li>A fingerprint mismatch is <strong>422 Unprocessable Entity</strong>.</li>
 * </ul>
 *
 * @param tenantId       owning tenant; part of the identity of the key
 * @param key            the caller's Idempotency-Key
 * @param fingerprint    digest of the request body, so a changed payload is detectable
 * @param status         whether the original completed
 * @param responseStatus HTTP status of the original, replayed verbatim
 * @param responseBody   serialised original response, replayed verbatim
 * @param recordedAt     when the original started; also the fencing token for complete/release
 * @param expiresAt      when the entry may be reclaimed; the policy is published, not incidental
 */
public record IdempotencyRecord(
    TenantId tenantId,
    String key,
    String fingerprint,
    Status status,
    int responseStatus,
    String responseBody,
    Instant recordedAt,
    Instant expiresAt
) implements Serializable {

    /** Whether the original request has finished. */
    public enum Status {
        /** Claimed but not yet completed. A duplicate must not see a partial result. */
        IN_FLIGHT,
        /** Finished. The recorded response is replayed verbatim. */
        COMPLETED
    }

    public IdempotencyRecord {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fingerprint, "fingerprint cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(responseBody, "responseBody cannot be null");
        Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");

        if (key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (status == Status.COMPLETED && (responseStatus < 100 || responseStatus > 599)) {
            throw new IllegalArgumentException("responseStatus must be a valid HTTP status, got " + responseStatus);
        }
        if (!expiresAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException("expiresAt must be after recordedAt");
        }
    }

    /**
     * Digest of a request body.
     *
     * <p>SHA-256 over the raw bytes. This is a change detector, not a security boundary — the
     * comparison is constant-time so a mismatch does not leak how much of the digest was right.
     */
    public static String fingerprintOf(String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((requestBody == null ? "" : requestBody).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for idempotency fingerprints", e);
        }
    }

    /** True when {@code candidate} is the same request this record was made for. */
    public boolean matches(String candidateFingerprint) {
        return MessageDigest.isEqual(
            fingerprint.getBytes(StandardCharsets.UTF_8),
            (candidateFingerprint == null ? "" : candidateFingerprint).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Claims a key as in-flight for {@code tenantId}. */
    public static IdempotencyRecord claim(TenantId tenantId, String key, String fingerprint,
                                          Instant now, Duration ttl) {
        return new IdempotencyRecord(tenantId, key, fingerprint, Status.IN_FLIGHT, 0, "",
            now, now.plus(ttl));
    }
}