package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.EntitlementEvent;
import com.saas.pricing.core.model.entitlement.FeatureType;
import com.saas.pricing.core.spi.EntitlementEventRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC / PostgreSQL storage for the entitlement event stream.
 *
 * <p>Append-only: there is no UPDATE or DELETE anywhere in this class. Migration V9 additionally
 * rejects both at the database level, so a bug here is caught even if it somehow escaped.
 */
public class JdbcEntitlementEventRepository implements EntitlementEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcEntitlementEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    private static final String INSERT = """
        INSERT INTO entitlement_events (
            event_id, tenant_id, customer_id, feature_key, change_type, feature_type,
            quota_limit, effective_at, recorded_at, previous_state_json, reason, payload_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT = """
        SELECT event_id, tenant_id, customer_id, feature_key, change_type, feature_type,
               quota_limit, effective_at, recorded_at, previous_state_json, reason, payload_json
        FROM entitlement_events
        """;

    /**
     * {@inheritDoc}
     *
     * <p>A redelivered identical event is silently skipped, which is what makes an at-least-once
     * transport safe to feed from. Re-using an id for different content still raises, because one
     * id must not be able to mean two different changes.
     *
     * <p>The collision is absorbed through a savepoint ({@link JdbcDuplicateGuard}); catching the
     * duplicate-key exception and then reading the table only works on H2, because PostgreSQL
     * aborts the transaction on the failed INSERT.
     */
    @Override
    @Transactional
    public void append(List<EntitlementEvent> events) {
        Objects.requireNonNull(events, "events cannot be null");
        for (EntitlementEvent event : events) {
            String payload = PricingJsonMapper.toJson(event);
            boolean inserted = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
                jdbcTemplate.update(INSERT,
                    event.eventId(), event.tenantId().value(), event.customerId().value(),
                    event.featureKey(), event.type().name(), event.featureType().name(),
                    event.quotaLimit().orElse(null),
                    Timestamp.from(event.effectiveAt()), Timestamp.from(event.recordedAt()),
                    event.previousState().map(PricingJsonMapper::toJson).orElse(null),
                    event.reason(), payload,
                    Timestamp.from(event.recordedAt())));
            if (inserted) {
                continue;
            }
            Integer conflicts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM entitlement_events
                WHERE event_id = ? AND payload_json <> ?
                """, Integer.class, event.eventId(), payload);
            if (conflicts != null && conflicts > 0) {
                throw new IllegalArgumentException(
                    "Entitlement event id " + event.eventId()
                        + " already exists with different content; the stream is append-only");
            }
            // Identical redelivery: already recorded, nothing to do.
        }
    }

    @Override
    public List<EntitlementEvent> findEvents(TenantId tenantId, CustomerId customerId,
                                             String featureKey, Optional<Instant> effectiveBefore) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE tenant_id = ? AND customer_id = ? AND feature_key = ?");
        List<Object> params = new java.util.ArrayList<>(
            List.of(tenantId.value(), customerId.value(), featureKey));
        if (effectiveBefore.isPresent()) {
            sql.append(" AND effective_at <= ?");
            params.add(Timestamp.from(effectiveBefore.get()));
        }
        sql.append(" ORDER BY effective_at, recorded_at, event_id");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> read(rs), params.toArray());
    }

    @Override
    public List<EntitlementEvent> findAllEvents(TenantId tenantId, CustomerId customerId,
                                                Optional<Instant> effectiveBefore) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE tenant_id = ? AND customer_id = ?");
        List<Object> params = new java.util.ArrayList<>(
            List.of(tenantId.value(), customerId.value()));
        if (effectiveBefore.isPresent()) {
            sql.append(" AND effective_at <= ?");
            params.add(Timestamp.from(effectiveBefore.get()));
        }
        sql.append(" ORDER BY effective_at, recorded_at, event_id");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> read(rs), params.toArray());
    }

    @Override
    public Optional<EntitlementEvent> findLatest(TenantId tenantId, CustomerId customerId,
                                                String featureKey) {
        return jdbcTemplate.query(SELECT
                + " WHERE tenant_id = ? AND customer_id = ? AND feature_key = ?"
                + " ORDER BY effective_at DESC, recorded_at DESC, event_id DESC LIMIT 1",
            (rs, rowNum) -> read(rs), tenantId.value(), customerId.value(), featureKey)
            .stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private static EntitlementEvent read(java.sql.ResultSet rs) throws java.sql.SQLException {
        String previousJson = rs.getString("previous_state_json");
        return new EntitlementEvent(
            rs.getString("event_id"),
            TenantId.of(rs.getString("tenant_id")),
            CustomerId.of(rs.getString("customer_id")),
            rs.getString("feature_key"),
            EntitlementEvent.ChangeType.valueOf(rs.getString("change_type")),
            FeatureType.valueOf(rs.getString("feature_type")),
            Optional.ofNullable(rs.getBigDecimal("quota_limit")),
            rs.getTimestamp("effective_at").toInstant(),
            rs.getTimestamp("recorded_at").toInstant(),
            previousJson == null
                ? Optional.empty()
                : Optional.of(PricingJsonMapper.fromJson(previousJson, EntitlementEvent.EntitlementState.class)),
            rs.getString("reason"),
            Map.of());
    }
}