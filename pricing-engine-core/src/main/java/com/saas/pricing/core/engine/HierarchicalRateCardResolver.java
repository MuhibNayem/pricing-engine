package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel;
import com.saas.pricing.core.model.hierarchy.ContractOverride;
import com.saas.pricing.core.model.hierarchy.ResolvedRateCardHierarchy;
import com.saas.pricing.core.spi.ContractOverrideRepository;
import com.saas.pricing.core.spi.RateCardRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Enterprise resolver implementing the 3-tier pricing hierarchy:
 * 1. Negotiated Contract Override (Customer-specific)
 * 2. Account Default Rate Card (Tenant-specific)
 * 3. Global Catalog Fallback (System-wide)
 */
public final class HierarchicalRateCardResolver {

    private final RateCardRepository rateCardRepository;
    private final ContractOverrideRepository contractOverrideRepository;

    public HierarchicalRateCardResolver(
        RateCardRepository rateCardRepository,
        ContractOverrideRepository contractOverrideRepository
    ) {
        this.rateCardRepository = Objects.requireNonNull(rateCardRepository, "rateCardRepository cannot be null");
        this.contractOverrideRepository = contractOverrideRepository; // Optional
    }

    public ResolvedRateCardHierarchy resolve(
        TenantId tenantId,
        Optional<CustomerId> customerId,
        PlanCode planCode,
        Instant effectiveTime,
        Optional<Instant> systemTime
    ) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");
        Objects.requireNonNull(systemTime, "systemTime cannot be null");

        // 1. Check Contract Override
        Optional<ContractOverride> overrideOpt = Optional.empty();
        if (contractOverrideRepository != null && customerId.isPresent()) {
            overrideOpt = systemTime.isPresent()
                ? contractOverrideRepository.findBiTemporalOverride(tenantId, customerId.get(), planCode, effectiveTime, systemTime.get())
                : contractOverrideRepository.findEffectiveOverride(tenantId, customerId.get(), planCode, effectiveTime);
        }

        // 2. Check Account Default Rate Card
        Optional<RateCard> accountCardOpt = systemTime.isPresent()
            ? rateCardRepository.findBiTemporalRateCard(tenantId, planCode, effectiveTime, systemTime.get())
            : rateCardRepository.findEffectiveRateCard(tenantId, planCode, effectiveTime);

        // 3. Check Global Catalog Fallback
        Optional<RateCard> globalCardOpt;
        if (accountCardOpt.isEmpty()) {
            globalCardOpt = systemTime.isPresent()
                ? rateCardRepository.findBiTemporalGlobalRateCard(planCode, effectiveTime, systemTime.get())
                : rateCardRepository.findGlobalRateCard(planCode, effectiveTime);
        } else {
            globalCardOpt = Optional.empty();
        }

        RateCard baseCard = accountCardOpt.isPresent() ? accountCardOpt.get() : globalCardOpt.orElse(null);

        if (baseCard == null && overrideOpt.isEmpty()) {
            throw new NoSuchElementException(
                "No active RateCard found for tenant '%s' and plan '%s' at timestamp %s"
                    .formatted(tenantId.value(), planCode.value(), effectiveTime)
            );
        }

        if (overrideOpt.isPresent()) {
            ContractOverride override = overrideOpt.get();
            Map<String, RatePlanItem> mergedItems = new HashMap<>();

            // Seed with base card items if available
            if (baseCard != null) {
                for (RatePlanItem item : baseCard.items()) {
                    mergedItems.put(item.itemCode().toUpperCase(), item);
                }
            }

            List<String> overriddenCodes = new ArrayList<>();
            for (RatePlanItem item : override.overriddenItems()) {
                mergedItems.put(item.itemCode().toUpperCase(), item);
                overriddenCodes.add(item.itemCode());
            }

            RateCard synthesizedCard = new RateCard(
                override.contractId(),
                tenantId,
                planCode,
                override.version(),
                override.effectiveFrom(),
                override.effectiveTo(),
                override.recordedAt(),
                override.supersededAt(),
                CatalogHierarchyLevel.CONTRACT_OVERRIDE,
                new ArrayList<>(mergedItems.values()),
                override.metadata()
            );

            return new ResolvedRateCardHierarchy(
                synthesizedCard,
                CatalogHierarchyLevel.CONTRACT_OVERRIDE,
                overriddenCodes,
                override.customDiscounts(),
                override.spendCommitment()
            );
        }

        return new ResolvedRateCardHierarchy(
            baseCard,
            baseCard.hierarchyLevel(),
            List.of(),
            List.of(),
            Optional.empty()
        );
    }
}
