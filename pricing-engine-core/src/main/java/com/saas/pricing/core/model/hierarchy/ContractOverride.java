package com.saas.pricing.core.model.hierarchy;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.SpendCommitment;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable bi-temporal representation of a customer-specific negotiated contract override.
 * Stores custom rates, discounts, and minimum spend commitments that supersede account defaults.
 */
public record ContractOverride(
    String contractId,
    TenantId tenantId,
    CustomerId customerId,
    PlanCode planCode,
    int version,
    Instant effectiveFrom,
    Optional<Instant> effectiveTo,
    Instant recordedAt,
    Optional<Instant> supersededAt,
    List<RatePlanItem> overriddenItems,
    List<Discount> customDiscounts,
    Optional<SpendCommitment> spendCommitment,
    Map<String, String> metadata
) implements Serializable {

    public ContractOverride {
        Objects.requireNonNull(contractId, "contractId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(effectiveTo, "effectiveTo cannot be null");
        Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
        Objects.requireNonNull(supersededAt, "supersededAt cannot be null");
        Objects.requireNonNull(overriddenItems, "overriddenItems cannot be null");
        Objects.requireNonNull(customDiscounts, "customDiscounts cannot be null");
        Objects.requireNonNull(spendCommitment, "spendCommitment cannot be null");
        Objects.requireNonNull(metadata, "metadata cannot be null");

        overriddenItems = List.copyOf(overriddenItems);
        customDiscounts = List.copyOf(customDiscounts);
        metadata = Map.copyOf(metadata);
    }

    public static ContractOverride of(
        String contractId,
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        int version,
        Instant effectiveFrom,
        List<RatePlanItem> overriddenItems
    ) {
        return new ContractOverride(
            contractId, tenantId, customerId, planCode, version,
            effectiveFrom, Optional.empty(), Instant.now(), Optional.empty(),
            overriddenItems, List.of(), Optional.empty(), Map.of()
        );
    }

    public static ContractOverride of(
        String contractId,
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        int version,
        Instant effectiveFrom,
        Optional<Instant> effectiveTo,
        List<RatePlanItem> overriddenItems,
        List<Discount> customDiscounts,
        Optional<SpendCommitment> spendCommitment
    ) {
        return new ContractOverride(
            contractId, tenantId, customerId, planCode, version,
            effectiveFrom, effectiveTo, Instant.now(), Optional.empty(),
            overriddenItems, customDiscounts, spendCommitment, Map.of()
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

    public Optional<RatePlanItem> findOverriddenItem(String itemCode) {
        return overriddenItems.stream()
            .filter(item -> item.itemCode().equalsIgnoreCase(itemCode))
            .findFirst();
    }
}
