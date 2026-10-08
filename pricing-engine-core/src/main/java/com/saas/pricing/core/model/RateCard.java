package com.saas.pricing.core.model;

import com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, bi-temporal versioned rate card containing all pricing components for a plan.
 */
public record RateCard(
    String rateCardId,
    TenantId tenantId,
    PlanCode planCode,
    int version,
    Instant effectiveFrom,
    Optional<Instant> effectiveTo,
    Instant recordedAt,
    Optional<Instant> supersededAt,
    CatalogHierarchyLevel hierarchyLevel,
    List<RatePlanItem> items,
    Map<String, String> metadata
) implements Serializable {

    public RateCard {
        Objects.requireNonNull(rateCardId, "rateCardId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(effectiveTo, "effectiveTo cannot be null");
        Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
        Objects.requireNonNull(supersededAt, "supersededAt cannot be null");
        Objects.requireNonNull(hierarchyLevel, "hierarchyLevel cannot be null");
        Objects.requireNonNull(items, "items cannot be null");
        Objects.requireNonNull(metadata, "metadata cannot be null");

        if (items.isEmpty()) {
            throw new IllegalArgumentException("RateCard must contain at least one item");
        }
        effectiveTo.ifPresent(to -> {
            if (to.isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("effectiveTo cannot be before effectiveFrom");
            }
        });

        items = List.copyOf(items);
        metadata = Map.copyOf(metadata);
    }

    public RateCard(
        String rateCardId,
        TenantId tenantId,
        PlanCode planCode,
        int version,
        Instant effectiveFrom,
        Optional<Instant> effectiveTo,
        List<RatePlanItem> items,
        Map<String, String> metadata
    ) {
        this(rateCardId, tenantId, planCode, version, effectiveFrom, effectiveTo, effectiveFrom, Optional.empty(), CatalogHierarchyLevel.ACCOUNT_DEFAULT, items, metadata);
    }

    public static RateCard of(
        String rateCardId,
        TenantId tenantId,
        PlanCode planCode,
        int version,
        Instant effectiveFrom,
        List<RatePlanItem> items
    ) {
        return new RateCard(rateCardId, tenantId, planCode, version, effectiveFrom, Optional.empty(), items, Map.of());
    }

    public static RateCard of(
        String rateCardId,
        TenantId tenantId,
        PlanCode planCode,
        int version,
        Instant effectiveFrom,
        Instant effectiveTo,
        List<RatePlanItem> items
    ) {
        return new RateCard(rateCardId, tenantId, planCode, version, effectiveFrom, Optional.of(effectiveTo), items, Map.of());
    }

    public static RateCard global(String rateCardId, PlanCode planCode, int version, Instant effectiveFrom, List<RatePlanItem> items) {
        return new RateCard(
            rateCardId, TenantId.of("GLOBAL"), planCode, version, effectiveFrom, Optional.empty(),
            effectiveFrom, Optional.empty(), CatalogHierarchyLevel.GLOBAL_CATALOG, items, Map.of()
        );
    }

    public boolean isEffectiveAt(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (timestamp.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo.map(to -> !timestamp.isAfter(to)).orElse(true);
    }

    public boolean isRecordValidAt(Instant systemTime) {
        Objects.requireNonNull(systemTime, "systemTime cannot be null");
        if (systemTime.isBefore(recordedAt)) {
            return false;
        }
        return supersededAt.map(sup -> systemTime.isBefore(sup)).orElse(true);
    }

    public boolean isBiTemporallyValidAt(Instant effectiveTime, Instant systemTime) {
        return isEffectiveAt(effectiveTime) && isRecordValidAt(systemTime);
    }

    public Optional<RatePlanItem> findItem(String itemCode) {
        return items.stream()
            .filter(item -> item.itemCode().equalsIgnoreCase(itemCode))
            .findFirst();
    }
}
