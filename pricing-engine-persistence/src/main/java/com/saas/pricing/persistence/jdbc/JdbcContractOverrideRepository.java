package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.hierarchy.ContractOverride;
import com.saas.pricing.core.spi.ContractOverrideRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production-ready JDBC / PostgreSQL persistence adapter for ContractOverrideRepository.
 */
public class JdbcContractOverrideRepository implements ContractOverrideRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcContractOverrideRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public Optional<ContractOverride> findEffectiveOverride(TenantId tenantId, CustomerId customerId, PlanCode planCode, Instant effectiveTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");

        Timestamp effTimestamp = Timestamp.from(effectiveTime);

        String sql = """
            SELECT payload_json FROM contract_overrides
            WHERE tenant_id = ? AND customer_id = ? AND plan_code = ?
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to >= ?)
              AND (superseded_at IS NULL OR superseded_at > ?)
            ORDER BY version DESC
            LIMIT 1
            """;

        List<String> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getString("payload_json"),
            tenantId.value(), customerId.value(), planCode.value(), effTimestamp, effTimestamp, effTimestamp
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(PricingJsonMapper.fromJson(results.getFirst(), ContractOverride.class));
    }

    @Override
    public Optional<ContractOverride> findBiTemporalOverride(TenantId tenantId, CustomerId customerId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");
        Objects.requireNonNull(systemTime, "systemTime cannot be null");

        Timestamp effTimestamp = Timestamp.from(effectiveTime);
        Timestamp sysTimestamp = Timestamp.from(systemTime);

        String sql = """
            SELECT payload_json FROM contract_overrides
            WHERE tenant_id = ? AND customer_id = ? AND plan_code = ?
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to >= ?)
              AND recorded_at <= ?
              AND (superseded_at IS NULL OR superseded_at > ?)
            ORDER BY version DESC
            LIMIT 1
            """;

        List<String> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getString("payload_json"),
            tenantId.value(), customerId.value(), planCode.value(), effTimestamp, effTimestamp, sysTimestamp, sysTimestamp
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(PricingJsonMapper.fromJson(results.getFirst(), ContractOverride.class));
    }

    @Override
    public void save(ContractOverride override) {
        Objects.requireNonNull(override, "override cannot be null");

        String payload = PricingJsonMapper.toJson(override);
        Timestamp effFrom = Timestamp.from(override.effectiveFrom());
        Timestamp effTo = override.effectiveTo().map(Timestamp::from).orElse(null);
        Timestamp recordedAt = Timestamp.from(override.recordedAt());
        Timestamp supersededAt = override.supersededAt().map(Timestamp::from).orElse(null);

        String checkSql = "SELECT COUNT(*) FROM contract_overrides WHERE contract_id = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, override.contractId());

        if (count != null && count > 0) {
            String updateSql = """
                UPDATE contract_overrides
                SET superseded_at = ?, payload_json = ?
                WHERE contract_id = ?
                """;
            jdbcTemplate.update(updateSql, supersededAt, payload, override.contractId());
        } else {
            String insertSql = """
                INSERT INTO contract_overrides (
                    contract_id, tenant_id, customer_id, plan_code, version,
                    effective_from, effective_to, recorded_at, superseded_at, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            jdbcTemplate.update(
                insertSql,
                override.contractId(),
                override.tenantId().value(),
                override.customerId().value(),
                override.planCode().value(),
                override.version(),
                effFrom,
                effTo,
                recordedAt,
                supersededAt,
                payload
            );
        }
    }
}
