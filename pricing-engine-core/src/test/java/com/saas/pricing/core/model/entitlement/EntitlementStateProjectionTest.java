package com.saas.pricing.core.model.entitlement;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entitlement state as a projection of an event stream.
 *
 * <p>The property under test is that current state is a <em>function of history</em>. If a mutation
 * to some stored flag could change what a customer holds without an event, reconciliation would be
 * a story rather than a check.
 */
class EntitlementStateProjectionTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    private static EntitlementEvent grant(String id, String key, Instant effectiveAt) {
        return EntitlementEvent.grant(id, TENANT, CUSTOMER, key, FeatureType.BOOLEAN,
            Optional.empty(), effectiveAt, effectiveAt, "contract");
    }

    @Nested
    @DisplayName("Event construction")
    class Construction {

        @Test
        @DisplayName("a revocation without a reason is refused")
        void revocationNeedsReason() {
            var previous = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
                Optional.empty(), Optional.of(BigDecimal.ZERO), JAN);

            assertThatThrownBy(() -> EntitlementEvent.revoke("e1", TENANT, CUSTOMER, "F",
                JUN, JUN, "   ", previous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("a blank feature key is refused")
        void featureKeyRequired() {
            assertThatThrownBy(() -> EntitlementEvent.grant("e1", TENANT, CUSTOMER, "  ",
                FeatureType.BOOLEAN, Optional.empty(), JAN, JAN, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("featureKey");
        }

        @Test
        @DisplayName("a repeated delivery is identifiable as not a state change")
        void repeatedDeliveryIsNotAChange() {
            var active = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
                Optional.empty(), Optional.of(BigDecimal.ZERO), JAN);

            var alreadyGranted = new EntitlementEvent("e1", TENANT, CUSTOMER, "F",
                EntitlementEvent.ChangeType.GRANTED, FeatureType.BOOLEAN, Optional.empty(),
                JUN, JUN, Optional.of(active), "replay", Map.of());

            assertThat(alreadyGranted.isStateChange()).isFalse();
            assertThat(grant("e0", "F", JUN).isStateChange()).isTrue();
        }
    }

    @Nested
    @DisplayName("Projection")
    class Projection {

        @Test
        @DisplayName("state at an instant reflects only the events effective by then")
        void timeTravel() {
            var events = List.of(grant("e1", "ANALYTICS", JAN));

            assertThat(EntitlementStateProjection.activeFeatures(
                EntitlementStateProjection.project(events, JAN.minusSeconds(1)))).isEmpty();
            assertThat(EntitlementStateProjection.activeFeatures(
                EntitlementStateProjection.project(events, NOW))).containsExactly("ANALYTICS");
        }

        @Test
        @DisplayName("a revocation removes the entitlement from then on")
        void revocationRemoves() {
            var previous = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
                Optional.empty(), Optional.of(BigDecimal.ZERO), JAN);
            var events = List.of(
                grant("e1", "ANALYTICS", JAN),
                EntitlementEvent.revoke("e2", TENANT, CUSTOMER, "ANALYTICS", JUN, JUN, "plan downgraded", previous));

            assertThat(EntitlementStateProjection.activeFeatures(
                EntitlementStateProjection.project(events, JUN.minusSeconds(1)))).containsExactly("ANALYTICS");
            assertThat(EntitlementStateProjection.activeFeatures(
                EntitlementStateProjection.project(events, NOW))).isEmpty();
        }

        @Test
        @DisplayName("a backdated grant is ordered by effective time, not by record order")
        void backdatedGrantAppliesAtItsEffectiveDate() {
            // A grant effective in January but recorded in June must still apply to a customer who
            // asks about January, as long as the question is asked with the system time set after
            // the record was written. Ordering by recordedAt alone would get this wrong.
            var backdated = EntitlementEvent.grant("e2", TENANT, CUSTOMER, "BACKFILL",
                FeatureType.BOOLEAN, Optional.empty(), JAN.plusSeconds(60), JUN, "backfill");

            var events = List.of(grant("e1", "ANALYTICS", JAN), backdated);

            var inFebruaryAsKnownInJune = EntitlementStateProjection.project(
                events, Instant.parse("2026-02-01T00:00:00Z"), JUN);
            assertThat(EntitlementStateProjection.activeFeatures(inFebruaryAsKnownInJune))
                .as("the backfilled grant applies at its effective date, before it was recorded")
                .containsExactlyInAnyOrder("ANALYTICS", "BACKFILL");

            var inFebruaryAsKnownInFebruary = EntitlementStateProjection.project(
                events, Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
            assertThat(EntitlementStateProjection.activeFeatures(inFebruaryAsKnownInFebruary))
                .as("an event recorded after the projection instant did not exist yet")
                .containsExactly("ANALYTICS");
        }

        @Test
        @DisplayName("a quota change keeps the entitlement active with a new limit")
        void quotaChange() {
            var granted = EntitlementEvent.grant("e1", TENANT, CUSTOMER, "API",
                FeatureType.METERED_STATIC, Optional.of(new BigDecimal("100")), JAN, JAN, "contract");
            var afterGrant = new EntitlementEvent.EntitlementState(true, FeatureType.METERED_STATIC,
                Optional.of(new BigDecimal("100")), Optional.of(BigDecimal.ZERO), JAN);
            var quotaEvent = new EntitlementEvent("e2", TENANT, CUSTOMER, "API",
                EntitlementEvent.ChangeType.QUOTA_CHANGED, FeatureType.METERED_STATIC,
                Optional.of(new BigDecimal("500")), JAN.plusSeconds(60), JAN.plusSeconds(60),
                Optional.of(afterGrant), "upgrade", Map.of());

            var state = EntitlementStateProjection.projectOne(
                List.of(granted, quotaEvent), "API", NOW).orElseThrow();

            assertThat(state.active()).isTrue();
            assertThat(state.quotaLimit()).contains(new BigDecimal("500"));
        }

        @Test
        @DisplayName("a quota change alone does not grant an entitlement")
        void quotaChangeDoesNotGrant() {
            // A customer upgrading a quota they were never granted must not thereby gain access.
            // computeIfPresent means an upgrade cannot act as a back-door grant.
            var afterNothing = new EntitlementEvent.EntitlementState(true, FeatureType.METERED_STATIC,
                Optional.of(new BigDecimal("100")), Optional.of(BigDecimal.ZERO), JAN);
            var quotaOnly = new EntitlementEvent("e2", TENANT, CUSTOMER, "API",
                EntitlementEvent.ChangeType.QUOTA_CHANGED, FeatureType.METERED_STATIC,
                Optional.of(new BigDecimal("500")), JAN, JAN,
                Optional.of(afterNothing), "upgrade", Map.of());

            assertThat(EntitlementStateProjection.projectOne(List.of(quotaOnly), "API", NOW))
                .as("an upgrade is not a grant")
                .isEmpty();
        }

        @Test
        @DisplayName("replaying the same events twice gives the same answer")
        void projectionIsDeterministic() {
            var previous = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
                Optional.empty(), Optional.of(BigDecimal.ZERO), JAN);
            var events = List.of(
                grant("e1", "A", JAN),
                grant("e2", "B", JAN),
                EntitlementEvent.revoke("e3", TENANT, CUSTOMER, "A", JUN, JUN, "removed", previous));

            assertThat(EntitlementStateProjection.project(events, NOW))
                .isEqualTo(EntitlementStateProjection.project(events, NOW));
        }

        @Test
        @DisplayName("drift between believed and derived state is reported")
        void driftDetected() {
            var derived = EntitlementStateProjection.project(
                List.of(grant("e1", "A", JAN), grant("e2", "B", JAN)), NOW);

            Set<String> drift = EntitlementStateProjection.driftedFeatures(derived,
                Map.of("A", true, "B", false, "C", true));

            assertThat(drift)
                .as("B is derived-active but believed-inactive; C is believed-active but not derived")
                .containsExactlyInAnyOrder("B", "C");
        }
    }
}