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
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = PricingEngineAutoConfiguration.class)
@Import(TestTenantConfiguration.class)
class PricingEngineControllerTest {

    @Autowired
    private PricingEngineController controller;

    @Autowired
    private PricingEngineExceptionHandler exceptionHandler;

    @Autowired
    private RateCardRepository rateCardRepo;

    @Autowired
    private WalletRepository walletRepo;

    @Autowired
    private com.saas.pricing.core.spi.EntitlementRepository entitlementRepo;

    @Autowired
    private com.saas.pricing.starter.EnterprisePricingService pricingService;

    @Autowired
    private com.saas.pricing.starter.tenant.TenantGuard tenantGuard;

    @Autowired
    private com.saas.pricing.starter.PricingEngineProperties defaultProperties;

    private MockMvc mockMvc;

    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();

        TestTenantConfiguration.actAs("tenant_rest");

        if (rateCardRepo.findEffectiveRateCard(TenantId.of("tenant_rest"), PlanCode.of("TEAM"), T0).isEmpty()) {
            var seatsItem = RatePlanItem.of(
                "SEATS", "seats",
                PricingModel.PerUnitModel.of(BigDecimal.valueOf(25)),
                CurrencyUnit.USD
            );
            var rateCard = RateCard.of(
                "rc_team",
                TenantId.of("tenant_rest"),
                PlanCode.of("TEAM"),
                1,
                T0,
                List.of(seatsItem)
            );
            rateCardRepo.save(rateCard);
        }

        if (walletRepo.findWallet(TenantId.of("tenant_rest"), CustomerId.of("cust_1")).isEmpty()) {
            CreditGrant grant = CreditGrant.prepaid(
                "grant_rest_1", "wal_rest_1", "Prepaid",
                BigDecimal.valueOf(500), BigDecimal.ONE, T0
            );
            walletRepo.save(Wallet.of(
                "wal_rest_1",
                TenantId.of("tenant_rest"),
                CustomerId.of("cust_1"),
                CurrencyUnit.USD,
                List.of(grant)
            ));
        }

        if (entitlementRepo.findEntitlement(TenantId.of("tenant_rest"), CustomerId.of("cust_1"), "AI_SEARCH", T0).isEmpty()) {
            entitlementRepo.saveEntitlement(com.saas.pricing.core.model.entitlement.CustomerEntitlement.metered(
                "ent_ai",
                TenantId.of("tenant_rest"),
                CustomerId.of("cust_1"),
                PlanCode.of("TEAM"),
                "AI_SEARCH",
                BigDecimal.valueOf(100),
                BigDecimal.ZERO,
                true,
                T0
            ));
        }
    }

    // =========================================================================
    // 1. Health Endpoint
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/pricing/health returns 200 UP")
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("UP")))
            .andExpect(jsonPath("$.service", is("Enterprise Pricing Engine")));
    }

    // =========================================================================
    // 2. Evaluate Endpoint
    // =========================================================================

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate returns 200 and correct calculation for valid payload")
    void testEvaluateSuccess() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "targetCurrency": "USD",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 4
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.calculationId", notNullValue()))
            .andExpect(jsonPath("$.tenantId", is("tenant_rest")))
            .andExpect(jsonPath("$.customerId", is("cust_1")))
            .andExpect(jsonPath("$.planCode", is("TEAM")))
            .andExpect(jsonPath("$.currency", is("USD")))
            .andExpect(jsonPath("$.finalTotal", is("100 USD")))
            .andExpect(jsonPath("$.lineItems", hasSize(1)))
            .andExpect(jsonPath("$.lineItems[0].itemCode", is("SEATS")))
            .andExpect(jsonPath("$.lineItems[0].rawQuantity", is(4)))
            .andExpect(jsonPath("$.lineItems[0].lineTotal", is("100 USD")));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate falls back to default currency when targetCurrency is omitted")
    void testEvaluateDefaultCurrencyFallback() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 2
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency", is("USD")))
            .andExpect(jsonPath("$.finalTotal", is("50 USD")));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate returns 400 ProblemDetail on blank planCode")
    void testEvaluateValidationBlankPlanCode() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 4
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Validation failed")))
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.errors", hasItem(containsString("planCode"))));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate returns 400 ProblemDetail on empty items")
    void testEvaluateValidationEmptyItems() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "items": []
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Validation failed")))
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.errors", hasItem(containsString("items"))));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate returns 400 ProblemDetail on negative item quantity")
    void testEvaluateValidationNegativeQuantity() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": -5
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Validation failed")))
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.errors", hasItem(containsString("quantity"))));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate returns 403 ProblemDetail when tenant mismatches authenticated session")
    void testEvaluateTenantMismatchForbidden() throws Exception {
        String payload = """
            {
                "tenantId": "other_tenant",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 4
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title", is("Tenant access denied")))
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")))
            .andExpect(jsonPath("$.detail", is("The caller is not authorised to act on this tenant")));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate returns 400 ProblemDetail when caller-supplied discounts are disallowed")
    void testEvaluateDisallowedDiscounts() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "targetCurrency": "USD",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 4
                    }
                ],
                "discounts": [
                    {
                        "code": "VIP",
                        "type": "PERCENTAGE",
                        "value": 10,
                        "scope": "INVOICE_TOTAL"
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Invalid pricing request")))
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
            .andExpect(jsonPath("$.detail", containsString("Caller-supplied discounts are not accepted")));
    }

    @Test
    @DisplayName("PricingEngineController accepts caller discounts when allowRequestDiscounts is true")
    void testEvaluateWithAllowedDiscounts() throws Exception {
        var props = new com.saas.pricing.starter.PricingEngineProperties();
        props.setAllowRequestDiscounts(true);
        var customController = new PricingEngineController(pricingService, tenantGuard, props);
        MockMvc customMvc = MockMvcBuilders.standaloneSetup(customController)
            .setControllerAdvice(exceptionHandler)
            .build();

        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "targetCurrency": "USD",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 4
                    }
                ],
                "discounts": [
                    {
                        "code": "VIP",
                        "type": "PERCENTAGE",
                        "value": 10,
                        "scope": "INVOICE_TOTAL"
                    }
                ]
            }
            """;

        customMvc.perform(post("/api/v1/pricing/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalGross", is("100 USD")))
            .andExpect(jsonPath("$.totalDiscount", is("10 USD")))
            .andExpect(jsonPath("$.finalTotal", is("90 USD")));
    }

    // =========================================================================
    // 3. Evaluate Batch Endpoint
    // =========================================================================

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate-batch returns 200 for valid batch")
    void testEvaluateBatchSuccess() throws Exception {
        String payload = """
            [
                {
                    "tenantId": "tenant_rest",
                    "customerId": "cust_1",
                    "planCode": "TEAM",
                    "targetCurrency": "USD",
                    "items": [{"itemCode": "SEATS", "quantity": 2}]
                },
                {
                    "tenantId": "tenant_rest",
                    "customerId": "cust_2",
                    "planCode": "TEAM",
                    "targetCurrency": "USD",
                    "items": [{"itemCode": "SEATS", "quantity": 3}]
                }
            ]
            """;

        mockMvc.perform(post("/api/v1/pricing/evaluate-batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].finalTotal", is("50 USD")))
            .andExpect(jsonPath("$[1].finalTotal", is("75 USD")));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate-batch returns 400 ProblemDetail on empty batch")
    void testEvaluateBatchEmptyList() throws Exception {
        mockMvc.perform(post("/api/v1/pricing/evaluate-batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Invalid pricing request")))
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
            .andExpect(jsonPath("$.detail", is("Batch request cannot be empty")));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/evaluate-batch returns 400 ProblemDetail when batch exceeds MAX_BATCH_SIZE (1,000)")
    void testEvaluateBatchOversized() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        String item = """
            {"tenantId":"tenant_rest","planCode":"TEAM","items":[{"itemCode":"SEATS","quantity":1}]}
            """;
        for (int i = 0; i < 1001; i++) {
            if (i > 0) sb.append(",");
            sb.append(item.trim());
        }
        sb.append("]");

        mockMvc.perform(post("/api/v1/pricing/evaluate-batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sb.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Invalid pricing request")))
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
            .andExpect(jsonPath("$.detail", containsString("exceeds the maximum of 1000")));
    }

    // =========================================================================
    // 4. Entitlements Verify Endpoint
    // =========================================================================

    @Test
    @DisplayName("POST /api/v1/pricing/entitlements/verify returns 200 for valid entitlement check")
    void testEntitlementVerifySuccess() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "featureKey": "AI_SEARCH",
                "requestedUnits": 10
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/entitlements/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.featureKey", is("AI_SEARCH")))
            .andExpect(jsonPath("$.requestedUnits", is(10)));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/entitlements/verify returns 400 ProblemDetail on blank featureKey")
    void testEntitlementVerifyValidationBlankFeature() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "featureKey": "",
                "requestedUnits": 10
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/entitlements/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Validation failed")))
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.errors", hasItem(containsString("featureKey"))));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/entitlements/verify returns 403 ProblemDetail on tenant mismatch")
    void testEntitlementVerifyTenantMismatch() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_other",
                "customerId": "cust_1",
                "featureKey": "AI_SEARCH",
                "requestedUnits": 10
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/entitlements/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/entitlements/verify returns 200 with allowed=false when entitlement is absent")
    void testEntitlementVerifyDeniedWhenMissing() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "featureKey": "NON_EXISTENT_FEATURE",
                "requestedUnits": 1
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/entitlements/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed", is(false)))
            .andExpect(jsonPath("$.reason", containsString("No entitlement found")));
    }

    // =========================================================================
    // 5. Wallets Drawdown Endpoint
    // =========================================================================

    @Test
    @DisplayName("POST /api/v1/pricing/wallets/drawdown returns 200 on valid drawdown")
    void testDrawdownSuccess() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "targetCurrency": "USD",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 4
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/wallets/drawdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.walletId", is("wal_rest_1")))
            .andExpect(jsonPath("$.originalInvoiceAmount", is("100 USD")))
            .andExpect(jsonPath("$.totalCreditsDrawn", is(100.0)))
            .andExpect(jsonPath("$.fullyCovered", is(true)));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/wallets/drawdown returns 400 ProblemDetail on empty items")
    void testDrawdownValidationEmptyItems() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_rest",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "targetCurrency": "USD",
                "items": []
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/wallets/drawdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Validation failed")))
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.errors", hasItem(containsString("items"))));
    }

    @Test
    @DisplayName("POST /api/v1/pricing/wallets/drawdown returns 403 ProblemDetail on tenant mismatch")
    void testDrawdownTenantMismatch() throws Exception {
        String payload = """
            {
                "tenantId": "tenant_alien",
                "customerId": "cust_1",
                "planCode": "TEAM",
                "targetCurrency": "USD",
                "items": [
                    {
                        "itemCode": "SEATS",
                        "quantity": 1
                    }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/wallets/drawdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }
}
