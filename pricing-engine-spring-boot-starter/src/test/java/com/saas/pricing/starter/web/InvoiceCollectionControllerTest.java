package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.PaymentMethodType;
import com.saas.pricing.core.model.invoice.InvoiceFactory;
import com.saas.pricing.core.spi.PaymentProcessor;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.InvoiceLifecycleService;
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc HTTP-level integration tests for InvoiceCollectionController.
 *
 * <p>Validates HTTP dispatching, Bean Validation, TenantGuard cross-tenant access rejection,
 * and settlement outcome mapping (200 OK for SETTLED, 202 Accepted for PENDING).
 */
@SpringBootTest(classes = PricingEngineAutoConfiguration.class)
@Import({TestTenantConfiguration.class, InvoiceCollectionControllerTest.TestProcessorConfig.class})
class InvoiceCollectionControllerTest {

    private static final String TENANT = "t_col";
    private static final String CUSTOMER = "c_col";
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    @TestConfiguration
    static class TestProcessorConfig {
        @Bean
        public PaymentProcessor paymentProcessor() {
            return request -> {
                if (request.type() == PaymentMethodType.CARD) {
                    return PaymentProcessor.Outcome.settled("tx_settled_1", Instant.now());
                } else {
                    return PaymentProcessor.Outcome.pending("tx_pending_1");
                }
            };
        }
    }

    @Autowired
    private InvoiceCollectionController controller;

    @Autowired
    private PricingEngineExceptionHandler exceptionHandler;

    @Autowired
    private EnterprisePricingService pricingService;

    @Autowired
    private InvoiceLifecycleService lifecycleService;

    @Autowired
    private RateCardRepository rateCardRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();

        TestTenantConfiguration.actAs(TENANT);

        if (rateCardRepository.findEffectiveRateCard(TenantId.of(TENANT), PlanCode.of("PRO"), T0).isEmpty()) {
            rateCardRepository.save(RateCard.of(
                "rc_col_pro",
                TenantId.of(TENANT),
                PlanCode.of("PRO"),
                1,
                T0,
                List.of(RatePlanItem.of("SEATS", "Seats",
                    PricingModel.PerUnitModel.of(new BigDecimal("50.00")), CurrencyUnit.USD))
            ));
        }
    }

    private void seedOpenInvoice(String invoiceId) {
        var rated = pricingService.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
            .tenantId(TENANT)
            .customerId(CUSTOMER)
            .planCode("PRO")
            .evaluationTime(T0)
            .targetCurrency(CurrencyUnit.USD)
            .item("SEATS", 2)
            .build());

        lifecycleService.createDraft(InvoiceFactory.draftFrom(
            rated, invoiceId, CustomerId.of(CUSTOMER), T0, T0.plusSeconds(86400), T0, "TX_STANDARD"));
        lifecycleService.finalizeInvoice(TenantId.of(TENANT), invoiceId, "INV-" + invoiceId);
    }

    @Test
    @DisplayName("POST /invoices/{id}/collect returns 200 OK and marks PAID for immediate card settlement")
    void testCollectSettledCard() throws Exception {
        seedOpenInvoice("inv-card-1");

        String json = """
            {
              "tenantId": "t_col",
              "paymentMethodId": "pm-card-1",
              "customerId": "c_col",
              "methodType": "CARD",
              "processorRef": "tok_visa_4242",
              "country": "US"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices/inv-card-1/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome", is("SETTLED")))
            .andExpect(jsonPath("$.invoiceStatus", is("PAID")))
            .andExpect(jsonPath("$.invoiceId", is("inv-card-1")));
    }

    @Test
    @DisplayName("POST /invoices/{id}/collect returns 202 Accepted and keeps OPEN for delayed ACH debit")
    void testCollectPendingAch() throws Exception {
        seedOpenInvoice("inv-ach-1");

        String json = """
            {
              "tenantId": "t_col",
              "paymentMethodId": "pm-ach-1",
              "customerId": "c_col",
              "methodType": "ACH_DEBIT",
              "processorRef": "tok_ach_1234",
              "country": "US"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices/inv-ach-1/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.outcome", is("PENDING")))
            .andExpect(jsonPath("$.invoiceStatus", is("OPEN")))
            .andExpect(jsonPath("$.invoiceId", is("inv-ach-1")));
    }

    @Test
    @DisplayName("POST /invoices/{id}/collect returns 400 ProblemDetail when required fields are blank")
    void testCollectValidationFailed() throws Exception {
        String json = """
            {
              "tenantId": "t_col",
              "paymentMethodId": "",
              "customerId": "",
              "processorRef": ""
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices/inv-bad/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /invoices/{id}/collect returns 403 ProblemDetail on tenant mismatch")
    void testCollectTenantMismatch() throws Exception {
        String json = """
            {
              "tenantId": "t_attacker",
              "paymentMethodId": "pm-card-1",
              "customerId": "c_col",
              "methodType": "CARD",
              "processorRef": "tok_visa_4242"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices/inv-123/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("POST /invoices/{id}/collect returns 400 ProblemDetail when invoice does not exist")
    void testCollectUnknownInvoice() throws Exception {
        String json = """
            {
              "tenantId": "t_col",
              "paymentMethodId": "pm-card-1",
              "customerId": "c_col",
              "methodType": "CARD",
              "processorRef": "tok_visa_4242"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices/inv-unknown/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }
}
