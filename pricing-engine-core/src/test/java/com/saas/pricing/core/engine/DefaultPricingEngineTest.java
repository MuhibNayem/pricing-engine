package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TaxRate;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.Tier;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.TaxProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPricingEngineTest {

    @Test
    @DisplayName("End-to-end evaluation with allowance, tiered rating, line discount, invoice discount, and tax")
    void testEndToEndPricingEvaluation() {
        // 1. Setup in-memory rate card repository
        Map<String, RateCard> repoMap = new HashMap<>();
        RateCardRepository repo = new RateCardRepository() {
            @Override
            public Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
                return Optional.ofNullable(repoMap.get(tenantId.value() + ":" + planCode.value()))
                    .filter(rc -> rc.isEffectiveAt(effectiveTime));
            }

            @Override
            public void save(RateCard rateCard) {
                repoMap.put(rateCard.tenantId().value() + ":" + rateCard.planCode().value(), rateCard);
            }
        };

        // 2. Define rate card
        // Items:
        // - BASE_SUB: $49.00 flat fee
        // - SEATS: $15.00/seat with 5 included seats
        // - API_CALLS: Graduated (0-10k @ 0, 10k-50k @ 0.002, 50k+ @ 0.001)
        var baseSubItem = RatePlanItem.of(
            "BASE_SUB", "subscription",
            PricingModel.FlatFeeModel.of(Money.of("49.00", CurrencyUnit.USD), BillingCadence.MONTHLY),
            CurrencyUnit.USD
        );

        var seatsItem = RatePlanItem.of(
            "SEATS", "active_users",
            PricingModel.PerUnitModel.of(BigDecimal.valueOf(15.00)),
            CurrencyUnit.USD,
            BigDecimal.valueOf(5) // 5 included seats
        );

        var apiItem = RatePlanItem.of(
            "API_CALLS", "api_requests",
            PricingModel.GraduatedTierModel.of(
                Tier.of(BigDecimal.ZERO, BigDecimal.valueOf(10000), BigDecimal.ZERO),
                Tier.of(BigDecimal.valueOf(10000), BigDecimal.valueOf(50000), BigDecimal.valueOf(0.002)),
                Tier.unbounded(BigDecimal.valueOf(50000), BigDecimal.valueOf(0.001))
            ),
            CurrencyUnit.USD
        );

        RateCard rateCard = RateCard.of(
            "rc_pro_v1",
            TenantId.of("tenant_acme"),
            PlanCode.of("PRO_PLAN"),
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(baseSubItem, seatsItem, apiItem)
        );
        repo.save(rateCard);

        // 3. Tax Provider (10% state tax on seats and base)
        TaxProvider taxProvider = (tenantId, itemCode, attributes) -> {
            if ("BASE_SUB".equals(itemCode) || "SEATS".equals(itemCode)) {
                return List.of(TaxRate.of("VAT_10", BigDecimal.valueOf(10), "CA"));
            }
            return List.of();
        };

        // 4. Audit Sink recorder
        List<PricingResult> recordedResults = new ArrayList<>();
        AuditSink auditSink = recordedResults::add;

        // 5. Instantiate Engine
        DefaultPricingEngine engine = new DefaultPricingEngine(
            repo,
            (from, to, timestamp) -> BigDecimal.ONE,
            taxProvider,
            auditSink,
            null
        );

        // 6. Build Request:
        // 1 Base, 12 Seats (12-5=7 billable @ 15 = $105), 60,000 API calls (0 + 80 + 10 = $90)
        // Line discount: 10% on SEATS ($10.50 off -> Net seats = $94.50)
        // Invoice discount: $20.00 off total
        PricingRequest request = PricingRequest.builder()
            .tenantId("tenant_acme")
            .planCode("PRO_PLAN")
            .evaluationTime(Instant.parse("2026-10-08T12:00:00Z"))
            .targetCurrency(CurrencyUnit.USD)
            .item("BASE_SUB", 1)
            .item("SEATS", 12)
            .item("API_CALLS", 60000)
            .discount(Discount.percentageItem("PROMO_SEATS_10", BigDecimal.valueOf(10), "SEATS"))
            .discount(Discount.fixedAmount("COUPON_20", Money.of("20.00", CurrencyUnit.USD)))
            .build();

        // 7. Evaluate
        PricingResult result = engine.evaluate(request);

        // Verify Gross amounts:
        // Base: $49.00
        // Seats: $105.00
        // API: $90.00
        // Total Gross: 49 + 105 + 90 = $244.00
        assertThat(result.totalGross().amount()).isEqualByComparingTo("244.00");

        // Verify Line discounts:
        // Seats 10% off = $10.50
        // Net items before invoice discount:
        // Base: $49.00, Seats: $94.50, API: $90.00. Subtotal net = $233.50
        // Invoice discount: $20.00 off
        // Total discount: 10.50 + 20.00 = $30.50
        assertThat(result.totalDiscount().amount()).isEqualByComparingTo("30.50");
        assertThat(result.totalNet().amount()).isEqualByComparingTo("213.50");

        // Tax is due on the amount actually charged, so the $20.00 invoice discount must be
        // apportioned onto the lines BEFORE tax is assessed.
        //
        // Invoice discount apportionment (largest-remainder, weights = pre-discount nets):
        //   Base  $49.00 / $233.50 x $20.00 = $4.20
        //   Seats $94.50 / $233.50 x $20.00 = $8.09
        //   API   $90.00 / $233.50 x $20.00 = $7.71   (sums to exactly $20.00)
        // Discounted nets: Base $44.80, Seats $86.41, API $82.29
        //
        // Tax rates: 10% on BASE_SUB and SEATS; API_CALLS is untaxed by the provider.
        //   Base  $44.80 -> $4.48
        //   Seats $86.41 -> $8.64
        //   = $13.12
        //
        // The previous expectation of $14.35 taxed the pre-discount amounts ($49.00 and $94.50)
        // and therefore over-charged VAT by $1.23 on this invoice.
        assertThat(result.totalTax().amount()).isEqualByComparingTo("13.12");

        // Final total: Net ($213.50) + Tax ($13.12) = $226.62
        assertThat(result.finalTotal().amount()).isEqualByComparingTo("226.62");

        // Verify Trace & Audit sink
        assertThat(result.trace().steps()).isNotEmpty();
        assertThat(recordedResults).hasSize(1);
    }

    @Test
    @DisplayName("Should convert base currency to target currency using FX provider")
    void testMultiCurrencyConversion() {
        var eurItem = RatePlanItem.of(
            "COMPUTE", "compute_hours",
            PricingModel.PerUnitModel.of(BigDecimal.valueOf(10)),
            CurrencyUnit.EUR
        );

        RateCard rateCard = RateCard.of(
            "rc_eu_v1",
            TenantId.of("tenant_global"),
            PlanCode.of("CLOUD_PLAN"),
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(eurItem)
        );

        RateCardRepository repo = new RateCardRepository() {
            @Override
            public Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
                return Optional.of(rateCard);
            }
            @Override
            public void save(RateCard rc) {}
        };

        // 1 EUR = 1.08 USD
        CurrencyExchangeProvider fxProvider = (from, to, timestamp) -> {
            if (from.equals(CurrencyUnit.EUR) && to.equals(CurrencyUnit.USD)) {
                return new BigDecimal("1.08");
            }
            return BigDecimal.ONE;
        };

        DefaultPricingEngine engine = new DefaultPricingEngine(
            repo,
            fxProvider,
            TaxProvider.noOp(),
            AuditSink.noOp(),
            null
        );

        PricingRequest request = PricingRequest.builder()
            .tenantId("tenant_global")
            .planCode("CLOUD_PLAN")
            .targetCurrency(CurrencyUnit.USD)
            .item("COMPUTE", 5) // 5 hours * 10 EUR = 50 EUR * 1.08 = 54 USD
            .build();

        PricingResult result = engine.evaluate(request);

        assertThat(result.currency()).isEqualTo(CurrencyUnit.USD);
        assertThat(result.finalTotal().amount()).isEqualByComparingTo("54.00");
    }

    @Test
    @DisplayName("Should throw when no rate card is active at evaluation timestamp")
    void testTemporalValidityException() {
        RateCard rateCard = RateCard.of(
            "rc_future",
            TenantId.of("tenant_1"),
            PlanCode.of("PLAN_A"),
            1,
            Instant.parse("2026-12-01T00:00:00Z"), // Starts in future
            List.of(RatePlanItem.of("SEAT", "seats", PricingModel.PerUnitModel.of(BigDecimal.ONE), CurrencyUnit.USD))
        );

        RateCardRepository repo = new RateCardRepository() {
            @Override
            public Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
                return rateCard.isEffectiveAt(effectiveTime) ? Optional.of(rateCard) : Optional.empty();
            }
            @Override
            public void save(RateCard rc) {}
        };

        DefaultPricingEngine engine = new DefaultPricingEngine(
            repo,
            (from, to, timestamp) -> BigDecimal.ONE,
            TaxProvider.noOp(),
            AuditSink.noOp(),
            null
        );

        PricingRequest request = PricingRequest.builder()
            .tenantId("tenant_1")
            .planCode("PLAN_A")
            .evaluationTime(Instant.parse("2026-10-01T00:00:00Z")) // Before effectiveFrom
            .item("SEAT", 1)
            .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.evaluate(request))
            .isInstanceOf(java.util.NoSuchElementException.class)
            .hasMessageContaining("No active RateCard found");
    }
}

