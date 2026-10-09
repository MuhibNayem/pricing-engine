package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.PaymentAttempt;
import com.saas.pricing.core.spi.CollectionRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC / PostgreSQL collection ledger.
 *
 * <p>Duplicate protection rests on the primary key and the {@code (invoice_id, attempt_number)}
 * uniqueness constraint rather than on a check-then-write, so two collection workers racing on the
 * same due attempt cannot both take the customer's money.
 */
public class JdbcCollectionRepository implements CollectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCollectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    private static final String INSERT = """
        INSERT INTO payment_attempts (
            attempt_id, invoice_id, attempt_number, amount, currency, status,
            failure_code, failure_reason, attempted_at, next_attempt_at, payload_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT = """
        SELECT attempt_id, invoice_id, attempt_number, amount, currency, status,
               failure_code, failure_reason, attempted_at, next_attempt_at
        FROM payment_attempts
        """;

    /**
     * {@inheritDoc}
     *
     * <p>An identical re-delivery collides on the primary key and is reported as {@code false}
     * rather than raising, because that is exactly what a retried collection request looks like and
     * it must not become a second charge.
     */
    @Override
    @Transactional
    public boolean record(PaymentAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt cannot be null");
        try {
            jdbcTemplate.update(INSERT,
                attempt.attemptId(), attempt.invoiceId(), attempt.attemptNumber(),
                attempt.amount().amount(), attempt.amount().currency().code(), attempt.status().name(),
                attempt.failureCode().orElse(null), attempt.failureReason().orElse(null),
                Timestamp.from(attempt.attemptedAt()),
                attempt.nextAttemptAt().map(Timestamp::from).orElse(null),
                PricingJsonMapper.toJson(attempt),
                Timestamp.from(attempt.attemptedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            Integer conflicting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_attempts
                WHERE attempt_id = ? AND payload_json <> ?
                """, Integer.class, attempt.attemptId(), PricingJsonMapper.toJson(attempt));
            if (conflicting != null && conflicting > 0) {
                throw new IllegalArgumentException(
                    "Attempt id " + attempt.attemptId() + " exists with different content; the ledger is append-only", e);
            }
            return false;
        }
    }

    @Override
    public List<PaymentAttempt> findAttempts(TenantId tenantId, String invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        return jdbcTemplate.query(SELECT + " WHERE invoice_id = ? ORDER BY attempt_number",
            (rs, rowNum) -> read(rs), invoiceId);
    }

    @Override
    public Optional<PaymentAttempt> findLatest(TenantId tenantId, String invoiceId) {
        return jdbcTemplate.query(SELECT + " WHERE invoice_id = ? ORDER BY attempt_number DESC LIMIT 1",
                (rs, rowNum) -> read(rs), invoiceId)
            .stream().findFirst();
    }

    @Override
    public List<PaymentAttempt> findDue(Instant at) {
        Objects.requireNonNull(at, "at cannot be null");
        return jdbcTemplate.query(SELECT
                + " WHERE status = 'FAILED_RETRYABLE' AND next_attempt_at IS NOT NULL AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at, attempt_number",
            (rs, rowNum) -> read(rs), Timestamp.from(at));
    }

    private static PaymentAttempt read(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp next = rs.getTimestamp("next_attempt_at");
        return new PaymentAttempt(
            rs.getString("attempt_id"),
            rs.getString("invoice_id"),
            rs.getInt("attempt_number"),
            new Money(rs.getBigDecimal("amount"), CurrencyUnit.of(rs.getString("currency"))),
            PaymentAttempt.Status.valueOf(rs.getString("status")),
            Optional.ofNullable(rs.getString("failure_code")),
            Optional.ofNullable(rs.getString("failure_reason")),
            rs.getTimestamp("attempted_at").toInstant(),
            Optional.ofNullable(next).map(Timestamp::toInstant));
    }
}