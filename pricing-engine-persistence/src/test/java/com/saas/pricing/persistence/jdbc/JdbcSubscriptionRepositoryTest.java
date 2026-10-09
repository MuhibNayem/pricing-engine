package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.subscription.Subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subscription persistence.
 *
 * <p>The property that matters most is the terminal-state one: a cancelled subscription must still
 * be cancelled after a reload. A row that loses its terminal state brings a customer back onto a
 * plan they stopped paying for, and a credit note already issued can never be reconciled.
 *
 * <p>Note on coverage: migration V15's PostgreSQL guards are not exercised here because the H2 build
 * used by this suite cannot execute plpgsql.
 */
class JdbcSubscriptionRepositoryTest extends BaseJdbcRepositoryTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-31T00:00:00Z");

    private JdbcSubscriptionRepository repository() {
        return new JdbcSubscriptionRepository(jdbcTemplate);
    }

    private static Subscription active(String id) {
        return Subscription.active(id, TENANT, CUSTOMER, PLAN, START, END, START);
    }

    @Test
    @DisplayName("a subscription round-trips with every field intact")
    void roundTrips() {
        var repo = repository();
        repo.create(active("sub-1"));

        var loaded = repo.find(TENANT, "sub-1").orElseThrow();

        assertThat(loaded.subscriptionId()).isEqualTo("sub-1");
        assertThat(loaded.status()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(loaded.planCode()).isEqualTo(PLAN);
        assertThat(loaded.currentPeriodStart()).isEqualTo(START);
        assertThat(loaded.currentPeriodEnd()).isEqualTo(END);
        assertThat(loaded.trialEndsAt()).isEmpty();
        assertThat(loaded.canceledAt()).isEmpty();
    }

    @Test
    @DisplayName("a cancelled subscription stays cancelled across a reload")
    void terminalStateSurvivesReload() {
        var repo = repository();
        repo.create(active("sub-c"));
        repo.update(active("sub-c").cancelImmediately(START.plusSeconds(3600)));

        var reloaded = repo.find(TENANT, "sub-c").orElseThrow();

        assertThat(reloaded.status()).isEqualTo(Subscription.Status.CANCELED);
        assertThat(reloaded.canceledAt()).contains(START.plusSeconds(3600));
        assertThatThrownBy(reloaded::resume)
            .as("a restarted process must not resurrect a cancelled subscription")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a trial keeps its end date through a reload")
    void trialRoundTrips() {
        var repo = repository();
        repo.create(Subscription.trialing("sub-t", TENANT, CUSTOMER, PLAN, START, END, END, START));

        var loaded = repo.find(TENANT, "sub-t").orElseThrow();

        assertThat(loaded.status()).isEqualTo(Subscription.Status.TRIALING);
        assertThat(loaded.trialEndsAt()).contains(END);
    }

    @Test
    @DisplayName("creating the same subscription twice is refused")
    void duplicateCreateRefused() {
        var repo = repository();
        repo.create(active("sub-d"));

        assertThatThrownBy(() -> repo.create(active("sub-d")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("updating an unknown subscription is refused rather than silently inserting")
    void unknownUpdateRefused() {
        assertThatThrownBy(() -> repository().update(active("missing")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unknown subscription");
    }

    @Test
    @DisplayName("terminal subscriptions are excluded from renewal")
    void terminalExcludedFromRenewal() {
        var repo = repository();
        repo.create(active("sub-live"));
        repo.create(active("sub-dead").cancelImmediately(START));

        var due = repo.findDueForRenewal(TENANT, END);

        assertThat(due).extracting(Subscription::subscriptionId).containsExactly("sub-live");
    }

    @Test
    @DisplayName("the schema refuses a live row carrying a cancellation timestamp")
    void schemaRejectsInconsistentState() {
        // Bypassing the model must not let an inconsistent row into the table.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO subscriptions (
                subscription_id, tenant_id, customer_id, plan_code, status, created_at,
                current_period_start, current_period_end, cancel_at_period_end, canceled_at
            ) VALUES ('bad', 't1', 'c1', 'PRO', 'ACTIVE', ?, ?, ?, FALSE, ?)
            """,
            java.sql.Timestamp.from(START), java.sql.Timestamp.from(START),
            java.sql.Timestamp.from(END), java.sql.Timestamp.from(START)))
            .isInstanceOf(Exception.class);
    }

    /**
     * The version is the sequence number for this subscription's outbox events, so it has to
     * survive a round-trip exactly. A version read back as zero would make every transition after
     * the first collide with the events already queued, and the outbox would discard them.
     */
    @Test
    @DisplayName("the transition version survives a round-trip and advances on update")
    void versionRoundTrips() {
        var repo = repository();
        repo.create(active("sub-ver"));

        assertThat(repo.find(TENANT, "sub-ver").orElseThrow().version()).isZero();

        repo.update(repo.find(TENANT, "sub-ver").orElseThrow().pause(Instant.parse("2026-10-16T00:00:00Z")));
        assertThat(repo.find(TENANT, "sub-ver").orElseThrow().version())
            .as("a paused subscription must report a transition that actually happened")
            .isEqualTo(1);

        repo.update(repo.find(TENANT, "sub-ver").orElseThrow().resume());
        assertThat(repo.find(TENANT, "sub-ver").orElseThrow().version()).isEqualTo(2);
    }

    /** Rows written before V18 defaulted the column to 0 and must still be usable. */
    @Test
    @DisplayName("a subscription written without a version is readable and updatable")
    void preExistingRowsDefaultToZero() {
        var repo = repository();
        repo.create(active("sub-legacy"));

        jdbcTemplate.update("UPDATE subscriptions SET version = 0 WHERE subscription_id = ?",
            "sub-legacy");

        assertThat(repo.find(TENANT, "sub-legacy").orElseThrow().version()).isZero();
        repo.update(repo.find(TENANT, "sub-legacy").orElseThrow().markPastDue());
        assertThat(repo.find(TENANT, "sub-legacy").orElseThrow().version()).isEqualTo(1);
    }

    /** The V18 check constraint must reject a negative version, like every other invariant. */
    @Test
    @DisplayName("the schema refuses a negative version")
    void schemaRejectsNegativeVersion() {
        var repo = repository();
        repo.create(active("sub-neg"));

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE subscriptions SET version = -1 WHERE subscription_id = ?", "sub-neg"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * The optimistic lock. Two writers that read the same version - a renewal sweep and a lifecycle
     * call, or two retries of the same job - must not both write: the loser has to see a conflict
     * and re-read, rather than silently overwriting the winner's transition and dropping its outbox
     * event.
     */
    @Test
    @DisplayName("a stale update loses the optimistic-lock race instead of overwriting")
    void staleUpdateIsRejected() {
        var repo = repository();
        repo.create(active("sub-race"));
        var base = repo.find(TENANT, "sub-race").orElseThrow();

        var first = base.pause(Instant.parse("2026-10-16T00:00:00Z"));
        var second = base.pause(Instant.parse("2026-10-17T00:00:00Z"));

        repo.update(first);

        assertThatThrownBy(() -> repo.update(second))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("concurrently");
        assertThat(repo.find(TENANT, "sub-race").orElseThrow().version())
            .as("the winner's transition stands and the loser changes nothing")
            .isEqualTo(1);
    }
}
