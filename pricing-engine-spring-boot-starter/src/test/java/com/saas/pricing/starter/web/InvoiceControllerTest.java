package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.starter.EnterprisePricingService;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc HTTP-level integration tests for InvoiceController.
 *
 * <p>Validates HTTP dispatching, Bean Validation, required Idempotency-Key headers,
 * idempotency replay and payload conflict (422), TenantGuard isolation (403),
 * RFC 9457 ProblemDetail error formats, and document lifecycle endpoints.
 */
@SpringBootTest(classes = PricingEngineAutoConfiguration.class)
@Import(TestTenantConfiguration.class)
class InvoiceControllerTest {

    private static final String TENANT = "tenant_inv";
    private static final String CUSTOMER = "cust_inv";
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    @Autowired
    private InvoiceController controller;

    @Autowired
    private PricingEngineExceptionHandler exceptionHandler;

    @Autowired
    private EnterprisePricingService pricingService;

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
                "rc_inv_pro",
                TenantId.of(TENANT),
                PlanCode.of("PRO"),
                1,
                T0,
                List.of(RatePlanItem.of("SEATS", "Seats",
                    PricingModel.PerUnitModel.of(new BigDecimal("25.00")), CurrencyUnit.USD))
            ));
        }
    }

    private String createRatingCalculationId(String invoiceId) {
        var rated = pricingService.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
            .tenantId(TENANT)
            .customerId(CUSTOMER)
            .planCode("PRO")
            .evaluationTime(T0)
            .targetCurrency(CurrencyUnit.USD)
            .item("SEATS", 4)
            .build());
        return rated.calculationId();
    }

    @Test
    @DisplayName("POST /invoices requires Idempotency-Key header (returns 400 when missing)")
    void testCreateDraftMissingIdempotencyKey() throws Exception {
        String json = """
            {
              "invoiceId": "inv-nokey",
              "customerId": "cust_inv",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "calculationId": "calc-123",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }

    @Test
    @DisplayName("POST /invoices returns 400 ProblemDetail when required fields are missing")
    void testCreateDraftValidationFailed() throws Exception {
        String json = """
            {
              "invoiceId": "",
              "customerId": "",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "key-val-err")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /invoices returns 403 ProblemDetail on tenant mismatch")
    void testCreateDraftTenantMismatch() throws Exception {
        String json = """
            {
              "invoiceId": "inv-other",
              "customerId": "cust_inv",
              "tenantId": "tenant_other",
              "planCode": "PRO",
              "calculationId": "calc-xyz",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "key-mismatch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("POST /invoices creates draft and returns 201 Created with valid rating")
    void testCreateDraftSuccess() throws Exception {
        String calcId = createRatingCalculationId("inv-success-1");

        String json = """
            {
              "invoiceId": "inv-success-1",
              "customerId": "cust_inv",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "calculationId": "%s",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z",
              "taxCode": "TX_STANDARD"
            }
            """.formatted(calcId);

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "idem-draft-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.invoiceId", is("inv-success-1")))
            .andExpect(jsonPath("$.status", is("DRAFT")))
            .andExpect(jsonPath("$.total", is("100.00")))
            .andExpect(jsonPath("$.lineItems", hasSize(1)));
    }

    @Test
    @DisplayName("POST /invoices replays previous 201 Created on identical retried request")
    void testCreateDraftIdempotencyReplay() throws Exception {
        String calcId = createRatingCalculationId("inv-replay-1");

        String json = """
            {
              "invoiceId": "inv-replay-1",
              "customerId": "cust_inv",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "calculationId": "%s",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """.formatted(calcId);

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "idem-replay-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.invoiceId", is("inv-replay-1")));

        // Replay with identical key and payload
        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "idem-replay-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.invoiceId", is("inv-replay-1")));
    }

    @Test
    @DisplayName("POST /invoices returns 422 Unprocessable Entity when key is reused for different payload")
    void testCreateDraftIdempotencyConflict() throws Exception {
        String calcId = createRatingCalculationId("inv-conflict-orig");

        String json1 = """
            {
              "invoiceId": "inv-conflict-orig",
              "customerId": "cust_inv",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "calculationId": "%s",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """.formatted(calcId);

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "idem-conflict-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json1))
            .andExpect(status().isCreated());

        String json2 = """
            {
              "invoiceId": "inv-conflict-other",
              "customerId": "cust_inv",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "calculationId": "%s",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """.formatted(calcId);

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "idem-conflict-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json2))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", is("REQUEST_REJECTED")));
    }

    @Test
    @DisplayName("GET /invoices/{id} returns invoice and handles tenant mismatch and not found")
    void testGetInvoiceLifecycle() throws Exception {
        String calcId = createRatingCalculationId("inv-get-1");
        String json = """
            {
              "invoiceId": "inv-get-1",
              "customerId": "cust_inv",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "calculationId": "%s",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """.formatted(calcId);

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "idem-get-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isCreated());

        // Successful lookup
        mockMvc.perform(get("/api/v1/pricing/invoices/inv-get-1")
                .param("tenantId", TENANT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invoiceId", is("inv-get-1")))
            .andExpect(jsonPath("$.status", is("DRAFT")));

        // Tenant mismatch
        mockMvc.perform(get("/api/v1/pricing/invoices/inv-get-1")
                .param("tenantId", "tenant_stranger"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));

        // Unknown invoice
        mockMvc.perform(get("/api/v1/pricing/invoices/inv-nonexistent")
                .param("tenantId", TENANT))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }

    @Test
    @DisplayName("Lifecycle: finalize, pay, and credit invoice via HTTP endpoints")
    void testInvoiceLifecycleEndpoints() throws Exception {
        String calcId = createRatingCalculationId("inv-flow-1");
        String json = """
            {
              "invoiceId": "inv-flow-1",
              "customerId": "cust_inv",
              "tenantId": "tenant_inv",
              "planCode": "PRO",
              "calculationId": "%s",
              "periodStart": "2026-10-01T00:00:00Z",
              "periodEnd": "2026-11-01T00:00:00Z"
            }
            """.formatted(calcId);

        mockMvc.perform(post("/api/v1/pricing/invoices")
                .header("Idempotency-Key", "idem-flow-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isCreated());

        // Finalize
        mockMvc.perform(post("/api/v1/pricing/invoices/inv-flow-1/finalize")
                .param("tenantId", TENANT)
                .param("invoiceNumber", "INV-2026-0001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("OPEN")))
            .andExpect(jsonPath("$.invoiceNumber", is("INV-2026-0001")));

        // Record payment
        String payJson = """
            {
              "amount": "100.00",
              "currency": "USD"
            }
            """;
        mockMvc.perform(post("/api/v1/pricing/invoices/inv-flow-1/payments")
                .param("tenantId", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("PAID")))
            .andExpect(jsonPath("$.amountPaid", is("100.00")));

        // Credit note
        String creditJson = """
            {
              "creditNoteId": "cn-001",
              "reason": "CUSTOMER_COURTESY",
              "disposition": "REFUND",
              "tenantId": "tenant_inv"
            }
            """;
        mockMvc.perform(post("/api/v1/pricing/invoices/inv-flow-1/credit-notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(creditJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.creditNoteId", is("cn-001")))
            .andExpect(jsonPath("$.invoiceId", is("inv-flow-1")))
            .andExpect(jsonPath("$.total", is("-100.00")));
    }

    @Test
    @DisplayName("GET /invoices paginated listing returns results")
    void testListInvoices() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/invoices")
                .param("tenantId", TENANT)
                .param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invoices", notNullValue()));
    }
}
