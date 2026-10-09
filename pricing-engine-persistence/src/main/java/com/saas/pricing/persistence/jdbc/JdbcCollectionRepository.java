package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.PaymentAttempt;
import com.saas.pricing.core.spi.CollectionRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;

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
        SELECT pa.attempt_id, pa.invoice_id, pa.attempt_number, pa.amount, pa.currency, pa.status,
               pa.failure_code, pa.failure_reason, pa.attempted_at, pa.next_attempt_at
        FROM payment_attempts pa
        """;

    /**
     * Tenant scoping is enforced by joining the invoice, because {@code payment_attempts} carries no
     * tenant column of its own (V10). Without this, any caller holding an invoice id could read
     * another tenant's collection history.
     */
    private static final String TENANT_SCOPE = """
         AND EXISTS (
             SELECT 1 FROM invoices i
             WHERE i.invoice_id = pa.invoice_id AND i.tenant_id = ?
         )
        """;

    /**
     * {@inheritDoc}
     *
     * <p>An identical re-delivery collides on the primary key and is reported as {@code false}
     * rather than raising, because that is exactly what a retried collection request looks like and
     * it must not become a second charge.
     *
     * <p>The collision is absorbed through a savepoint ({@link JdbcDuplicateGuard}); the naive
     * catch-then-read pattern only works on H2, because PostgreSQL aborts the transaction on the
     * failed INSERT.
     */
    @Override
    @Transactional
    public boolean record(PaymentAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt cannot be null");
        String payload = PricingJsonMapper.toJson(attempt);
        boolean inserted = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
            jdbcTemplate.update(INSERT,
                attempt.attemptId(), attempt.invoiceId(), attempt.attemptNumber(),
                attempt.amount().amount(), attempt.amount().currency().code(), attempt.status().name(),
                attempt.failureCode().orElse(null), attempt.failureReason().orElse(null),
                Timestamp.from(attempt.attemptedAt()),
                attempt.nextAttemptAt().map(Timestamp::from).orElse(null),
                payload,
                Timestamp.from(attempt.attemptedAt())));
        if (inserted) {
            return true;
        }
        Integer conflicting = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM payment_attempts
            WHERE attempt_id = ? AND payload_json <> ?
            """, Integer.class, attempt.attemptId(), payload);
        if (conflicting != null && conflicting > 0) {
            throw new IllegalArgumentException(
                "Attempt id " + attempt.attemptId() + " exists with different content; the ledger is append-only");
        }
        return false;
    }

    @Override
    public List<PaymentAttempt> findAttempts(TenantId tenantId, String invoiceId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        return jdbcTemplate.query(SELECT
                + " WHERE pa.invoice_id = ?" + TENANT_SCOPE + " ORDER BY pa.attempt_number",
            (rs, rowNum) -> read(rs), invoiceId, tenantId.value());
    }

    @Override
    public Optional<PaymentAttempt> findLatest(TenantId tenantId, String invoiceId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        return jdbcTemplate.query(SELECT
                + " WHERE pa.invoice_id = ?" + TENANT_SCOPE + " ORDER BY pa.attempt_number DESC LIMIT 1",
                (rs, rowNum) -> read(rs), invoiceId, tenantId.value())
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