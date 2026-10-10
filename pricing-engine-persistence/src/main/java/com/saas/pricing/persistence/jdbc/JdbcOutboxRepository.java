package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxEvent;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.model.event.TraceContext;
import com.saas.pricing.core.model.event.TraceContextProvider;

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
    private final TraceContextProvider traceContexts;

    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, TraceContextProvider.NONE);
    }

    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate, TraceContextProvider traceContexts) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
        this.traceContexts = Objects.requireNonNull(traceContexts, "traceContexts cannot be null");
    }

    private static final String INSERT = """
        INSERT INTO outbox_events (
            event_id, topic, tenant_id, aggregate_type, aggregate_id, payload,
            occurred_at, created_at, attempts, next_attempt_at, last_error, delivered_at, signature,
            traceparent, tracestate
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT = """
        SELECT event_id, topic, tenant_id, aggregate_type, aggregate_id, payload,
               occurred_at, created_at, attempts, next_attempt_at, last_error, delivered_at, signature,
               traceparent, tracestate
        FROM outbox_events
        """;

    /**
     * {@inheritDoc}
     *
     * <p>An identical re-enqueue collides on the primary key and is a no-op: a retried transaction
     * writing the same event is not an error. Conflicting content under one id is refused.
     *
     * <p>The collision is absorbed through a savepoint ({@link JdbcDuplicateGuard}): catching the
     * duplicate-key exception and immediately reading the table only works on H2. On PostgreSQL the
     * failed INSERT aborts the transaction, so the recovery read - and every later statement - would
     * fail with 25P02.
     */
    @Override
    @Transactional
    public void enqueue(OutboxEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        // Stamped here, inside the caller's transaction, and only here. recordDelivery deliberately
        // does not re-read the provider: that would overwrite the link to the request that caused
        // the state change with a link to the dispatcher's own poll.
        OutboxEvent stamped = event.withTraceContext(traceContexts.current());
        // INSERT only. Going through the upsert here would UPDATE an existing row, so the
        // duplicate-key path would never fire and a conflicting re-enqueue would silently
        // overwrite the original event instead of being refused.
        boolean inserted = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () -> insert(stamped));
        if (inserted) {
            return;
        }
        Integer conflicting = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE event_id = ? AND (payload <> ? OR topic <> ?)
            """, Integer.class, stamped.eventId(), stamped.payload(), stamped.topic());
        if (conflicting != null && conflicting > 0) {
            throw new IllegalArgumentException(
                "Outbox event " + stamped.eventId() + " already queued with different content");
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
            event.signature().orElse(null),
            event.traceContext().traceparent().orElse(null),
            event.traceContext().tracestate().orElse(null));
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
            Optional.ofNullable(rs.getString("signature")),
            readTraceContext(rs));
    }

    /**
     * Rehydrates the stored headers, tolerating rows written before V22 added the columns.
     *
     * <p>Re-validated on the way out rather than trusted, because this is a column a database
     * operator can edit with {@code redis-cli}-style direct access and a malformed value reaching
     * a propagator would break correlation silently at the far end of a broker. A value that no
     * longer parses is dropped rather than fatal: losing the trace link is recoverable, refusing
     * to deliver the billing event is not.</p>
     */
    private static TraceContext readTraceContext(java.sql.ResultSet rs) throws java.sql.SQLException {
        String traceparent = rs.getString("traceparent");
        String tracestate = rs.getString("tracestate");
        if (traceparent == null || traceparent.isBlank()) {
            return TraceContext.NONE;
        }
        try {
            return new TraceContext(Optional.of(traceparent),
                Optional.ofNullable(tracestate).filter(value -> !value.isBlank()));
        } catch (IllegalArgumentException e) {
            return TraceContext.NONE;
        }
    }
}