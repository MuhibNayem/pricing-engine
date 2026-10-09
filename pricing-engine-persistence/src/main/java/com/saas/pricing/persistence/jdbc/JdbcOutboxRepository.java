package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxEvent;
import com.saas.pricing.core.model.event.OutboxRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC / PostgreSQL outbox.
 *
 * <p>{@link #enqueue(OutboxEvent)} is {@code @Transactional} but joins the caller's transaction
 * rather than starting its own, which is the entire design: the event row and the state change
 * commit or roll back together.
 */
public class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    private static final String INSERT = """
        INSERT INTO outbox_events (
            event_id, topic, tenant_id, aggregate_type, aggregate_id, payload,
            occurred_at, created_at, attempts, next_attempt_at, last_error, delivered_at, signature
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT = """
        SELECT event_id, topic, tenant_id, aggregate_type, aggregate_id, payload,
               occurred_at, created_at, attempts, next_attempt_at, last_error, delivered_at, signature
        FROM outbox_events
        """;

    /**
     * {@inheritDoc}
     *
     * <p>An identical re-enqueue collides on the primary key and is a no-op: a retried transaction
     * writing the same event is not an error. Conflicting content under one id is refused.
     */
    @Override
    @Transactional
    public void enqueue(OutboxEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        try {
            // INSERT only. Going through the upsert here would UPDATE an existing row, so the
            // duplicate-key path would never fire and a conflicting re-enqueue would silently
            // overwrite the original event instead of being refused.
            insert(event);
        } catch (DuplicateKeyException e) {
            Integer conflicting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM outbox_events
                WHERE event_id = ? AND (payload <> ? OR topic <> ?)
                """, Integer.class, event.eventId(), event.payload(), event.topic());
            if (conflicting != null && conflicting > 0) {
                throw new IllegalArgumentException(
                    "Outbox event " + event.eventId() + " already queued with different content", e);
            }
        }
    }

    @Override
    @Transactional
    public void recordDelivery(OutboxEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        write(event);
    }

    /**
     * Upsert by hand: UPDATE first, INSERT only when nothing matched.
     *
     * <p>Deliberately not {@code INSERT ... ON CONFLICT DO UPDATE}, which H2 rejects even in
     * PostgreSQL emulation mode - and the test suite runs on H2.
     */
    private void insert(OutboxEvent event) {
        jdbcTemplate.update(INSERT,
            event.eventId(), event.topic(), event.tenantId(), event.aggregateType(),
            event.aggregateId(), event.payload(), Timestamp.from(event.occurredAt()),
            Timestamp.from(event.createdAt()), event.attempts(),
            event.nextAttemptAt().map(Timestamp::from).orElse(null),
            event.lastError().orElse(null),
            event.deliveredAt().map(Timestamp::from).orElse(null),
            event.signature().orElse(null));
    }

    private void write(OutboxEvent event) {
        int updated = jdbcTemplate.update("""
            UPDATE outbox_events
            SET attempts = ?, next_attempt_at = ?, last_error = ?, delivered_at = ?, signature = ?
            WHERE event_id = ?
            """,
            event.attempts(),
            event.nextAttemptAt().map(Timestamp::from).orElse(null),
            event.lastError().orElse(null),
            event.deliveredAt().map(Timestamp::from).orElse(null),
            event.signature().orElse(null),
            event.eventId());

        if (updated == 0) {
            insert(event);
        }
    }

    @Override
    public List<OutboxEvent> findDue(Instant at, int limit) {
        Objects.requireNonNull(at, "at cannot be null");
        int effective = limit <= 0 ? 100 : Math.min(limit, 1_000);
        return jdbcTemplate.query(SELECT
                + " WHERE delivered_at IS NULL AND attempts < ?"
                + "   AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
                + " ORDER BY created_at, event_id LIMIT ?",
            (rs, rowNum) -> read(rs), OutboxEvent.MAX_ATTEMPTS, Timestamp.from(at), effective);
    }

    @Override
    public List<OutboxEvent> findByTenant(TenantId tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        int effective = limit <= 0 ? 100 : Math.min(limit, 1_000);
        return jdbcTemplate.query(SELECT + " WHERE tenant_id = ? ORDER BY created_at, event_id LIMIT ?",
            (rs, rowNum) -> read(rs), tenantId.value(), effective);
    }

    @Override
    public List<OutboxEvent> findUndelivered(String topic, int limit) {
        int effective = limit <= 0 ? 100 : Math.min(limit, 1_000);
        if (topic == null || topic.isBlank()) {
            return jdbcTemplate.query(SELECT
                    + " WHERE delivered_at IS NULL ORDER BY created_at, event_id LIMIT ?",
                (rs, rowNum) -> read(rs), effective);
        }
        return jdbcTemplate.query(SELECT
                + " WHERE delivered_at IS NULL AND topic = ? ORDER BY created_at, event_id LIMIT ?",
            (rs, rowNum) -> read(rs), topic, effective);
    }

    private static OutboxEvent read(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp next = rs.getTimestamp("next_attempt_at");
        Timestamp delivered = rs.getTimestamp("delivered_at");
        return new OutboxEvent(
            rs.getString("event_id"),
            rs.getString("topic"),
            rs.getString("tenant_id"),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant(),
            created.toInstant(),
            rs.getInt("attempts"),
            Optional.ofNullable(next).map(Timestamp::toInstant),
            Optional.ofNullable(rs.getString("last_error")),
            Optional.ofNullable(delivered).map(Timestamp::toInstant),
            Optional.ofNullable(rs.getString("signature")));
    }
}