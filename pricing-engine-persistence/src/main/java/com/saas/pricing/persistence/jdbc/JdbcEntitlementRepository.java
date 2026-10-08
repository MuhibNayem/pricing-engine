package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.spi.EntitlementRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production-ready JDBC / PostgreSQL persistence adapter for EntitlementRepository.
 * Supports high-throughput atomic SQL usage counter increments.
 */
public class JdbcEntitlementRepository implements EntitlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcEntitlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public Optional<CustomerEntitlement> findEntitlement(TenantId tenantId, CustomerId customerId, String featureKey, Instant timestamp) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        Timestamp ts = Timestamp.from(timestamp);
        String sql = """
            SELECT payload_json, current_usage FROM entitlements
            WHERE tenant_id = ? AND customer_id = ? AND feature_key = ?
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to >= ?)
            """;

        List<CustomerEntitlement> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
            String json = rs.getString("payload_json");
            BigDecimal currentUsage = rs.getBigDecimal("current_usage");
            CustomerEntitlement ent = PricingJsonMapper.fromJson(json, CustomerEntitlement.class);
            if (ent != null && currentUsage != null) {
                return new CustomerEntitlement(
                    ent.entitlementId(), ent.tenantId(), ent.customerId(), ent.planCode(),
                    ent.featureKey(), ent.type(), ent.booleanValue(), ent.quotaLimit(),
                    currentUsage, ent.isHardLimit(), ent.effectiveFrom(), ent.effectiveTo()
                );
            }
            return ent;
        }, tenantId.value(), customerId.value(), featureKey, ts, ts);

        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.getFirst());
    }

    @Override
    public List<CustomerEntitlement> findAllEntitlements(TenantId tenantId, CustomerId customerId, Instant timestamp) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        Timestamp ts = Timestamp.from(timestamp);
        String sql = """
            SELECT payload_json, current_usage FROM entitlements
            WHERE tenant_id = ? AND customer_id = ?
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to >= ?)
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String json = rs.getString("payload_json");
            BigDecimal currentUsage = rs.getBigDecimal("current_usage");
            CustomerEntitlement ent = PricingJsonMapper.fromJson(json, CustomerEntitlement.class);
            if (ent != null && currentUsage != null) {
                return new CustomerEntitlement(
                    ent.entitlementId(), ent.tenantId(), ent.customerId(), ent.planCode(),
                    ent.featureKey(), ent.type(), ent.booleanValue(), ent.quotaLimit(),
                    currentUsage, ent.isHardLimit(), ent.effectiveFrom(), ent.effectiveTo()
                );
            }
            return ent;
        }, tenantId.value(), customerId.value(), ts, ts);
    }

    @Override
    @Transactional
    public void saveEntitlement(CustomerEntitlement entitlement) {
        Objects.requireNonNull(entitlement, "entitlement cannot be null");

        String payload = PricingJsonMapper.toJson(entitlement);
        Timestamp effFrom = Timestamp.from(entitlement.effectiveFrom());
        Timestamp effTo = entitlement.effectiveTo().map(Timestamp::from).orElse(null);

        String updateSql = """
            UPDATE entitlements
            SET current_usage = ?, quota_limit = ?, effective_from = ?, effective_to = ?, payload_json = ?
            WHERE tenant_id = ? AND customer_id = ? AND feature_key = ?
            """;

        int updated = jdbcTemplate.update(
            updateSql,
            entitlement.currentUsage(),
            entitlement.quotaLimit().orElse(null),
            effFrom,
            effTo,
            payload,
            entitlement.tenantId().value(),
            entitlement.customerId().value(),
            entitlement.featureKey()
        );

        if (updated == 0) {
            String insertSql = """
                INSERT INTO entitlements (
                    entitlement_id, tenant_id, customer_id, plan_code, feature_key,
                    feature_type, boolean_value, quota_limit, current_usage,
                    is_hard_limit, effective_from, effective_to, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            jdbcTemplate.update(
                insertSql,
                entitlement.entitlementId(),
                entitlement.tenantId().value(),
                entitlement.customerId().value(),
                entitlement.planCode().value(),
                entitlement.featureKey(),
                entitlement.type().name(),
                entitlement.booleanValue(),
                entitlement.quotaLimit().orElse(null),
                entitlement.currentUsage(),
                entitlement.isHardLimit(),
                effFrom,
                effTo,
                payload
            );
        }
    }

    @Override
    @Transactional
    public void recordUsage(TenantId tenantId, CustomerId customerId, String featureKey, BigDecimal usageDelta) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(usageDelta, "usageDelta cannot be null");

        String sql = """
            UPDATE entitlements
            SET current_usage = current_usage + ?
            WHERE tenant_id = ? AND customer_id = ? AND feature_key = ?
            """;

        jdbcTemplate.update(sql, usageDelta, tenantId.value(), customerId.value(), featureKey);
    }
}
