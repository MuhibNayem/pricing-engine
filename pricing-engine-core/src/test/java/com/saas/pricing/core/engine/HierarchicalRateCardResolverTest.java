package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel;
import com.saas.pricing.core.model.hierarchy.ContractOverride;
import com.saas.pricing.core.model.hierarchy.ResolvedRateCardHierarchy;
import com.saas.pricing.core.spi.impl.InMemoryContractOverrideRepository;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalRateCardResolverTest {

    private final InMemoryRateCardRepository rateCardRepo = new InMemoryRateCardRepository();
    private final InMemoryContractOverrideRepository contractRepo = new InMemoryContractOverrideRepository();
    private final HierarchicalRateCardResolver resolver = new HierarchicalRateCardResolver(rateCardRepo, contractRepo);
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Should resolve Negotiated Contract Override when customer override exists")
    void testContractOverrideResolution() {
        // Base rate card ($10/seat)
        var standardItem = RatePlanItem.of("SEAT", "seats", PricingModel.PerUnitModel.of(BigDecimal.valueOf(10)), CurrencyUnit.USD);
        var baseCard = RateCard.of("base_pro", TenantId.of("tenant_acme"), PlanCode.of("PRO"), 1, now, List.of(standardItem));
        rateCardRepo.save(baseCard);

        // Custom negotiated override for enterprise customer ($6/seat)
        var overrideItem = RatePlanItem.of("SEAT", "seats", PricingModel.PerUnitModel.of(BigDecimal.valueOf(6)), CurrencyUnit.USD);
        var override = ContractOverride.of(
            "override_vip", TenantId.of("tenant_acme"), CustomerId.of("vip_cust"),
            PlanCode.of("PRO"), 1, now, List.of(overrideItem)
        );
        contractRepo.save(override);

        ResolvedRateCardHierarchy resolved = resolver.resolve(
            TenantId.of("tenant_acme"), Optional.of(CustomerId.of("vip_cust")),
            PlanCode.of("PRO"), now, Optional.empty()
        );

        assertThat(resolved.sourceHierarchyLevel()).isEqualTo(CatalogHierarchyLevel.CONTRACT_OVERRIDE);
        assertThat(resolved.effectiveRateCard().findItem("SEAT")).isPresent();

        // Check overridden price is $6 instead of $10
        PricingModel.PerUnitModel model = (PricingModel.PerUnitModel) resolved.effectiveRateCard().findItem("SEAT").get().pricingModel();
        assertThat(model.unitPrice()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("Should fallback to Account Default Rate Card when customer has no contract override")
    void testTenantDefaultResolution() {
        var standardItem = RatePlanItem.of("SEAT", "seats", PricingModel.PerUnitModel.of(BigDecimal.valueOf(10)), CurrencyUnit.USD);
        var baseCard = RateCard.of("base_pro", TenantId.of("tenant_acme"), PlanCode.of("PRO"), 1, now, List.of(standardItem));
        rateCardRepo.save(baseCard);

        ResolvedRateCardHierarchy resolved = resolver.resolve(
            TenantId.of("tenant_acme"), Optional.of(CustomerId.of("regular_cust")),
            PlanCode.of("PRO"), now, Optional.empty()
        );

        assertThat(resolved.sourceHierarchyLevel()).isEqualTo(CatalogHierarchyLevel.ACCOUNT_DEFAULT);
        PricingModel.PerUnitModel model = (PricingModel.PerUnitModel) resolved.effectiveRateCard().findItem("SEAT").get().pricingModel();
        assertThat(model.unitPrice()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Should fallback to Global Catalog when tenant-specific rate card is absent")
    void testGlobalCatalogFallback() {
        var globalItem = RatePlanItem.of("SEAT", "seats", PricingModel.PerUnitModel.of(BigDecimal.valueOf(15)), CurrencyUnit.USD);
        var globalCard = RateCard.global("global_pro", PlanCode.of("PRO"), 1, now, List.of(globalItem));
        rateCardRepo.save(globalCard);

        ResolvedRateCardHierarchy resolved = resolver.resolve(
            TenantId.of("new_tenant_without_custom_card"), Optional.empty(),
            PlanCode.of("PRO"), now, Optional.empty()
        );

        assertThat(resolved.sourceHierarchyLevel()).isEqualTo(CatalogHierarchyLevel.GLOBAL_CATALOG);
        PricingModel.PerUnitModel model = (PricingModel.PerUnitModel) resolved.effectiveRateCard().findItem("SEAT").get().pricingModel();
        assertThat(model.unitPrice()).isEqualByComparingTo("15");
    }
}
