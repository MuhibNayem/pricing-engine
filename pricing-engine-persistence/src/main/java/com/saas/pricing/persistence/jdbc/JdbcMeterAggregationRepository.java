package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.AggregationType;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.metering.spi.MeterAggregationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production-ready JDBC / PostgreSQL persistence adapter for MeterAggregationRepository.
 */
public class JdbcMeterAggregationRepository implements MeterAggregationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMeterAggregationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public Optional<MeterAggregation> findAggregation(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        String custVal = customerId.map(CustomerId::value).orElse("*");
        Timestamp startTs = Timestamp.from(window.startTime());
        Timestamp endTs = Timestamp.from(window.endTime());

        String sql = """
            SELECT aggregation_id, tenant_id, customer_id, meter_code, aggregation_type,
                   window_start, window_end, aggregated_value, event_count, last_event_time,
                   approximate
            FROM meter_aggregations
            WHERE tenant_id = ? AND customer_id = ? AND meter_code = ?
              AND window_start = ? AND window_end = ?
            """;

        List<MeterAggregation> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
            String cust = rs.getString("customer_id");
            Timestamp lastTs = rs.getTimestamp("last_event_time");
            return new MeterAggregation(
                TenantId.of(rs.getString("tenant_id")),
                "*".equals(cust) ? Optional.empty() : Optional.of(CustomerId.of(cust)),
                rs.getString("meter_code"),
                TimeWindow.of(rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant()),
                AggregationType.valueOf(rs.getString("aggregation_type")),
                rs.getBigDecimal("aggregated_value"),
                rs.getLong("event_count"),
                Optional.ofNullable(lastTs).map(Timestamp::toInstant),
                rs.getBoolean("approximate")
            );
        }, tenantId.value(), custVal, meterCode, startTs, endTs);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    @Transactional
    public void saveAggregation(MeterAggregation aggregation) {
        Objects.requireNonNull(aggregation, "aggregation cannot be null");

        String custVal = aggregation.customerId().map(CustomerId::value).orElse("*");
        Timestamp startTs = Timestamp.from(aggregation.window().startTime());
        Timestamp endTs = Timestamp.from(aggregation.window().endTime());
        Timestamp lastTs = aggregation.lastEventTime().map(Timestamp::from).orElse(null);
        Timestamp nowTs = Timestamp.from(Instant.now());

        String updateSql = """
            UPDATE meter_aggregations
            SET aggregated_value = ?, event_count = ?, last_event_time = ?, updated_at = ?, approximate = ?
            WHERE tenant_id = ? AND customer_id = ? AND meter_code = ?
              AND window_start = ? AND window_end = ?
            """;

        int updated = jdbcTemplate.update(
            updateSql,
            aggregation.aggregatedValue(),
            aggregation.eventCount(),
            lastTs,
            nowTs,
            aggregation.isApproximate(),
            aggregation.tenantId().value(),
            custVal,
            aggregation.meterCode(),
            startTs,
            endTs
        );

        if (updated == 0) {
            String insertSql = """
                INSERT INTO meter_aggregations (
                    aggregation_id, tenant_id, customer_id, meter_code, aggregation_type,
                    window_start, window_end, aggregated_value, event_count, last_event_time,
                    updated_at, approximate
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            jdbcTemplate.update(
                insertSql,
                UUID.randomUUID().toString(),
                aggregation.tenantId().value(),
                custVal,
                aggregation.meterCode(),
                aggregation.aggregationType().name(),
                startTs,
                endTs,
                aggregation.aggregatedValue(),
                aggregation.eventCount(),
                lastTs,
                nowTs,
                aggregation.isApproximate()
            );
        }
    }

    @Override
    @Transactional
    public void invalidate(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        String custVal = customerId.map(CustomerId::value).orElse("*");
        Timestamp startTs = Timestamp.from(window.startTime());
        Timestamp endTs = Timestamp.from(window.endTime());

        String sql = """
            DELETE FROM meter_aggregations
            WHERE tenant_id = ? AND customer_id = ? AND meter_code = ?
              AND window_start = ? AND window_end = ?
            """;

        jdbcTemplate.update(sql, tenantId.value(), custVal, meterCode, startTs, endTs);
    }

    @Override
    @Transactional
    public void invalidateForEvent(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, java.time.Instant eventTimestamp) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(eventTimestamp, "eventTimestamp cannot be null");

        Timestamp ts = Timestamp.from(eventTimestamp);
        String sql;
        Object[] params;

        if (customerId.isPresent()) {
            sql = """
                DELETE FROM meter_aggregations
                WHERE tenant_id = ? AND (customer_id = ? OR customer_id = '*') AND meter_code = ?
                  AND window_start <= ? AND window_end > ?
                """;
            params = new Object[]{tenantId.value(), customerId.get().value(), meterCode, ts, ts};
        } else {
            sql = """
                DELETE FROM meter_aggregations
                WHERE tenant_id = ? AND meter_code = ?
                  AND window_start <= ? AND window_end > ?
                """;
            params = new Object[]{tenantId.value(), meterCode, ts, ts};
        }

        jdbcTemplate.update(sql, params);
    }

    @Override
    public List<MeterAggregation> findAllAggregations(TenantId tenantId, Optional<CustomerId> customerId, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        Timestamp startTs = Timestamp.from(window.startTime());
        Timestamp endTs = Timestamp.from(window.endTime());

        String sql;
        Object[] params;

        if (customerId.isPresent()) {
            sql = """
                SELECT aggregation_id, tenant_id, customer_id, meter_code, aggregation_type,
                       window_start, window_end, aggregated_value, event_count, last_event_time
                FROM meter_aggregations
                WHERE tenant_id = ? AND customer_id = ?
                  AND window_start = ? AND window_end = ?
                """;
            params = new Object[]{tenantId.value(), customerId.get().value(), startTs, endTs};
        } else {
            sql = """
                SELECT aggregation_id, tenant_id, customer_id, meter_code, aggregation_type,
                       window_start, window_end, aggregated_value, event_count, last_event_time,
                       approximate
                FROM meter_aggregations
                WHERE tenant_id = ?
                  AND window_start = ? AND window_end = ?
                """;
            params = new Object[]{tenantId.value(), startTs, endTs};
        }

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String cust = rs.getString("customer_id");
            Timestamp lastTs = rs.getTimestamp("last_event_time");
            return new MeterAggregation(
                TenantId.of(rs.getString("tenant_id")),
                "*".equals(cust) ? Optional.empty() : Optional.of(CustomerId.of(cust)),
                rs.getString("meter_code"),
                TimeWindow.of(rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant()),
                AggregationType.valueOf(rs.getString("aggregation_type")),
                rs.getBigDecimal("aggregated_value"),
                rs.getLong("event_count"),
                Optional.ofNullable(lastTs).map(Timestamp::toInstant),
                rs.getBoolean("approximate")
            );
        }, params);
    }
}
