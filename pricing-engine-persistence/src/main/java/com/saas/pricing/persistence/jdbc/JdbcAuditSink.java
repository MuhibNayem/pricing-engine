package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.persistence.json.PricingJsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production-ready JDBC / PostgreSQL persistence adapter for AuditSink.
 * Persists complete calculation financial ledger and audit trace for compliance.
 */
public class JdbcAuditSink implements AuditSink {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuditSink(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public void record(PricingResult result) {
        Objects.requireNonNull(result, "result cannot be null");

        String payload = PricingJsonMapper.toJson(result);
        Timestamp evalTimestamp = Timestamp.from(result.evaluatedAt());

        String sql = """
            INSERT INTO pricing_audit_ledger (
                calculation_id, tenant_id, customer_id, plan_code, evaluated_at, currency,
                gross_amount, discount_amount, net_amount, tax_amount, final_total, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(
            sql,
            result.calculationId(),
            result.tenantId().value(),
            result.customerId().map(c -> c.value()).orElse(null),
            result.planCode().value(),
            evalTimestamp,
            result.currency().code(),
            result.totalGross().amount(),
            result.totalDiscount().amount(),
            result.totalNet().amount(),
            result.totalTax().amount(),
            result.finalTotal().amount(),
            payload
        );
    }

    public Optional<PricingResult> findAuditRecord(TenantId tenantId, String calculationId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(calculationId, "calculationId cannot be null");

        String sql = "SELECT payload_json FROM pricing_audit_ledger WHERE tenant_id = ? AND calculation_id = ?";
        List<String> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getString("payload_json"),
            tenantId.value(), calculationId
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(PricingJsonMapper.fromJson(results.getFirst(), PricingResult.class));
    }

    public List<PricingResult> findByTenant(TenantId tenantId, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");

        String sql = """
            SELECT payload_json FROM pricing_audit_ledger
            WHERE tenant_id = ? AND evaluated_at >= ? AND evaluated_at < ?
            ORDER BY evaluated_at DESC
            """;

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> PricingJsonMapper.fromJson(rs.getString("payload_json"), PricingResult.class),
            tenantId.value(), Timestamp.from(from), Timestamp.from(to)
        );
    }
}
