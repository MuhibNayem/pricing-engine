package com.saas.pricing.core.model.hierarchy;

import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.wallet.SpendCommitment;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of hierarchical rate card resolution.
 * Contains the synthesized effective RateCard, overridden item codes, custom negotiated discounts,
 * and any applicable spend commitments.
 */
public record ResolvedRateCardHierarchy(
    RateCard effectiveRateCard,
    CatalogHierarchyLevel sourceHierarchyLevel,
    List<String> overriddenItemCodes,
    List<Discount> customDiscounts,
    Optional<SpendCommitment> spendCommitment
) implements Serializable {

    public ResolvedRateCardHierarchy {
        Objects.requireNonNull(effectiveRateCard, "effectiveRateCard cannot be null");
        Objects.requireNonNull(sourceHierarchyLevel, "sourceHierarchyLevel cannot be null");
        Objects.requireNonNull(overriddenItemCodes, "overriddenItemCodes cannot be null");
        Objects.requireNonNull(customDiscounts, "customDiscounts cannot be null");
        Objects.requireNonNull(spendCommitment, "spendCommitment cannot be null");

        overriddenItemCodes = List.copyOf(overriddenItemCodes);
        customDiscounts = List.copyOf(customDiscounts);
    }
}
