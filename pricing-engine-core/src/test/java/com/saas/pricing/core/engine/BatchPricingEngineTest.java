package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.TaxProvider;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchPricingEngineTest {

    @Test
    @DisplayName("Should evaluate large batch of pricing requests concurrently using Virtual Threads")
    void testBatchEvaluation() {
        InMemoryRateCardRepository repo = new InMemoryRateCardRepository();
        Instant now = Instant.now();

        var item = RatePlanItem.of("API", "api", PricingModel.PerUnitModel.of(BigDecimal.valueOf(0.01)), CurrencyUnit.USD);
        var rateCard = RateCard.of("rc_api", TenantId.of("tenant_batch"), PlanCode.of("DEV"), 1, now, List.of(item));
        repo.save(rateCard);

        DefaultPricingEngine engine = new DefaultPricingEngine(
            repo,
            (from, to, time) -> BigDecimal.ONE,
            TaxProvider.noOp(),
            AuditSink.noOp(),
            null
        );

        List<PricingRequest> batch = new ArrayList<>();
        int count = 50;
        for (int i = 1; i <= count; i++) {
            batch.add(PricingRequest.builder()
                .tenantId("tenant_batch")
                .planCode("DEV")
                .evaluationTime(now)
                .item("API", i * 100)
                .build());
        }

        try (BatchPricingEngine batchEngine = new BatchPricingEngine(engine)) {
            List<PricingResult> results = batchEngine.evaluateBatch(batch);

            assertThat(results).hasSize(count);
            // Verify item 1 (100 units * 0.01 = $1.00)
            assertThat(results.get(0).finalTotal().amount()).isEqualByComparingTo("1.00");
            // Verify item 50 (5000 units * 0.01 = $50.00)
            assertThat(results.get(count - 1).finalTotal().amount()).isEqualByComparingTo("50.00");
        }
    }
}
