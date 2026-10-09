package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRateCardRepositoryTest extends BaseJdbcRepositoryTest {

    private JdbcRateCardRepository repository;
    private final TenantId tenantId = TenantId.of("tenant_postgres");
    private final PlanCode planCode = PlanCode.of("ENTERPRISE_PLAN");

    @BeforeEach
    void setupRepository() {
        repository = new JdbcRateCardRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Should save and retrieve RateCard with nested polymorphic pricing models")
    void testSaveAndRetrieveRateCard() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-31T23:59:59Z");

        var tiers = List.of(
            Tier.of(BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("0.10")),
            Tier.unbounded(new BigDecimal("1000"), new BigDecimal("0.05"))
        );

        var items = List.of(
            RatePlanItem.of("BASE_FEE", "Base Fee",
                PricingModel.FlatFeeModel.of(Money.of("99.00", CurrencyUnit.USD), BillingCadence.MONTHLY),
                CurrencyUnit.USD),
            RatePlanItem.of("API_CALLS", "API Calls",
                PricingModel.GraduatedTierModel.of(tiers),
                CurrencyUnit.USD,
                new BigDecimal("500"))
        );

        RateCard card = new RateCard(
            "rc_ent_1",
            tenantId,
            planCode,
            1,
            from,
            Optional.of(to),
            from,
            Optional.empty(),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.ACCOUNT_DEFAULT,
            items,
            Map.of("category", "SaaS")
        );

        repository.save(card);

        // Effective lookup
        Optional<RateCard> retrievedOpt = repository.findEffectiveRateCard(tenantId, planCode, Instant.parse("2026-06-01T12:00:00Z"));
        assertThat(retrievedOpt).isPresent();

        RateCard retrieved = retrievedOpt.get();
        assertThat(retrieved.rateCardId()).isEqualTo("rc_ent_1");
        assertThat(retrieved.tenantId()).isEqualTo(tenantId);
        assertThat(retrieved.planCode()).isEqualTo(planCode);
        assertThat(retrieved.version()).isEqualTo(1);
        assertThat(retrieved.items()).hasSize(2);

        RatePlanItem baseItem = retrieved.findItem("BASE_FEE").orElseThrow();
        assertThat(baseItem.pricingModel()).isInstanceOf(PricingModel.FlatFeeModel.class);
        PricingModel.FlatFeeModel flat = (PricingModel.FlatFeeModel) baseItem.pricingModel();
        assertThat(flat.amount().amount()).isEqualByComparingTo("99.00");

        RatePlanItem apiItem = retrieved.findItem("API_CALLS").orElseThrow();
        assertThat(apiItem.pricingModel()).isInstanceOf(PricingModel.GraduatedTierModel.class);
        PricingModel.GraduatedTierModel grad = (PricingModel.GraduatedTierModel) apiItem.pricingModel();
        assertThat(grad.tiers()).hasSize(2);
        assertThat(apiItem.includedAllowance()).contains(new BigDecimal("500"));
    }

    @Test
    @DisplayName("Should return global rate card fallback when tenant-specific card does not exist")
    void testGlobalFallback() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        RateCard globalCard = RateCard.global(
            "rc_global_1",
            planCode,
            1,
            from,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("15.00")), CurrencyUnit.USD))
        );

        repository.save(globalCard);

        Optional<RateCard> found = repository.findEffectiveRateCard(TenantId.of("unknown_tenant"), planCode, Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(found).isPresent();
        assertThat(found.get().rateCardId()).isEqualTo("rc_global_1");
    }

    @Test
    @DisplayName("Should support bi-temporal lookup honoring recordedAt and supersededAt")
    void testBiTemporalLookup() {
        Instant effFrom = Instant.parse("2026-01-01T00:00:00Z");
        Instant sysT1 = Instant.parse("2026-02-01T00:00:00Z");
        Instant sysT2 = Instant.parse("2026-03-01T00:00:00Z");

        // Version 1 recorded at sysT1, superseded at sysT2
        RateCard v1 = new RateCard(
            "rc_v1",
            tenantId,
            planCode,
            1,
            effFrom,
            Optional.empty(),
            sysT1,
            Optional.of(sysT2),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.ACCOUNT_DEFAULT,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("10.00")), CurrencyUnit.USD)),
            Map.of()
        );

        // Version 2 recorded at sysT2, active indefinitely
        RateCard v2 = new RateCard(
            "rc_v2",
            tenantId,
            planCode,
            2,
            effFrom,
            Optional.empty(),
            sysT2,
            Optional.empty(),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.ACCOUNT_DEFAULT,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("12.00")), CurrencyUnit.USD)),
            Map.of()
        );

        repository.save(v1);
        repository.save(v2);

        // Query historical state as known at 2026-02-15 (between sysT1 and sysT2) -> should return v1 ($10.00)
        Optional<RateCard> historical = repository.findBiTemporalRateCard(
            tenantId, planCode,
            Instant.parse("2026-01-15T00:00:00Z"),
            Instant.parse("2026-02-15T00:00:00Z")
        );
        assertThat(historical).isPresent();
        assertThat(historical.get().version()).isEqualTo(1);

        // Query current state as known at 2026-03-15 -> should return v2 ($12.00)
        Optional<RateCard> current = repository.findBiTemporalRateCard(
            tenantId, planCode,
            Instant.parse("2026-01-15T00:00:00Z"),
            Instant.parse("2026-03-15T00:00:00Z")
        );
        assertThat(current).isPresent();
        assertThat(current.get().version()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should return historical global rate card when tenant card is missing during bi-temporal lookup")
    void testBiTemporalGlobalFallback() {
        PlanCode globalPlan = PlanCode.of("GLOBAL_BI_PLAN");
        Instant effFrom = Instant.parse("2026-01-01T00:00:00Z");
        Instant sysT1 = Instant.parse("2026-02-01T00:00:00Z");
        Instant sysT2 = Instant.parse("2026-03-01T00:00:00Z");

        // Global v1 recorded at sysT1, superseded at sysT2
        RateCard gv1 = new RateCard(
            "rc_gv1",
            TenantId.of("GLOBAL"),
            globalPlan,
            1,
            effFrom,
            Optional.empty(),
            sysT1,
            Optional.of(sysT2),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.GLOBAL_CATALOG,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("5.00")), CurrencyUnit.USD)),
            Map.of()
        );

        // Global v2 recorded at sysT2, active
        RateCard gv2 = new RateCard(
            "rc_gv2",
            TenantId.of("GLOBAL"),
            globalPlan,
            2,
            effFrom,
            Optional.empty(),
            sysT2,
            Optional.empty(),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.GLOBAL_CATALOG,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("7.00")), CurrencyUnit.USD)),
            Map.of()
        );

        repository.save(gv1);
        repository.save(gv2);

        // Bi-temporal lookup for unknown tenant as of sysT1 + 10 days
        Optional<RateCard> histGlobal = repository.findBiTemporalRateCard(
            TenantId.of("non_existent_tenant"),
            globalPlan,
            Instant.parse("2026-01-15T00:00:00Z"),
            Instant.parse("2026-02-10T00:00:00Z")
        );

        assertThat(histGlobal).isPresent();
        assertThat(histGlobal.get().rateCardId()).isEqualTo("rc_gv1");
        assertThat(histGlobal.get().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should find rate card at exact effectiveTo inclusive boundary instant")
    void testEffectiveToInclusiveBoundary() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");
        PlanCode boundaryPlan = PlanCode.of("BOUNDARY_PLAN");

        RateCard card = RateCard.of(
            "rc_boundary_1",
            tenantId,
            boundaryPlan,
            1,
            from,
            to,
            List.of(RatePlanItem.of("BASE", "Base", PricingModel.FlatFeeModel.of(Money.of("10.00", CurrencyUnit.USD), BillingCadence.MONTHLY), CurrencyUnit.USD))
        );

        repository.save(card);

        // Query at exact instant of `to` -> MUST be found according to domain rules
        Optional<RateCard> found = repository.findEffectiveRateCard(tenantId, boundaryPlan, to);
        assertThat(found).isPresent();
        assertThat(found.get().rateCardId()).isEqualTo("rc_boundary_1");
    }

    @Test
    @DisplayName("Should persist and deserialize complex pricing models: Composite, Hybrid, Formula, Matrix")
    void testComplexPricingModelsPersistence() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        PlanCode complexPlan = PlanCode.of("COMPLEX_PLAN");

        var subModel1 = PricingModel.FlatFeeModel.of(Money.of("50.00", CurrencyUnit.USD), BillingCadence.MONTHLY);
        var subModel2 = PricingModel.PerUnitModel.of(new BigDecimal("0.02"));
        var composite = PricingModel.CompositePricingModel.of(subModel1, subModel2);

        var hybrid = PricingModel.HybridModel.of(
            Money.of("100.00", CurrencyUnit.USD),
            new BigDecimal("1000"),
            PricingModel.PerUnitModel.of(new BigDecimal("0.05")),
            Money.of("500.00", CurrencyUnit.USD)
        );

        var formula = PricingModel.DynamicFormulaModel.of("(tokens * 0.001) + 5", "tokens");

        var steps = List.of(
            com.saas.pricing.core.model.StairStep.of(BigDecimal.ZERO, new BigDecimal("10"), Money.of("20.00", CurrencyUnit.USD)),
            com.saas.pricing.core.model.StairStep.unbounded(new BigDecimal("10"), Money.of("50.00", CurrencyUnit.USD))
        );
        var stairStep = PricingModel.StairStepModel.of(steps);

        RateCard card = RateCard.of(
            "rc_complex_1",
            tenantId,
            complexPlan,
            1,
            from,
            List.of(
                RatePlanItem.of("COMPOSITE_ITEM", "Composite", composite, CurrencyUnit.USD),
                RatePlanItem.of("HYBRID_ITEM", "Hybrid", hybrid, CurrencyUnit.USD),
                RatePlanItem.of("FORMULA_ITEM", "Formula", formula, CurrencyUnit.USD),
                RatePlanItem.of("STAIR_ITEM", "Stair", stairStep, CurrencyUnit.USD)
            )
        );

        repository.save(card);

        Optional<RateCard> retrieved = repository.findEffectiveRateCard(tenantId, complexPlan, from.plusSeconds(3600));
        assertThat(retrieved).isPresent();

        RateCard rc = retrieved.get();
        assertThat(rc.findItem("COMPOSITE_ITEM").orElseThrow().pricingModel()).isInstanceOf(PricingModel.CompositePricingModel.class);
        assertThat(rc.findItem("HYBRID_ITEM").orElseThrow().pricingModel()).isInstanceOf(PricingModel.HybridModel.class);
        assertThat(rc.findItem("FORMULA_ITEM").orElseThrow().pricingModel()).isInstanceOf(PricingModel.DynamicFormulaModel.class);
        assertThat(rc.findItem("STAIR_ITEM").orElseThrow().pricingModel()).isInstanceOf(PricingModel.StairStepModel.class);
    }

    @Test
    @DisplayName("non-temporal lookup uses system time, not the queried valid time")
    void nonTemporalLookupUsesCurrentSystemTime() {
        // Regression: superseded_at (system time) was compared against the caller's effectiveTime
        // (valid time). A card superseded now but queried for a past instant was returned, and a
        // card recorded in the future could be returned too.
        Instant effFrom = Instant.parse("2026-01-01T00:00:00Z");
        PlanCode plan = PlanCode.of("SYSTEM_TIME_PLAN");

        RateCard superseded = new RateCard(
            "rc-old", tenantId, plan, 1, effFrom, Optional.empty(),
            Instant.parse("2026-02-01T00:00:00Z"), Optional.of(Instant.parse("2026-03-01T00:00:00Z")),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.ACCOUNT_DEFAULT,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("10.00")), CurrencyUnit.USD)),
            Map.of());
        RateCard active = new RateCard(
            "rc-new", tenantId, plan, 2, effFrom, Optional.empty(),
            Instant.parse("2026-03-01T00:00:00Z"), Optional.empty(),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.ACCOUNT_DEFAULT,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("12.00")), CurrencyUnit.USD)),
            Map.of());
        RateCard notYetRecorded = new RateCard(
            "rc-future", tenantId, plan, 3, effFrom, Optional.empty(),
            Instant.parse("2027-01-01T00:00:00Z"), Optional.empty(),
            com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.ACCOUNT_DEFAULT,
            List.of(RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("99.00")), CurrencyUnit.USD)),
            Map.of());

        repository.save(superseded);
        repository.save(active);
        repository.save(notYetRecorded);

        Optional<RateCard> found = repository.findEffectiveRateCard(
            tenantId, plan, Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(found).isPresent();
        assertThat(found.get().rateCardId())
            .as("the superseded card is hidden and the not-yet-recorded card is not visible")
            .isEqualTo("rc-new");
    }
}
