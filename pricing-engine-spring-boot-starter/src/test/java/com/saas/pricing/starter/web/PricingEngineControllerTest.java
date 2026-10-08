package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import com.saas.pricing.starter.repository.InMemoryRateCardRepository;
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
class PricingEngineControllerTest {

    @Autowired
    private PricingEngineController controller;

    @Autowired
    private InMemoryRateCardRepository rateCardRepo;

    @Test
    @DisplayName("Should return UP status on health check")
    void testHealthCheck() {
        ResponseEntity<Map<String, String>> response = controller.health();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("Should evaluate pricing via REST DTO request")
    void testEvaluateDto() {
        Instant now = Instant.now();
        var item = RatePlanItem.of("SEATS", "seats", PricingModel.PerUnitModel.of(BigDecimal.valueOf(25)), CurrencyUnit.USD);
        var rateCard = RateCard.of("rc_team", TenantId.of("tenant_rest"), PlanCode.of("TEAM"), 1, now, List.of(item));
        rateCardRepo.save(rateCard);

        var requestDto = new PricingDtos.PricingEvaluationRequestDto(
            "tenant_rest",
            "cust_1",
            "TEAM",
            "USD",
            List.of(new PricingDtos.ItemDto("SEATS", BigDecimal.valueOf(4), Map.of())),
            List.of(),
            Map.of()
        );

        var response = controller.evaluate(requestDto);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        // 4 seats * $25 = $100.00 -> Money.toString() strips trailing zeros -> "100 USD"
        assertThat(response.getBody().finalTotal()).isEqualTo("100 USD");
    }
}
