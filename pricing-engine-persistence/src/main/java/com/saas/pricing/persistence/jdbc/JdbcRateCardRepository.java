package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production-ready JDBC / PostgreSQL persistence adapter for RateCardRepository.
 * Stores indexed identity columns alongside rich JSONB / TEXT payload records.
 */
public class JdbcRateCardRepository implements RateCardRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRateCardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");

        Timestamp effTimestamp = Timestamp.from(effectiveTime);

        String sql = """
            SELECT payload_json FROM rate_cards
            WHERE tenant_id = ? AND plan_code = ?
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to >= ?)
              AND recorded_at <= CURRENT_TIMESTAMP
              AND (superseded_at IS NULL OR superseded_at > CURRENT_TIMESTAMP)
            ORDER BY version DESC
            LIMIT 1
            """;

        List<String> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getString("payload_json"),
            tenantId.value(), planCode.value(), effTimestamp, effTimestamp
        );

        if (results.isEmpty()) {
            return findGlobalRateCard(planCode, effectiveTime);
        }

        return Optional.of(PricingJsonMapper.fromJson(results.getFirst(), RateCard.class));
    }

    @Override
    public Optional<RateCard> findBiTemporalRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");
        Objects.requireNonNull(systemTime, "systemTime cannot be null");

        Timestamp effTimestamp = Timestamp.from(effectiveTime);
        Timestamp sysTimestamp = Timestamp.from(systemTime);

        String sql = """
            SELECT payload_json FROM rate_cards
            WHERE tenant_id = ? AND plan_code = ?
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
            tenantId.value(), planCode.value(), effTimestamp, effTimestamp, sysTimestamp, sysTimestamp
        );

        if (results.isEmpty()) {
            return findBiTemporalGlobalRateCard(planCode, effectiveTime, systemTime);
        }

        return Optional.of(PricingJsonMapper.fromJson(results.getFirst(), RateCard.class));
    }

    @Override
    public Optional<RateCard> findGlobalRateCard(PlanCode planCode, Instant effectiveTime) {
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");

        Timestamp effTimestamp = Timestamp.from(effectiveTime);
        String sql = """
            SELECT payload_json FROM rate_cards
            WHERE tenant_id = 'GLOBAL' AND plan_code = ?
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to >= ?)
              AND recorded_at <= CURRENT_TIMESTAMP
              AND (superseded_at IS NULL OR superseded_at > CURRENT_TIMESTAMP)
            ORDER BY version DESC
            LIMIT 1
            """;

        List<String> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getString("payload_json"),
            planCode.value(), effTimestamp, effTimestamp
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(PricingJsonMapper.fromJson(results.getFirst(), RateCard.class));
    }

    @Override
    public Optional<RateCard> findBiTemporalGlobalRateCard(PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");
        Objects.requireNonNull(systemTime, "systemTime cannot be null");

        Timestamp effTimestamp = Timestamp.from(effectiveTime);
        Timestamp sysTimestamp = Timestamp.from(systemTime);

        String sql = """
            SELECT payload_json FROM rate_cards
            WHERE tenant_id = 'GLOBAL' AND plan_code = ?
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
            planCode.value(), effTimestamp, effTimestamp, sysTimestamp, sysTimestamp
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(PricingJsonMapper.fromJson(results.getFirst(), RateCard.class));
    }

    @Override
    public void save(RateCard rateCard) {
        Objects.requireNonNull(rateCard, "rateCard cannot be null");

        String payload = PricingJsonMapper.toJson(rateCard);
        Timestamp effFrom = Timestamp.from(rateCard.effectiveFrom());
        Timestamp effTo = rateCard.effectiveTo().map(Timestamp::from).orElse(null);
        Timestamp recordedAt = Timestamp.from(rateCard.recordedAt());
        Timestamp supersededAt = rateCard.supersededAt().map(Timestamp::from).orElse(null);

        // Check if existing version exists
        String checkSql = "SELECT COUNT(*) FROM rate_cards WHERE tenant_id = ? AND plan_code = ? AND version = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class,
            rateCard.tenantId().value(), rateCard.planCode().value(), rateCard.version());

        if (count != null && count > 0) {
            // Update superseded_at and payload if version is being superseded
            String updateSql = """
                UPDATE rate_cards
                SET superseded_at = ?, payload_json = ?
                WHERE tenant_id = ? AND plan_code = ? AND version = ?
                """;
            jdbcTemplate.update(updateSql, supersededAt, payload,
                rateCard.tenantId().value(), rateCard.planCode().value(), rateCard.version());
        } else {
            String insertSql = """
                INSERT INTO rate_cards (
                    rate_card_id, tenant_id, plan_code, version, currency,
                    effective_from, effective_to, recorded_at, superseded_at, hierarchy_level, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            String currencyCode = rateCard.items().isEmpty() ? "USD" : rateCard.items().getFirst().baseCurrency().code();

            jdbcTemplate.update(
                insertSql,
                rateCard.rateCardId(),
                rateCard.tenantId().value(),
                rateCard.planCode().value(),
                rateCard.version(),
                currencyCode,
                effFrom,
                effTo,
                recordedAt,
                supersededAt,
                rateCard.hierarchyLevel().name(),
                payload
            );
        }
    }
}
