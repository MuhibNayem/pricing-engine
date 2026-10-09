package com.saas.pricing.core.model.entitlement;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares derived entitlement state against stored state and explains any divergence.
 *
 * <h2>Why this exists</h2>
 * The engine now keeps an append-only {@link EntitlementEvent} stream, and
 * {@link EntitlementStateProjection} derives current state from it. Alongside that, the legacy
 * {@link com.saas.pricing.core.model.entitlement.CustomerEntitlement} rows still hold mutable
 * {@code currentUsage} counters updated in place by {@code recordUsage(...)}.
 *
 * <p>That is two sources of truth. Rather than ripping out the mutable path — which would break every
 * deployment using it — this makes the divergence <em>visible and classified</em>, so an operator can
 * see exactly which features disagree and by how much before deciding what to do.
 *
 * <h2>Direction matters</h2>
 * The classification is not cosmetic. A feature the projection says is active but the store denies
 * means a customer is being refused access they paid for — a support incident and potentially a
 * billing dispute. The opposite direction means the customer is being granted access that should
 * have been revoked, which is the worse of the two from a security standpoint. Both are surfaced;
 * they are not treated as equally bad.
 */
public final class EntitlementReconciler {

    /** What kind of divergence was found for one feature. */
    public enum DriftType {
        /** Derived and stored agree. */
        NONE,
        /** Projection says active, store says not — the customer is wrongly denied. */
        WRONGLY_DENIED,
        /** Projection says not active, store says active — access that should have been revoked. */
        WRONGLY_GRANTED,
        /** Both active, but the usage counters disagree. */
        USAGE_MISMATCH
    }

    /** What an operator should do about a divergence. */
    public enum Remediation {
        /** Nothing to do. */
        NONE,
        /** Rebuild the stored row from the event stream. */
        REBUILD_FROM_STREAM,
        /** Investigate a missing event: the stream may not have been written. */
        INVESTIGATE_MISSING_EVENT,
        /** Investigate an unexpected revocation recorded without an event. */
        INVESTIGATE_UNRECORDED_REVOCATION,
        /** Usage counters disagree; replay the usage window onto the stored row. */
        REPLAY_USAGE
    }

    /**
     * The verdict for one feature.
     *
     * @param featureKey       the feature compared
     * @param drift            what kind of divergence was found
     * @param remediation      the recommended action
     * @param derivedQuota     quota the projection derived, if any
     * @param storedQuota      quota the store holds, if any
     * @param derivedUsage     usage the projection carries, if any
     * @param storedUsage      usage the store holds, if any
     */
    public record FeatureComparison(
        String featureKey,
        DriftType drift,
        Remediation remediation,
        Optional<BigDecimal> derivedQuota,
        Optional<BigDecimal> storedQuota,
        Optional<BigDecimal> derivedUsage,
        Optional<BigDecimal> storedUsage
    ) implements Serializable {
        public FeatureComparison {
            Objects.requireNonNull(featureKey, "featureKey cannot be null");
            Objects.requireNonNull(drift, "drift cannot be null");
            Objects.requireNonNull(remediation, "remediation cannot be null");
            derivedQuota = derivedQuota == null ? Optional.empty() : derivedQuota;
            storedQuota = storedQuota == null ? Optional.empty() : storedQuota;
            derivedUsage = derivedUsage == null ? Optional.empty() : derivedUsage;
            storedUsage = storedUsage == null ? Optional.empty() : storedUsage;
        }

        public boolean isDrifted() {
            return drift != DriftType.NONE;
        }
    }

    /** The full reconciliation result for one customer at one instant. */
    public record Report(
        TenantId tenantId,
        CustomerId customerId,
        Instant evaluatedAt,
        List<FeatureComparison> comparisons
    ) implements Serializable {
        public Report {
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(customerId, "customerId cannot be null");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt cannot be null");
            comparisons = comparisons == null ? List.of() : List.copyOf(comparisons);
        }

        public List<FeatureComparison> drifted() {
            return comparisons.stream().filter(FeatureComparison::isDrifted).toList();
        }

        public boolean isInSync() {
            return drifted().isEmpty();
        }

        /** Feature keys where the customer is wrongly denied access. */
        public List<String> wronglyDenied() {
            return comparisons.stream()
                .filter(c -> c.drift() == DriftType.WRONGLY_DENIED)
                .map(FeatureComparison::featureKey).toList();
        }

        /** Feature keys where access survives that the stream says was revoked. */
        public List<String> wronglyGranted() {
            return comparisons.stream()
                .filter(c -> c.drift() == DriftType.WRONGLY_GRANTED)
                .map(FeatureComparison::featureKey).toList();
        }

        public Map<String, String> summary() {
            Map<String, String> summary = new LinkedHashMap<>();
            comparisons.forEach(c -> summary.put(c.featureKey(),
                c.isDrifted() ? c.drift() + " -> " + c.remediation() : "in sync"));
            return summary;
        }
    }

    private EntitlementReconciler() {
        // static utility
    }

    /**
     * Reconciles the projection against the stored entitlements.
     *
     * @param derived    state derived by replaying the event stream
     * @param stored     the legacy mutable rows, keyed by feature; may be empty for a customer who
     *                   has never been written by the legacy path
     * @param at         the instant both sides were evaluated at
     */
    public static Report reconcile(TenantId tenantId, CustomerId customerId, Instant at,
                                   Map<String, EntitlementEvent.EntitlementState> derived,
                                   Map<String, CustomerEntitlement> stored) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(at, "at cannot be null");
        Objects.requireNonNull(derived, "derived must not be null");
        Objects.requireNonNull(stored, "stored must not be null");

        Map<String, FeatureComparison> byFeature = new LinkedHashMap<>();

        // Every feature either side knows about, so a feature present on only one side is not missed.
        java.util.Set<String> featureKeys = new java.util.LinkedHashSet<>(derived.keySet());
        featureKeys.addAll(stored.keySet());

        for (String featureKey : featureKeys) {
            var derivedState = derived.get(featureKey);
            CustomerEntitlement storedState = stored.get(featureKey);

            boolean derivedActive = derivedState != null && derivedState.active();
            boolean storedActive = storedState != null && activeAt(storedState, at);

            DriftType drift;
            Remediation remediation;

            if (derivedActive && !storedActive) {
                drift = DriftType.WRONGLY_DENIED;
                remediation = Remediation.REBUILD_FROM_STREAM;
            } else if (!derivedActive && storedActive) {
                drift = DriftType.WRONGLY_GRANTED;
                remediation = Remediation.INVESTIGATE_UNRECORDED_REVOCATION;
            } else if (!derivedActive && !storedActive) {
                drift = DriftType.NONE;
                remediation = Remediation.NONE;
            } else {
                // Both active: compare the numbers.
                Optional<BigDecimal> derivedUsage = derivedState == null
                    ? Optional.empty() : derivedState.currentUsage();
                Optional<BigDecimal> storedUsage = storedState == null
                    ? Optional.empty() : Optional.ofNullable(storedState.currentUsage());
                boolean usageAgrees = derivedUsage.map(d -> storedUsage.map(s -> d.compareTo(s) == 0).orElse(false))
                    .orElseGet(() -> storedUsage.isEmpty());

                drift = usageAgrees ? DriftType.NONE : DriftType.USAGE_MISMATCH;
                remediation = usageAgrees ? Remediation.NONE : Remediation.REPLAY_USAGE;
            }

            byFeature.put(featureKey, new FeatureComparison(
                featureKey, drift, remediation,
                derivedState == null ? Optional.empty() : derivedState.quotaLimit(),
                storedState == null ? Optional.empty() : storedState.quotaLimit(),
                derivedState == null ? Optional.empty() : derivedState.currentUsage(),
                storedState == null ? Optional.empty() : Optional.ofNullable(storedState.currentUsage())));
        }

        return new Report(tenantId, customerId, at, new ArrayList<>(byFeature.values()));
    }

    /** Convenience: derive from the event stream and reconcile in one call. */
    public static Report reconcile(TenantId tenantId, CustomerId customerId, Instant at,
                                   List<EntitlementEvent> events,
                                   Map<String, CustomerEntitlement> stored) {
        return reconcile(tenantId, customerId, at,
            EntitlementStateProjection.project(events, at), stored);
    }

    /**
     * Whether a stored entitlement is in force at {@code at}.
     *
     * <p>Two conditions, both required. A BOOLEAN entitlement carrying {@code value=false} is
     * simply not granted, regardless of its date window — that is different from "granted from
     * March". The window is half-open, {@code [effectiveFrom, effectiveTo)}, so an entitlement
     * ending exactly at {@code at} is no longer active, which is what makes a replay agree with
     * the store at a boundary instant.
     */
    private static boolean activeAt(CustomerEntitlement entitlement, Instant at) {
        if (!entitlement.booleanValue()) {
            return false;
        }
        if (at.isBefore(entitlement.effectiveFrom())) {
            return false;
        }
        return entitlement.effectiveTo().map(to -> at.isBefore(to)).orElse(true);
    }
}