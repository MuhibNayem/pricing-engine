package com.saas.pricing.core.model.entitlement;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation between the append-only stream and the legacy mutable entitlement rows.
 *
 * <p>Two sources of truth exist during the migration. These tests pin the behaviour that makes that
 * tolerable: every divergence is detected, and it is classified by direction, because "denied
 * access the customer paid for" and "access that should have been revoked" are not equally bad.
 */
class EntitlementReconcilerTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    private EntitlementEvent grant(String id, String featureKey) {
        return EntitlementEvent.grant(id, TENANT, CUSTOMER, featureKey, FeatureType.BOOLEAN,
            java.util.Optional.empty(), JAN, JAN, "contract");
    }

    private CustomerEntitlement stored(String featureKey, boolean active) {
        return CustomerEntitlement.booleanEntitlement("ent-" + featureKey, TENANT, CUSTOMER, PLAN,
            featureKey, active, active ? JAN : JUN);
    }

    private static CustomerEntitlement storedWithUsage(String featureKey, BigDecimal usage) {
        return new CustomerEntitlement("ent-" + featureKey, TENANT, CUSTOMER, PLAN, featureKey,
            FeatureType.METERED_STATIC, true, java.util.Optional.of(new BigDecimal("1000")),
            usage, true, JAN, java.util.Optional.empty());
    }

    @Nested
    @DisplayName("Agreement")
    class Agreement {

        @Test
        @DisplayName("matching state reports no drift")
        void inSync() {
            var report = EntitlementReconciler.reconcile(TENANT, CUSTOMER, NOW,
                List.of(grant("e1", "A"), grant("e2", "B")),
                Map.of("A", stored("A", true), "B", stored("B", true)));

            assertThat(report.isInSync()).isTrue();
            assertThat(report.drifted()).isEmpty();
        }

        @Test
        @DisplayName("a feature unknown to both sides is not drift")
        void absentOnBothSides() {
            var report = EntitlementReconciler.reconcile(TENANT, CUSTOMER, NOW,
                List.of(), Map.of());

            assertThat(report.isInSync()).isTrue();
            assertThat(report.comparisons()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Divergence")
    class Divergence {

        @Test
        @DisplayName("a customer denied access the stream says they hold is WRONGLY_DENIED")
        void wronglyDenied() {
            var report = EntitlementReconciler.reconcile(TENANT, CUSTOMER, NOW,
                List.of(grant("e1", "A")), Map.of("A", stored("A", false)));

            assertThat(report.wronglyDenied()).containsExactly("A");
            assertThat(report.drifted()).singleElement().satisfies(drift -> {
                assertThat(drift.drift()).isEqualTo(EntitlementReconciler.DriftType.WRONGLY_DENIED);
                assertThat(drift.remediation())
                    .isEqualTo(EntitlementReconciler.Remediation.REBUILD_FROM_STREAM);
            });
        }

        @Test
        @DisplayName("access surviving a recorded revocation is WRONGLY_GRANTED")
        void wronglyGranted() {
            var previous = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
                java.util.Optional.empty(), java.util.Optional.of(BigDecimal.ZERO), JAN);
            var events = List.of(grant("e1", "A"),
                EntitlementEvent.revoke("e2", TENANT, CUSTOMER, "A", JUN, JUN, "plan ended", previous));

            var report = EntitlementReconciler.reconcile(TENANT, CUSTOMER, NOW, events,
                Map.of("A", stored("A", true)));

            assertThat(report.wronglyGranted())
                .as("access that outlived its revocation is the security-relevant direction")
                .containsExactly("A");
            assertThat(report.drifted()).singleElement().satisfies(drift ->
                assertThat(drift.remediation())
                    .isEqualTo(EntitlementReconciler.Remediation.INVESTIGATE_UNRECORDED_REVOCATION));
        }

        @Test
        @DisplayName("a feature on only one side is still compared")
        void oneSidedFeature() {
            var report = EntitlementReconciler.reconcile(TENANT, CUSTOMER, NOW,
                List.of(grant("e1", "ONLY_IN_STREAM")),
                Map.of("ONLY_IN_STORE", stored("ONLY_IN_STORE", true)));

            assertThat(report.drifted()).hasSize(2);
            assertThat(report.wronglyDenied()).containsExactly("ONLY_IN_STREAM");
            assertThat(report.wronglyGranted()).containsExactly("ONLY_IN_STORE");
        }

        @Test
        @DisplayName("disagreeing usage counters are flagged without touching access")
        void usageMismatch() {
            var events = List.of(EntitlementEvent.grant("e1", TENANT, CUSTOMER, "API",
                FeatureType.METERED_STATIC, java.util.Optional.of(new BigDecimal("1000")), JAN, JAN, "contract"));

            var report = EntitlementReconciler.reconcile(TENANT, CUSTOMER, NOW, events,
                Map.of("API", storedWithUsage("API", new BigDecimal("42"))));

            assertThat(report.drifted()).singleElement().satisfies(drift -> {
                assertThat(drift.drift()).isEqualTo(EntitlementReconciler.DriftType.USAGE_MISMATCH);
                assertThat(drift.remediation()).isEqualTo(EntitlementReconciler.Remediation.REPLAY_USAGE);
            });
            assertThat(report.wronglyDenied()).isEmpty();
            assertThat(report.wronglyGranted()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Operator surface")
    class Surface {

        @Test
        @DisplayName("the summary is readable without reading the objects")
        void summaryIsReadable() {
            var previous = new EntitlementEvent.EntitlementState(true, FeatureType.BOOLEAN,
                java.util.Optional.empty(), java.util.Optional.of(BigDecimal.ZERO), JAN);
            var events = List.of(
                grant("e1", "OK"),
                grant("e2", "DENIED"),
                EntitlementEvent.revoke("e3", TENANT, CUSTOMER, "LEAKED", JUN, JUN, "ended", previous));

            var report = EntitlementReconciler.reconcile(TENANT, CUSTOMER, NOW, events,
                Map.of("OK", stored("OK", true),
                       "DENIED", stored("DENIED", false),
                       "LEAKED", stored("LEAKED", true)));

            assertThat(report.summary())
                .containsEntry("OK", "in sync")
                .containsEntry("DENIED", "WRONGLY_DENIED -> REBUILD_FROM_STREAM")
                .containsEntry("LEAKED", "WRONGLY_GRANTED -> INVESTIGATE_UNRECORDED_REVOCATION");
        }
    }
}