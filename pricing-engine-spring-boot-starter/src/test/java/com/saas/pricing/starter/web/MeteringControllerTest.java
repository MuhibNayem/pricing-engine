package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import com.saas.pricing.starter.web.dto.PricingDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PricingEngineAutoConfiguration.class)
class MeteringControllerTest {

    @Autowired
    private MeteringController controller;

    @Autowired
    private RateCardRepository rateCardRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Test
    @DisplayName("Should ingest single and batch events via controller")
    void testIngestEvent() {
        var eventDto = new PricingDtos.MeterEventDto(
            "evt_ctrl_1",
            "key_ctrl_1",
            "tenant_ctrl",
            "cust_ctrl",
            "API_CALLS",
            BigDecimal.valueOf(5),
            "2026-10-08T12:00:00Z",
            Map.of("region", "us-east")
        );

        ResponseEntity<IngestionResult> response = controller.ingestEvent(eventDto);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isAccepted()).isTrue();

        // Duplicate rejection
        ResponseEntity<IngestionResult> dupResponse = controller.ingestEvent(eventDto);
        assertThat(dupResponse.getBody()).isNotNull();
        assertThat(dupResponse.getBody().isDuplicate()).isTrue();

        // Batch ingestion
        var eventDto2 = new PricingDtos.MeterEventDto(
            "evt_ctrl_2",
            "key_ctrl_2",
            "tenant_ctrl",
            "cust_ctrl",
            "API_CALLS",
            BigDecimal.valueOf(10),
            "2026-10-08T12:05:00Z",
            Map.of()
        );
        ResponseEntity<List<IngestionResult>> batchResponse = controller.ingestBatch(List.of(eventDto2));
        assertThat(batchResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(batchResponse.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("Should query aggregations via controller")
    void testGetAggregation() {
        var eventDto = new PricingDtos.MeterEventDto(
            "evt_agg_1",
            "key_agg_1",
            "tenant_agg",
            "cust_agg",
            "STORAGE_GB",
            BigDecimal.valueOf(100),
            "2026-10-08T10:15:00Z",
            Map.of()
        );
        controller.ingestEvent(eventDto);

        var aggResponse = controller.getAggregation(
            "tenant_agg",
            "cust_agg",
            "STORAGE_GB",
            "2026-10-08T10:00:00Z",
            "2026-10-08T11:00:00Z"
        );

        assertThat(aggResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(aggResponse.getBody()).isNotNull();
        assertThat(aggResponse.getBody().aggregatedValue()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("Should ingest, aggregate, rate, and draw down wallet end-to-end via controller")
    void testRateAndDrawdown() {
        TenantId tenantId = TenantId.of("tenant_draw");
        CustomerId customerId = CustomerId.of("cust_draw");
        PlanCode planCode = PlanCode.of("DRAW_PLAN");
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        // 1. Setup rate card
        rateCardRepository.save(RateCard.of(
            "rc_draw",
            tenantId,
            planCode,
            1,
            now,
            List.of(RatePlanItem.of("API_CALLS", "Calls", PricingModel.PerUnitModel.of(new BigDecimal("0.50")), CurrencyUnit.USD))
        ));

        // 2. Setup wallet with credits
        CreditGrant grant = CreditGrant.prepaid("grant_draw", "wal_draw", "Prepaid", new BigDecimal("100"), BigDecimal.ONE, now);
        walletRepository.save(Wallet.of("wal_draw", tenantId, customerId, CurrencyUnit.USD, List.of(grant)));

        // 3. Ingest usage event: 20 API calls -> 20 * $0.50 = $10.00
        controller.ingestEvent(new PricingDtos.MeterEventDto(
            "evt_d_1",
            "key_d_1",
            "tenant_draw",
            "cust_draw",
            "API_CALLS",
            BigDecimal.valueOf(20),
            "2026-10-08T05:00:00Z",
            Map.of()
        ));

        // 4. Rate and drawdown
        var drawdownReq = new PricingDtos.MeterRateAndDrawdownRequestDto(
            "tenant_draw",
            "cust_draw",
            "DRAW_PLAN",
            "USD",
            "2026-10-08T00:00:00Z",
            "2026-10-08T12:00:00Z"
        );

        ResponseEntity<PricingDtos.WalletDrawdownResponseDto> response = controller.rateAndDrawdown(drawdownReq);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().walletId()).isEqualTo("wal_draw");
        assertThat(response.getBody().totalCreditsDrawn()).isEqualByComparingTo("10.00");
        assertThat(response.getBody().fullyCovered()).isTrue();
    }
}
