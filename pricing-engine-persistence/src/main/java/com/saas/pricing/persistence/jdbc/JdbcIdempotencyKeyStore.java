package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.idempotency.IdempotencyDecision;
import com.saas.pricing.core.model.idempotency.IdempotencyRecord;
import com.saas.pricing.core.spi.IdempotencyKeyStore;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * JDBC idempotency-key storage.
 *
 * <p>The claim is a single INSERT, so two concurrent requests carrying the same key cannot both
 * succeed: the primary key makes the loser fail rather than proceed. A check-then-insert would let
 * both through and issue two invoices, which is the duplicate money transfer the IETF draft warns
 * about.
 *
 * <p>No {@code @Transactional} here, deliberately. These methods are constructed by hand rather than
 * obtained from a proxy, so a transaction annotation would be inert and merely misleading about the
 * atomicity guarantee. Each statement autocommits, which is exactly what is wanted: a claim must be
 * visible to other connections the instant it is taken, and the atomicity comes from the primary key
 * rather than from an enclosing transaction.
 *
 * <p>The reclaim of an expired row is conditional and its update count is checked. Returning
 * {@code PROCEED} without inspecting that count is a defect: two racers both observe the expired
 * row, only one UPDATE matches, and the loser would proceed anyway — reintroducing the very
 * double-issue this table prevents.
 */
public class JdbcIdempotencyKeyStore implements IdempotencyKeyStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyKeyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    private static final String SELECT = """
        SELECT tenant_id, idem_key, fingerprint, status, response_status, response_body,
               recorded_at, expires_at
        FROM idempotency_keys
        WHERE tenant_id = ? AND idem_key = ?
        """;

    @Override
    public IdempotencyDecision decide(TenantId tenantId, String key, String fingerprint,
                                      Duration ttl, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fingerprint, "fingerprint cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }

        // PostgreSQL stores TIMESTAMP WITH TIME ZONE at microsecond resolution while Instant.now()
        // carries nanoseconds. The fence in complete()/release() compares recorded_at for equality,
        // so the claim time is truncated to microseconds here - otherwise the value read back after
        // a round trip would never equal the in-memory claim and the key would stay IN_FLIGHT until
        // its TTL, rejecting every retry with 409.
        Instant claimTime = now.truncatedTo(ChronoUnit.MICROS);
        IdempotencyRecord claim = IdempotencyRecord.claim(tenantId, key, fingerprint, claimTime, ttl);

        boolean inserted = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
            jdbcTemplate.update("""
                INSERT INTO idempotency_keys (
                    tenant_id, idem_key, fingerprint, status,
                    response_status, response_body, recorded_at, expires_at
                ) VALUES (?, ?, ?, 'IN_FLIGHT', 0, '', ?, ?)
                """,
                tenantId.value(), key, fingerprint,
                Timestamp.from(claimTime), Timestamp.from(claimTime.plus(ttl))));
        if (inserted) {
            return IdempotencyDecision.proceed(claim);
        }
        // Someone else holds the key; work out what they are doing.

        IdempotencyRecord existing = find(tenantId, key);
        if (existing == null) {
            // Released between the collision and the read. We hold nothing, so proceeding is safe.
            return IdempotencyDecision.proceed(claim);
        }

        if (existing.isExpired(now)) {
            int reclaimed = jdbcTemplate.update("""
                UPDATE idempotency_keys
                SET fingerprint = ?, status = 'IN_FLIGHT', response_status = 0, response_body = '',
                    recorded_at = ?, expires_at = ?
                WHERE tenant_id = ? AND idem_key = ? AND expires_at <= ?
                """,
                fingerprint, Timestamp.from(claimTime), Timestamp.from(claimTime.plus(ttl)),
                tenantId.value(), key, Timestamp.from(now));

            if (reclaimed == 1) {
                return IdempotencyDecision.proceed(claim);
            }
            // Lost the reclaim race. Re-read and judge properly: proceeding here would let two
            // racers execute the same request.
            existing = find(tenantId, key);
            if (existing == null) {
                return IdempotencyDecision.proceed(claim);
            }
            if (existing.isExpired(now)) {
                // Still expired but not claimable. Fail closed: tell the client to retry rather than
                // risk a second execution.
                return IdempotencyDecision.inFlight(existing);
            }
        }

        if (!existing.matches(fingerprint)) {
            return IdempotencyDecision.conflict(existing);
        }
        if (existing.status() == IdempotencyRecord.Status.COMPLETED) {
            return IdempotencyDecision.replay(existing);
        }
        return IdempotencyDecision.inFlight(existing);
    }

    @Override
    public void complete(TenantId tenantId, IdempotencyRecord claim, int httpStatus, String responseBody) {
        Objects.requireNonNull(claim, "claim cannot be null");
        // Fenced on recorded_at and on still-being-in-flight: a slow original whose key was already
        // reclaimed must not overwrite the newer execution's row. A zero update count is therefore
        // normally "someone else owns it now" and is a no-op; a live row that still matches the
        // claim could not match 0 rows, so that state means the fence is broken and is surfaced.
        int updated = jdbcTemplate.update("""
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_status = ?, response_body = ?
            WHERE tenant_id = ? AND idem_key = ? AND status = 'IN_FLIGHT' AND recorded_at = ?
            """,
            httpStatus, responseBody == null ? "" : responseBody,
            tenantId.value(), claim.key(), Timestamp.from(claim.recordedAt()));
        if (updated == 0 && stillHeldBy(tenantId, claim)) {
            throw new IllegalStateException(
                "Idempotency claim " + tenantId.value() + "/" + claim.key()
                    + " is still in flight but could not be completed; recorded_at did not match");
        }
    }

    @Override
    public void release(TenantId tenantId, IdempotencyRecord claim) {
        Objects.requireNonNull(claim, "claim cannot be null");
        int deleted = jdbcTemplate.update("""
            DELETE FROM idempotency_keys
            WHERE tenant_id = ? AND idem_key = ? AND status = 'IN_FLIGHT' AND recorded_at = ?
            """,
            tenantId.value(), claim.key(), Timestamp.from(claim.recordedAt()));
        if (deleted == 0 && stillHeldBy(tenantId, claim)) {
            throw new IllegalStateException(
                "Idempotency claim " + tenantId.value() + "/" + claim.key()
                    + " is still in flight but could not be released; recorded_at did not match");
        }
    }

    /** True when the stored row is still exactly the caller's live claim. */
    private boolean stillHeldBy(TenantId tenantId, IdempotencyRecord claim) {
        IdempotencyRecord current = find(tenantId, claim.key());
        return current != null
            && current.status() == IdempotencyRecord.Status.IN_FLIGHT
            && current.recordedAt().equals(claim.recordedAt());
    }

    private IdempotencyRecord find(TenantId tenantId, String key) {
        List<IdempotencyRecord> rows = jdbcTemplate.query(SELECT, (rs, rowNum) -> new IdempotencyRecord(
            TenantId.of(rs.getString("tenant_id")),
            rs.getString("idem_key"),
            rs.getString("fingerprint"),
            IdempotencyRecord.Status.valueOf(rs.getString("status")),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            rs.getTimestamp("recorded_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant()), tenantId.value(), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}