package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.core.spi.SubscriptionRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC / PostgreSQL persistence for subscriptions.
 *
 * <p>The row holds current state and is updated in place; the transition history is announced
 * through the outbox.
 */
public class JdbcSubscriptionRepository implements SubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    private static final String COLUMNS = """
        subscription_id, tenant_id, customer_id, plan_code, status, created_at,
        current_period_start, current_period_end, trial_ends_at, cancel_at_period_end,
        canceled_at, paused_at, version, payload_json
        """;

    @Override
    @Transactional
    public void create(Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription cannot be null");
        try {
            jdbcTemplate.update("""
                INSERT INTO subscriptions (
                    subscription_id, tenant_id, customer_id, plan_code, status, created_at,
                    current_period_start, current_period_end, trial_ends_at, cancel_at_period_end,
                    canceled_at, paused_at, version, payload_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                subscription.subscriptionId(), subscription.tenantId().value(),
                subscription.customerId().value(), subscription.planCode().value(),
                subscription.status().name(), Timestamp.from(subscription.createdAt()),
                Timestamp.from(subscription.currentPeriodStart()),
                Timestamp.from(subscription.currentPeriodEnd()),
                subscription.trialEndsAt().map(Timestamp::from).orElse(null),
                subscription.cancelAtPeriodEnd(),
                subscription.canceledAt().map(Timestamp::from).orElse(null),
                subscription.pausedAt().map(Timestamp::from).orElse(null),
                subscription.version(),
                PricingJsonMapper.toJson(subscription),
                Timestamp.from(subscription.currentPeriodStart()));
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException(
                "Subscription " + subscription.subscriptionId() + " already exists", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Optimistic locking on {@code version}. Every model transition advances the version by
     * exactly one, so the row is updated only when it still holds the immediately preceding version:
     * two workers that both read version N (a renewal sweep and a lifecycle call, or two retries of
     * the same job) cannot both write - the loser gets a conflict and re-reads instead of silently
     * overwriting the winner's transition and losing its outbox event.
     */
    @Override
    @Transactional
    public void update(Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription cannot be null");
        if (find(subscription.tenantId(), subscription.subscriptionId()).isEmpty()) {
            throw new IllegalStateException(
                "Unknown subscription " + subscription.subscriptionId()
                    + " for tenant " + subscription.tenantId().value());
        }
        long expectedPreviousVersion = subscription.version() - 1;
        if (expectedPreviousVersion < 0) {
            throw new IllegalStateException(
                "A subscription update must advance the version; got version "
                    + subscription.version() + " for " + subscription.subscriptionId());
        }
        int updated = jdbcTemplate.update("""
            UPDATE subscriptions SET
                plan_code = ?, status = ?, current_period_start = ?, current_period_end = ?,
                trial_ends_at = ?, cancel_at_period_end = ?, canceled_at = ?, paused_at = ?,
                version = ?, payload_json = ?, updated_at = ?
            WHERE subscription_id = ? AND tenant_id = ? AND version = ?
            """,
            subscription.planCode().value(), subscription.status().name(),
            Timestamp.from(subscription.currentPeriodStart()),
            Timestamp.from(subscription.currentPeriodEnd()),
            subscription.trialEndsAt().map(Timestamp::from).orElse(null),
            subscription.cancelAtPeriodEnd(),
            subscription.canceledAt().map(Timestamp::from).orElse(null),
            subscription.pausedAt().map(Timestamp::from).orElse(null),
            subscription.version(),
            PricingJsonMapper.toJson(subscription),
            Timestamp.from(subscription.currentPeriodStart()),
            subscription.subscriptionId(), subscription.tenantId().value(),
            expectedPreviousVersion);

        if (updated == 0) {
            throw new IllegalStateException(
                "Subscription " + subscription.subscriptionId() + " was modified concurrently or does"
                    + " not exist for tenant " + subscription.tenantId().value()
                    + " (expected version " + expectedPreviousVersion + ")");
        }
    }

    @Override
    public Optional<Subscription> find(TenantId tenantId, String subscriptionId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM subscriptions WHERE subscription_id = ? AND tenant_id = ?",
            (rs, rowNum) -> read(rs), subscriptionId, tenantId.value())
            .stream().findFirst();
    }

    @Override
    public List<Subscription> findByCustomer(TenantId tenantId, CustomerId customerId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM subscriptions WHERE tenant_id = ? AND customer_id = ? ORDER BY subscription_id",
            (rs, rowNum) -> read(rs), tenantId.value(), customerId.value());
    }

    @Override
    public List<Subscription> findDueForRenewal(TenantId tenantId, Instant at) {
        // Terminal rows are excluded in the query, not only in Java: a cancelled subscription that
        // renews itself back to life is the exact failure the absorbing states exist to prevent.
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM subscriptions WHERE tenant_id = ? AND current_period_end <= ?"
                + " AND status NOT IN ('CANCELED', 'EXPIRED') ORDER BY current_period_end",
            (rs, rowNum) -> read(rs), tenantId.value(), Timestamp.from(at));
    }

    private static Subscription read(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp trial = rs.getTimestamp("trial_ends_at");
        Timestamp canceled = rs.getTimestamp("canceled_at");
        Timestamp paused = rs.getTimestamp("paused_at");
        return new Subscription(
            rs.getString("subscription_id"),
            TenantId.of(rs.getString("tenant_id")),
            CustomerId.of(rs.getString("customer_id")),
            PlanCode.of(rs.getString("plan_code")),
            Subscription.Status.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("current_period_start").toInstant(),
            rs.getTimestamp("current_period_end").toInstant(),
            Optional.ofNullable(trial).map(Timestamp::toInstant),
            rs.getBoolean("cancel_at_period_end"),
            Optional.ofNullable(canceled).map(Timestamp::toInstant),
            Optional.ofNullable(paused).map(Timestamp::toInstant),
            rs.getLong("version"));
    }
}