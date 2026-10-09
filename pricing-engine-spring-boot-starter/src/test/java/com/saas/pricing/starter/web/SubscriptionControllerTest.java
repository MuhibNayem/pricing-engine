package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.core.model.event.InMemoryOutboxRepository;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.SubscriptionRepository;
import com.saas.pricing.core.spi.impl.InMemorySubscriptionRepository;
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import com.saas.pricing.starter.SubscriptionCommandService;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc HTTP-level integration tests for SubscriptionController.
 *
 * <p>Validates HTTP dispatching, Bean Validation, TenantGuard isolation (403),
 * RFC 9457 ProblemDetail error mapping, and subscription lifecycle transitions
 * (get, list, pause, resume, cancel, renewals).
 */
@SpringBootTest(classes = PricingEngineAutoConfiguration.class)
@Import(TestTenantConfiguration.class)
class SubscriptionControllerTest {

    private static final String TENANT = "t_sub";
    private static final String CUSTOMER = "c_sub";
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired
    private SubscriptionController controller;

    @Autowired
    private PricingEngineExceptionHandler exceptionHandler;

    @Autowired
    private SubscriptionCommandService commandService;

    @Autowired
    private RateCardRepository rateCardRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired(required = false)
    private OutboxRepository outboxRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        if (subscriptionRepository instanceof InMemorySubscriptionRepository inMem) {
            inMem.clear();
        }
        if (outboxRepository instanceof InMemoryOutboxRepository inMemOutbox) {
            inMemOutbox.clear();
        }

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();

        TestTenantConfiguration.actAs(TENANT);

        if (rateCardRepository.findEffectiveRateCard(TenantId.of(TENANT), PlanCode.of("PRO"), T0).isEmpty()) {
            rateCardRepository.save(RateCard.of(
                "rc_sub_pro",
                TenantId.of(TENANT),
                PlanCode.of("PRO"),
                1,
                T0,
                List.of(RatePlanItem.of("BASE", "Base Plan",
                    PricingModel.PerUnitModel.of(new BigDecimal("100.00")), CurrencyUnit.USD))
            ));
        }
    }

    private void seedSubscription(String subscriptionId, String customerId) {
        commandService.create(Subscription.active(
            subscriptionId,
            TenantId.of(TENANT),
            CustomerId.of(customerId),
            PlanCode.of("PRO"),
            T0,
            T1,
            T0
        ));
    }

    private void seedSubscription(String subscriptionId) {
        seedSubscription(subscriptionId, CUSTOMER);
    }

    @Test
    @DisplayName("GET /subscriptions/{id} returns subscription and handles tenant mismatch")
    void testGetSubscription() throws Exception {
        seedSubscription("sub-get-1");

        mockMvc.perform(get("/api/v1/pricing/subscriptions/sub-get-1")
                .param("tenantId", TENANT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscriptionId", is("sub-get-1")))
            .andExpect(jsonPath("$.status", is("ACTIVE")))
            .andExpect(jsonPath("$.planCode", is("PRO")));

        // Tenant mismatch
        mockMvc.perform(get("/api/v1/pricing/subscriptions/sub-get-1")
                .param("tenantId", "t_intruder"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("TENANT_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("GET /subscriptions lists subscriptions for a customer")
    void testListSubscriptions() throws Exception {
        seedSubscription("sub-list-1");

        mockMvc.perform(get("/api/v1/pricing/subscriptions")
                .param("tenantId", TENANT)
                .param("customerId", CUSTOMER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].subscriptionId", is("sub-list-1")));
    }

    @Test
    @DisplayName("POST /subscriptions/{id}/pause pauses active subscription")
    void testPauseSubscription() throws Exception {
        seedSubscription("sub-pause-1");

        mockMvc.perform(post("/api/v1/pricing/subscriptions/sub-pause-1/pause")
                .param("tenantId", TENANT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscriptionId", is("sub-pause-1")))
            .andExpect(jsonPath("$.status", is("PAUSED")));
    }

    @Test
    @DisplayName("POST /subscriptions/{id}/resume resumes paused subscription")
    void testResumeSubscription() throws Exception {
        seedSubscription("sub-resume-1");
        commandService.pause(TenantId.of(TENANT), "sub-resume-1");

        mockMvc.perform(post("/api/v1/pricing/subscriptions/sub-resume-1/resume")
                .param("tenantId", TENANT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscriptionId", is("sub-resume-1")))
            .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("POST /subscriptions/{id}/cancel cancels subscription via HTTP")
    void testCancelSubscription() throws Exception {
        seedSubscription("sub-cancel-1");

        String cancelJson = """
            {
              "tenantId": "t_sub",
              "itemCode": "BASE",
              "currency": "USD",
              "atPeriodEnd": false
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/subscriptions/sub-cancel-1/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cancelJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscriptionId", is("sub-cancel-1")))
            .andExpect(jsonPath("$.status", is("CANCELED")));
    }

    @Test
    @DisplayName("POST /subscriptions/{id}/cancel returns 400 ProblemDetail on validation failure")
    void testCancelSubscriptionValidationFailed() throws Exception {
        String cancelJson = """
            {
              "tenantId": "",
              "itemCode": "",
              "currency": "",
              "atPeriodEnd": false
            }
            """;

        mockMvc.perform(post("/api/v1/pricing/subscriptions/sub-cancel-bad/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cancelJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /subscriptions/renewals/run runs the renewal sweep")
    void testRunRenewals() throws Exception {
        mockMvc.perform(post("/api/v1/pricing/subscriptions/renewals/run")
                .param("tenantId", TENANT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenantId", is(TENANT)))
            .andExpect(jsonPath("$.renewed", is(0)));
    }
}
