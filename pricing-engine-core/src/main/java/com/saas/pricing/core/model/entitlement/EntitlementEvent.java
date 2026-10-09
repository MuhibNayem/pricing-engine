package com.saas.pricing.core.model.entitlement;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable change to a customer's entitlement state.
 *
 * <h2>Why an event, not a mutable flag</h2>
 * Entitlements are <em>derived</em> state. The authoritative record is the stream of grants and
 * revocations; the current entitlement is what you get by replaying it. That distinction is what
 * makes three things possible, none of which a mutable boolean supports:
 *
 * <ul>
 *   <li><strong>Reconciliation.</strong> A customer can compare what they believe they hold against
 *       what the engine derived, and the difference is a list of events rather than an unexplained
 *       mismatch.</li>
 *   <li><strong>Restart safety.</strong> Because the stream is durable, a rebuild after a bad
 *       deploy reproduces the exact state.</li>
 *   <li><strong>Audit.</strong> "Why did this customer lose access on 3 March" is answerable.</li>
 * </ul>
 *
 * <p>The event carries {@code previous} state so a consumer can tell a genuine change from a
 * repeated delivery, which is what makes at-least-once delivery safe.
 *
 * @param eventId        unique identifier; makes redelivery idempotent
 * @param tenantId       tenant the entitlement belongs to
 * @param customerId     customer the entitlement belongs to
 * @param featureKey     stable, version-stable key the product code gates on
 * @param type           the change
 * @param featureType    BOOLEAN, METERED_RECURRING or METERED_STATIC
 * @param quotaLimit     the limit at the moment of the change, if any
 * @param effectiveAt    when the change takes effect
 * @param recordedAt     when the change was recorded; system time
 * @param previousState  the state before this event, empty when granting from nothing
 * @param reason         why the change happened; required for a revocation
 * @param metadata       free-form context for audit, never interpreted by the engine
 */
public record EntitlementEvent(
    String eventId,
    com.saas.pricing.core.model.TenantId tenantId,
    com.saas.pricing.core.model.CustomerId customerId,
    String featureKey,
    ChangeType type,
    FeatureType featureType,
    Optional<BigDecimal> quotaLimit,
    Instant effectiveAt,
    Instant recordedAt,
    Optional<EntitlementState> previousState,
    String reason,
    Map<String, String> metadata
) implements Serializable {

    /** The kind of change an event records. */
    public enum ChangeType {
        /** The entitlement becomes active. */
        GRANTED,
        /** The entitlement stops being active. */
        REVOKED,
        /** The entitlement stays active but its quota changes. */
        QUOTA_CHANGED
    }

    /** The state an entitlement held at a point in time. */
    public record EntitlementState(
        boolean active,
        FeatureType featureType,
        Optional<BigDecimal> quotaLimit,
        Optional<BigDecimal> currentUsage,
        Instant effectiveFrom
    ) implements Serializable {
        public EntitlementState {
            Objects.requireNonNull(featureType, "featureType cannot be null");
            quotaLimit = quotaLimit == null ? Optional.empty() : quotaLimit;
            currentUsage = currentUsage == null ? Optional.empty() : currentUsage;
            Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        }
    }

    public EntitlementEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(featureType, "featureType cannot be null");
        Objects.requireNonNull(quotaLimit, "quotaLimit cannot be null");
        Objects.requireNonNull(effectiveAt, "effectiveAt cannot be null");
        Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
        Objects.requireNonNull(previousState, "previousState cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);

        if (featureKey.isBlank()) {
            throw new IllegalArgumentException("featureKey cannot be blank");
        }
        // A revocation without a cause cannot be defended to a customer or an auditor.
        if (type == ChangeType.REVOKED && reason.isBlank()) {
            throw new IllegalArgumentException("An entitlement revocation must state a reason");
        }
        if (type == ChangeType.QUOTA_CHANGED && quotaLimit.isEmpty()) {
            throw new IllegalArgumentException("A QUOTA_CHANGED event must carry the new limit");
        }
    }

    public static EntitlementEvent grant(String eventId, com.saas.pricing.core.model.TenantId tenantId,
                                         com.saas.pricing.core.model.CustomerId customerId,
                                         String featureKey, FeatureType featureType,
                                         java.util.Optional<BigDecimal> quotaLimit,
                                         Instant effectiveAt, Instant recordedAt, String reason) {
        return new EntitlementEvent(eventId, tenantId, customerId, featureKey, ChangeType.GRANTED,
            featureType, quotaLimit, effectiveAt, recordedAt, Optional.empty(), reason, Map.of());
    }

    public static EntitlementEvent revoke(String eventId, com.saas.pricing.core.model.TenantId tenantId,
                                          com.saas.pricing.core.model.CustomerId customerId,
                                          String featureKey, Instant effectiveAt, Instant recordedAt,
                                          String reason, EntitlementState previous) {
        return new EntitlementEvent(eventId, tenantId, customerId, featureKey, ChangeType.REVOKED,
            previous.featureType(), Optional.of(previous.quotaLimit().orElse(BigDecimal.ZERO)),
            effectiveAt, recordedAt, Optional.of(previous), reason, Map.of());
    }

    /**
     * True when this event changes anything the consumer could act on.
     *
     * <p>A repeated delivery carries identical state and is not a change, so an at-least-once
     * consumer can drop it safely instead of double-granting or double-revoking.
     */
    public boolean isStateChange() {
        if (type == ChangeType.GRANTED) {
            return previousState.map(s -> !s.active()).orElse(true);
        }
        if (type == ChangeType.REVOKED) {
            return previousState.map(EntitlementState::active).orElse(false);
        }
        return previousState
                .map(s -> s.quotaLimit().map(previousQuota -> previousQuota.compareTo(quotaLimit.orElseThrow()) != 0)
                    .orElse(true))
                .orElse(true);
    }
}