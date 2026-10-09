package com.saas.pricing.persistence.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.spi.IdempotencyStore;
import com.saas.pricing.metering.spi.MeterEventRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Production-ready JDBC / PostgreSQL persistence adapter for MeterEventRepository and IdempotencyStore.
 */
public class JdbcMeterEventRepository implements MeterEventRepository, IdempotencyStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMeterEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public boolean saveEvent(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        String propertiesJson = PricingJsonMapper.toJson(event.properties());
        Timestamp eventTs = Timestamp.from(event.timestamp());

        String sql = """
            INSERT INTO meter_events (
                event_id, idempotency_key, tenant_id, customer_id, meter_code, event_value, event_timestamp, properties_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try {
            jdbcTemplate.update(
                sql,
                event.eventId(),
                event.idempotencyKey(),
                event.tenantId().value(),
                event.customerId().map(c -> c.value()).orElse(null),
                event.meterCode(),
                event.value(),
                eventTs,
                propertiesJson
            );
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * Atomically claims an idempotency key, remembering the content it was claimed for.
     *
     * <p>The claim itself is the atomic operation: an INSERT into {@code meter_idempotency_keys}
     * succeeds (we own the key) or the key already exists. The stored fingerprint then distinguishes
     * a retry (identical content, DUPLICATE) from a key reused for different content (CONFLICT),
     * which used to be silently answered as a duplicate and dropped a real event.</p>
     *
     * <p>The collision is absorbed with a savepoint ({@link JdbcDuplicateGuard}) because the naive
     * catch-then-read only works on H2; PostgreSQL aborts the transaction on the failed INSERT.</p>
     */
    @Override
    public IdempotencyStore.Claim claim(TenantId tenantId, String idempotencyKey,
                                        String fingerprint, Instant eventTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        String content = fingerprint == null ? "" : fingerprint;
        Instant recordedAt = eventTime != null ? eventTime : Instant.now();

        boolean inserted = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
            jdbcTemplate.update(
                "INSERT INTO meter_idempotency_keys (tenant_id, idempotency_key, fingerprint, recorded_at) "
                    + "VALUES (?, ?, ?, ?)",
                tenantId.value(), idempotencyKey, content, Timestamp.from(recordedAt)));
        if (inserted) {
            return IdempotencyStore.Claim.CLAIMED;
        }

        String stored = jdbcTemplate.query(
            "SELECT fingerprint FROM meter_idempotency_keys WHERE tenant_id = ? AND idempotency_key = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            tenantId.value(), idempotencyKey);
        if (stored == null) {
            // Released between the collision and the read: one retry of the claim is safe.
            boolean retried = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
                jdbcTemplate.update(
                    "INSERT INTO meter_idempotency_keys (tenant_id, idempotency_key, fingerprint, recorded_at) "
                        + "VALUES (?, ?, ?, ?)",
                    tenantId.value(), idempotencyKey, content, Timestamp.from(recordedAt)));
            return retried ? IdempotencyStore.Claim.CLAIMED : IdempotencyStore.Claim.DUPLICATE;
        }
        return stored.equals(content) ? IdempotencyStore.Claim.DUPLICATE : IdempotencyStore.Claim.CONFLICT;
    }

    /**
     * Releases a previously claimed key so a legitimate retry is not permanently rejected as a
     * duplicate.
     *
     * <p>The {@code recorded_at} predicate makes this a compare-and-remove: if the row has been
     * replaced since we wrote it, we must not delete the new owner's claim.
     */
    @Override
    public boolean remove(TenantId tenantId, String idempotencyKey, Instant recordedAt) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");

        if (recordedAt == null) {
            return false;
        }
        String sql = "DELETE FROM meter_idempotency_keys WHERE tenant_id = ? AND idempotency_key = ? AND recorded_at = ?";
        return jdbcTemplate.update(sql, tenantId.value(), idempotencyKey, Timestamp.from(recordedAt)) > 0;
    }

    @Override
    public boolean isDuplicate(TenantId tenantId, String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");

        String sql = "SELECT COUNT(*) FROM meter_events WHERE tenant_id = ? AND idempotency_key = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tenantId.value(), idempotencyKey);
        return count != null && count > 0;
    }

    @Override
    public List<MeterEvent> findEvents(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");

        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);

        String sql;
        Object[] params;

        if (customerId.isPresent()) {
            sql = """
                SELECT event_id, idempotency_key, tenant_id, customer_id, meter_code, event_value, event_timestamp, properties_json
                FROM meter_events
                WHERE tenant_id = ? AND customer_id = ? AND meter_code = ?
                  AND event_timestamp >= ? AND event_timestamp < ?
                ORDER BY event_timestamp ASC
                """;
            params = new Object[]{tenantId.value(), customerId.get().value(), meterCode, fromTs, toTs};
        } else {
            sql = """
                SELECT event_id, idempotency_key, tenant_id, customer_id, meter_code, event_value, event_timestamp, properties_json
                FROM meter_events
                WHERE tenant_id = ? AND meter_code = ?
                  AND event_timestamp >= ? AND event_timestamp < ?
                ORDER BY event_timestamp ASC
                """;
            params = new Object[]{tenantId.value(), meterCode, fromTs, toTs};
        }

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String custIdStr = rs.getString("customer_id");
            String propsStr = rs.getString("properties_json");
            Map<String, Object> props = propsStr != null && !propsStr.isBlank()
                ? PricingJsonMapper.fromJson(propsStr, Map.class)
                : Map.of();

            return new MeterEvent(
                rs.getString("event_id"),
                rs.getString("idempotency_key"),
                TenantId.of(rs.getString("tenant_id")),
                Optional.ofNullable(custIdStr).map(CustomerId::of),
                rs.getString("meter_code"),
                rs.getBigDecimal("event_value"),
                rs.getTimestamp("event_timestamp").toInstant(),
                props
            );
        }, params);
    }

    @Override
    public List<MeterEvent> findAllEvents(TenantId tenantId, Optional<CustomerId> customerId, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");

        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);

        String sql;
        Object[] params;

        if (customerId.isPresent()) {
            sql = """
                SELECT event_id, idempotency_key, tenant_id, customer_id, meter_code, event_value, event_timestamp, properties_json
                FROM meter_events
                WHERE tenant_id = ? AND customer_id = ?
                  AND event_timestamp >= ? AND event_timestamp < ?
                ORDER BY event_timestamp ASC
                """;
            params = new Object[]{tenantId.value(), customerId.get().value(), fromTs, toTs};
        } else {
            sql = """
                SELECT event_id, idempotency_key, tenant_id, customer_id, meter_code, event_value, event_timestamp, properties_json
                FROM meter_events
                WHERE tenant_id = ?
                  AND event_timestamp >= ? AND event_timestamp < ?
                ORDER BY event_timestamp ASC
                """;
            params = new Object[]{tenantId.value(), fromTs, toTs};
        }

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String custIdStr = rs.getString("customer_id");
            String propsStr = rs.getString("properties_json");
            Map<String, Object> props = propsStr != null && !propsStr.isBlank()
                ? PricingJsonMapper.fromJson(propsStr, Map.class)
                : Map.of();

            return new MeterEvent(
                rs.getString("event_id"),
                rs.getString("idempotency_key"),
                TenantId.of(rs.getString("tenant_id")),
                Optional.ofNullable(custIdStr).map(CustomerId::of),
                rs.getString("meter_code"),
                rs.getBigDecimal("event_value"),
                rs.getTimestamp("event_timestamp").toInstant(),
                props
            );
        }, params);
    }
}
