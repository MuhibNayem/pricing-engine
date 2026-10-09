package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.RatingClaimStore;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC / PostgreSQL rating-claim store.
 *
 * <p>The claim row is the durable memory of what a window has already been charged. A late event
 * that grows the aggregation makes the service charge only the difference; a retry after a restart
 * finds the claim and charges nothing.</p>
 *
 * <p>{@link #compareAndSet} is the cluster-safety primitive: two nodes rating the same window race
 * on one row, and only the winner may draw down the delta. The loser re-reads and sees the new base,
 * so it computes a zero delta instead of charging the customer twice.</p>
 */
public class JdbcRatingClaimStore implements RatingClaimStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRatingClaimStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public Optional<Charged> find(TenantId tenantId, String claimKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");

        List<Charged> rows = jdbcTemplate.query("""
            SELECT amount, currency, charged_at FROM rating_claims
            WHERE tenant_id = ? AND claim_key = ?
            """,
            (rs, rowNum) -> new Charged(
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getTimestamp("charged_at").toInstant()),
            tenantId.value(), claimKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public void record(TenantId tenantId, String claimKey, Charged charged) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(charged, "charged cannot be null");

        int updated = jdbcTemplate.update("""
            UPDATE rating_claims
            SET amount = ?, currency = ?, charged_at = ?
            WHERE tenant_id = ? AND claim_key = ?
            """,
            charged.amount(), charged.currency(), Timestamp.from(charged.chargedAt()),
            tenantId.value(), claimKey);

        if (updated == 0) {
            // The collision with a concurrent first write is absorbed with a savepoint so the
            // caller's transaction (if any) survives on PostgreSQL.
            JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
                jdbcTemplate.update("""
                    INSERT INTO rating_claims (tenant_id, claim_key, amount, currency, charged_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    tenantId.value(), claimKey, charged.amount(), charged.currency(),
                    Timestamp.from(charged.chargedAt())));
        }
    }

    @Override
    public boolean compareAndSet(TenantId tenantId, String claimKey,
                                 Optional<Charged> expected, Charged updated) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");
        Objects.requireNonNull(updated, "updated cannot be null");

        if (expected.isEmpty()) {
            return JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
                jdbcTemplate.update("""
                    INSERT INTO rating_claims (tenant_id, claim_key, amount, currency, charged_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    tenantId.value(), claimKey, updated.amount(), updated.currency(),
                    Timestamp.from(updated.chargedAt())));
        }

        Charged base = expected.get();
        int updatedRows = jdbcTemplate.update("""
            UPDATE rating_claims
            SET amount = ?, currency = ?, charged_at = ?
            WHERE tenant_id = ? AND claim_key = ?
              AND amount = ? AND currency = ? AND charged_at = ?
            """,
            updated.amount(), updated.currency(), Timestamp.from(updated.chargedAt()),
            tenantId.value(), claimKey,
            base.amount(), base.currency(), Timestamp.from(base.chargedAt()));
        return updatedRows == 1;
    }

    @Override
    public boolean remove(TenantId tenantId, String claimKey, Charged expected) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");

        return jdbcTemplate.update("""
            DELETE FROM rating_claims
            WHERE tenant_id = ? AND claim_key = ?
              AND amount = ? AND currency = ? AND charged_at = ?
            """,
            tenantId.value(), claimKey,
            expected.amount(), expected.currency(), Timestamp.from(expected.chargedAt())) == 1;
    }
}
