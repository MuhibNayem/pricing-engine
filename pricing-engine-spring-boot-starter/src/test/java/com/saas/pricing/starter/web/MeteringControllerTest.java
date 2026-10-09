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
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc HTTP-level integration tests for MeteringController.
 *
 * <p>Validates DispatcherServlet HTTP dispatching, Jakarta Bean Validation (@Valid),
 * TenantGuard cross-tenant access rejection, RFC 9457 ProblemDetail error mapping,
 * and wallet drawdown rating flows.
 */
@SpringBootTest(classes = PricingEngineAutoConfiguration.class)
@Import(TestTenantConfiguration.class)
class MeteringControllerTest {

    @Autowired
    private MeteringController controller;

    @Autowired
    private PricingEngineExceptionHandler exceptionHandler;

    @Autowired
    private RateCardRepository rateCardRepository;

    @Autowired
    private WalletRepository walletRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();

        TestTenantConfiguration.actAs("tenant_ctrl");
    }

    @Test
    @DisplayName("POST /events accepts valid meter event via HTTP")
    void testIngestEventSuccess() throws Exception {
        String json = """
            {
              "eventId": "evt_http_1",
              "idempotencyKey": "key_http_1",
              "tenantId": "tenant_ctrl",
              "customerId": "cust_ctrl",
              "meterCode": "API_CALLS",
              "value": 5.0,
              "timestamp": "2026-10-08T12:00:00Z",
              "properties": {"region": "us-east"}
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted", is(true)))
            .andExpect(jsonPath("$.duplicate", is(false)))
            .andExpect(jsonPath("$.eventId", is("evt_http_1")));
    }

    @Test
    @DisplayName("POST /events marks duplicate when identical event is re-ingested")
    void testIngestEventDuplicate() throws Exception {
        String json = """
            {
              "eventId": "evt_dup_1",
              "idempotencyKey": "key_dup_1",
              "tenantId": "tenant_ctrl",
              "customerId": "cust_ctrl",
              "meterCode": "API_CALLS",
              "value": 10.0,
              "timestamp": "2026-10-08T12:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted", is(true)));

        // Re-post duplicate
        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted", is(false)))
            .andExpect(jsonPath("$.duplicate", is(true)));
    }

    @Test
    @DisplayName("POST /events returns 400 ProblemDetail when eventId is blank")
    void testIngestEventValidationBlankEventId() throws Exception {
        String json = """
            {
              "eventId": "",
              "tenantId": "tenant_ctrl",
              "meterCode": "API_CALLS",
              "value": 5.0
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Validation failed")))
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.errors", hasSize(1)));
    }

    @Test
    @DisplayName("POST /events returns 400 ProblemDetail when value is negative")
    void testIngestEventValidationNegativeValue() throws Exception {
        String json = """
            {
              "eventId": "evt_neg",
              "tenantId": "tenant_ctrl",
              "meterCode": "API_CALLS",
              "value": -1.5
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Validation failed")))
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /events returns 403 ProblemDetail when tenant does not match TenantGuard")
    void testIngestEventTenantMismatch() throws Exception {
        String json = """
            {
              "eventId": "evt_other",
              "tenantId": "tenant_attacker",
              "meterCode": "API_CALLS",
              "value": 5.0
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title", is("Tenant access denied")))
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("POST /events/batch accepts multiple events")
    void testIngestBatchSuccess() throws Exception {
        String json = """
            [
              {
                "eventId": "batch_1",
                "idempotencyKey": "bkey_1",
                "tenantId": "tenant_ctrl",
                "customerId": "cust_ctrl",
                "meterCode": "API_CALLS",
                "value": 10.0,
                "timestamp": "2026-10-08T12:00:00Z"
              },
              {
                "eventId": "batch_2",
                "idempotencyKey": "bkey_2",
                "tenantId": "tenant_ctrl",
                "customerId": "cust_ctrl",
                "meterCode": "API_CALLS",
                "value": 20.0,
                "timestamp": "2026-10-08T12:01:00Z"
              }
            ]
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].accepted", is(true)))
            .andExpect(jsonPath("$[1].accepted", is(true)));
    }

    @Test
    @DisplayName("POST /events/batch returns 400 ProblemDetail when empty")
    void testIngestBatchEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/pricing/meter/events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Invalid pricing request")))
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
            .andExpect(jsonPath("$.detail", containsString("cannot be empty")));
    }

    @Test
    @DisplayName("POST /events/batch returns 400 ProblemDetail when exceeding MAX_BATCH_SIZE")
    void testIngestBatchExceedsMax() throws Exception {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 10_001; i++) {
            items.add("""
                {"eventId":"e%d","tenantId":"tenant_ctrl","meterCode":"M","value":1}
                """.formatted(i));
        }
        String json = "[" + String.join(",", items) + "]";

        mockMvc.perform(post("/api/v1/pricing/meter/events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
            .andExpect(jsonPath("$.detail", containsString("exceeds the maximum")));
    }

    @Test
    @DisplayName("POST /events/batch returns 400 ProblemDetail when a batch item is invalid")
    void testIngestBatchItemValidationFailed() throws Exception {
        String json = """
            [
              {
                "eventId": "valid_1",
                "tenantId": "tenant_ctrl",
                "meterCode": "API_CALLS",
                "value": 10.0
              },
              {
                "eventId": "",
                "tenantId": "tenant_ctrl",
                "meterCode": "API_CALLS",
                "value": 20.0
              }
            ]
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("GET /aggregations returns calculated window aggregation")
    void testGetAggregationSuccess() throws Exception {
        TestTenantConfiguration.actAs("tenant_agg");

        String ingestJson = """
            {
              "eventId": "evt_agg_http",
              "idempotencyKey": "key_agg_http",
              "tenantId": "tenant_agg",
              "customerId": "cust_agg",
              "meterCode": "STORAGE_GB",
              "value": 150.0,
              "timestamp": "2026-10-08T10:15:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ingestJson))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/pricing/meter/aggregations")
                .param("tenantId", "tenant_agg")
                .param("customerId", "cust_agg")
                .param("meterCode", "STORAGE_GB")
                .param("windowStart", "2026-10-08T10:00:00Z")
                .param("windowEnd", "2026-10-08T11:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenantId", is("tenant_agg")))
            .andExpect(jsonPath("$.meterCode", is("STORAGE_GB")))
            .andExpect(jsonPath("$.aggregatedValue", is(150.0)))
            .andExpect(jsonPath("$.eventCount", is(1)));
    }

    @Test
    @DisplayName("GET /aggregations returns 403 ProblemDetail when tenant does not match TenantGuard")
    void testGetAggregationTenantMismatch() throws Exception {
        TestTenantConfiguration.actAs("tenant_ctrl");

        mockMvc.perform(get("/api/v1/pricing/meter/aggregations")
                .param("tenantId", "tenant_other")
                .param("meterCode", "STORAGE_GB")
                .param("windowStart", "2026-10-08T10:00:00Z")
                .param("windowEnd", "2026-10-08T11:00:00Z"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("GET /aggregations returns 400 ProblemDetail when required query parameter is missing")
    void testGetAggregationMissingParam() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/meter/aggregations")
                .param("tenantId", "tenant_ctrl")
                .param("meterCode", "STORAGE_GB"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }

    private void setupRateCardAndWallet(String tenant, String customer, String plan, String walletId) {
        TenantId tenantId = TenantId.of(tenant);
        CustomerId customerId = CustomerId.of(customer);
        PlanCode planCode = PlanCode.of(plan);
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        if (rateCardRepository.findEffectiveRateCard(tenantId, planCode, now).isEmpty()) {
            rateCardRepository.save(RateCard.of(
                "rc_" + tenant + "_" + plan,
                tenantId,
                planCode,
                1,
                now,
                List.of(RatePlanItem.of("API_CALLS", "Calls", PricingModel.PerUnitModel.of(new BigDecimal("0.50")), CurrencyUnit.USD))
            ));
        }

        if (walletRepository.findWallet(tenantId, customerId).isEmpty()) {
            CreditGrant grant = CreditGrant.prepaid("grant_" + walletId, walletId, "Prepaid", new BigDecimal("100"), BigDecimal.ONE, now);
            walletRepository.save(Wallet.of(walletId, tenantId, customerId, CurrencyUnit.USD, List.of(grant)));
        }
    }

    @Test
    @DisplayName("POST /rate-and-drawdown performs rating and wallet debit end-to-end via HTTP")
    void testRateAndDrawdownSuccess() throws Exception {
        TestTenantConfiguration.actAs("tenant_draw");
        setupRateCardAndWallet("tenant_draw", "cust_draw", "DRAW_PLAN", "wal_draw_mvc");

        // Ingest usage
        String eventJson = """
            {
              "eventId": "evt_dmvc_1",
              "idempotencyKey": "key_dmvc_1",
              "tenantId": "tenant_draw",
              "customerId": "cust_draw",
              "meterCode": "API_CALLS",
              "value": 20.0,
              "timestamp": "2026-10-08T05:00:00Z"
            }
            """;
        mockMvc.perform(post("/api/v1/pricing/meter/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson))
            .andExpect(status().isOk());

        // Rate and drawdown
        String rateReq = """
            {
              "tenantId": "tenant_draw",
              "customerId": "cust_draw",
              "planCode": "DRAW_PLAN",
              "targetCurrency": "USD",
              "windowStart": "2026-10-08T00:00:00Z",
              "windowEnd": "2026-10-08T12:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/rate-and-drawdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rateReq))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.walletId", is("wal_draw_mvc")))
            .andExpect(jsonPath("$.originalInvoiceAmount", is("10 USD")))
            .andExpect(jsonPath("$.totalCreditsDrawn", is(10.0)))
            .andExpect(jsonPath("$.remainingInvoiceDue", is("0 USD")))
            .andExpect(jsonPath("$.fullyCovered", is(true)))
            .andExpect(jsonPath("$.currency", is("USD")))
            .andExpect(jsonPath("$.originalAmount", is(10.0)))
            .andExpect(jsonPath("$.creditMoneyAmount", is(10.0)))
            .andExpect(jsonPath("$.remainingDueAmount", is(0.0)));
    }

    @Test
    @DisplayName("POST /rate-and-drawdown falls back to configured defaultCurrency when targetCurrency is omitted")
    void testRateAndDrawdownDefaultCurrencyFallback() throws Exception {
        TestTenantConfiguration.actAs("tenant_draw");
        setupRateCardAndWallet("tenant_draw", "cust_draw", "DRAW_PLAN", "wal_draw_mvc");

        String rateReq = """
            {
              "tenantId": "tenant_draw",
              "customerId": "cust_draw",
              "planCode": "DRAW_PLAN",
              "windowStart": "2026-10-08T00:00:00Z",
              "windowEnd": "2026-10-08T12:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/rate-and-drawdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rateReq))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullyCovered", is(true)));
    }

    @Test
    @DisplayName("POST /rate-and-drawdown returns 400 ProblemDetail when planCode is missing")
    void testRateAndDrawdownValidationMissingPlanCode() throws Exception {
        String rateReq = """
            {
              "tenantId": "tenant_ctrl",
              "windowStart": "2026-10-08T00:00:00Z",
              "windowEnd": "2026-10-08T12:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/rate-and-drawdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rateReq))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /rate-and-drawdown returns 403 ProblemDetail on tenant mismatch")
    void testRateAndDrawdownTenantMismatch() throws Exception {
        String rateReq = """
            {
              "tenantId": "tenant_intruder",
              "planCode": "DRAW_PLAN",
              "windowStart": "2026-10-08T00:00:00Z",
              "windowEnd": "2026-10-08T12:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/meter/rate-and-drawdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rateReq))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }
}
