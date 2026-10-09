package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.EntitlementEvent;
import com.saas.pricing.core.model.entitlement.EntitlementStateProjection;
import com.saas.pricing.core.model.entitlement.FeatureType;
import com.saas.pricing.persistence.jdbc.JdbcEntitlementEventRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entitlement event stream persistence.
 *
 * <p>Note on coverage: migration V9's PostgreSQL rules are not exercised here because the H2 build
 * used by this suite cannot execute plpgsql. The equivalent Java-level rules are tested below.
 */
class JdbcEntitlementEventRepositoryTest extends BaseJdbcRepositoryTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    private JdbcEntitlementEventRepository repository() {
        return new JdbcEntitlementEventRepository(jdbcTemplate);
    }

    private EntitlementEvent grant(String id, String featureKey, Instant effectiveAt) {
        return EntitlementEvent.grant(id, TENANT, CUSTOMER, featureKey, FeatureType.BOOLEAN,
            Optional.empty(), effectiveAt, effectiveAt, "contract");
    }

    @Test
    @DisplayName("events round-trip and come back in replay order")
    void roundTripsInReplayOrder() {
        var repo = repository();
        var previous = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
            Optional.empty(), Optional.of(BigDecimal.ZERO), JAN);

        repo.append(List.of(
            EntitlementEvent.revoke("e2", TENANT, CUSTOMER, "A", JUN, JUN, "downgraded", previous),
            grant("e1", "A", JAN)));

        var events = repo.findAllEvents(TENANT, CUSTOMER, Optional.empty());

        assertThat(events).hasSize(2);
        assertThat(events.getFirst().eventId())
            .as("replay order is (effectiveAt, recordedAt, eventId), not insertion order")
            .isEqualTo("e1");
        assertThat(events.getLast().type()).isEqualTo(EntitlementEvent.ChangeType.REVOKED);
        assertThat(events.getLast().previousState())
            .as("previous state survives the round trip, which is what makes redelivery detectable")
            .isPresent();
        assertThat(events.getLast().previousState().get().effectiveFrom()).isEqualTo(JAN);
    }

    @Test
    @DisplayName("a redelivered identical event is a no-op, not a conflict")
    void redeliveryIsIdempotent() {
        var repo = repository();
        var event = grant("e1", "A", JAN);

        repo.append(List.of(event));
        repo.append(List.of(event));

        assertThat(repo.findAllEvents(TENANT, CUSTOMER, Optional.empty()))
            .as("an at-least-once transport must be safe to feed from")
            .hasSize(1);
    }

    @Test
    @DisplayName("re-using an event id for different content is refused")
    void conflictingEventIdRejected() {
        var repo = repository();
        repo.append(List.of(grant("e1", "A", JAN)));

        assertThatThrownBy(() -> repo.append(List.of(
            EntitlementEvent.grant("e1", TENANT, CUSTOMER, "A", FeatureType.BOOLEAN,
                Optional.empty(), JUN, JUN, "different"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the stream can be replayed to derive state after a restart")
    void streamReplaysToState() {
        var repo = repository();
        var previous = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
            Optional.empty(), Optional.of(BigDecimal.ZERO), JAN);
        repo.append(List.of(
            grant("e1", "ANALYTICS", JAN),
            grant("e2", "API", JAN),
            EntitlementEvent.revoke("e3", TENANT, CUSTOMER, "ANALYTICS", JUN, JUN, "removed", previous)));

        // A rebuild reads only the stream - no mutable flag is consulted anywhere.
        var rebuilt = EntitlementStateProjection.project(
            repo.findAllEvents(TENANT, CUSTOMER, Optional.empty()), NOW);

        assertThat(EntitlementStateProjection.activeFeatures(rebuilt)).containsExactly("API");
    }

    @Test
    @DisplayName("a time-bounded read excludes events effective later")
    void effectiveBeforeFilters() {
        var repo = repository();
        repo.append(List.of(grant("e1", "A", JAN), grant("e2", "B", JUN)));

        assertThat(repo.findAllEvents(TENANT, CUSTOMER, Optional.of(Instant.parse("2026-02-01T00:00:00Z"))))
            .extracting(EntitlementEvent::eventId)
            .containsExactly("e1");
    }

    @Test
    @DisplayName("per-feature reads and latest-event lookup")
    void perFeatureQueries() {
        var repo = repository();
        repo.append(List.of(grant("e1", "A", JAN), grant("e2", "A", JUN), grant("e3", "B", JAN)));

        assertThat(repo.findEvents(TENANT, CUSTOMER, "A", Optional.empty()))
            .extracting(EntitlementEvent::eventId)
            .containsExactly("e1", "e2");
        assertThat(repo.findLatest(TENANT, CUSTOMER, "A")).map(EntitlementEvent::eventId).contains("e2");
        assertThat(repo.findLatest(TENANT, CUSTOMER, "MISSING")).isEmpty();
    }

    @Test
    @DisplayName("the schema refuses a revocation with no reason")
    void revocationReasonEnforcedBySchema() {
        // Enforced in the database as well as the model, so a write that bypasses the Java
        // constructor still cannot produce an unexplainable access revocation.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO entitlement_events (
                event_id, tenant_id, customer_id, feature_key, change_type, feature_type,
                effective_at, recorded_at, reason, payload_json
            ) VALUES ('bad-1', 't1', 'c1', 'F', 'REVOKED', 'BOOLEAN', ?, ?, '   ', '{}')
            """, java.sql.Timestamp.from(JAN), java.sql.Timestamp.from(JAN)))
            .isInstanceOf(Exception.class);
    }
}