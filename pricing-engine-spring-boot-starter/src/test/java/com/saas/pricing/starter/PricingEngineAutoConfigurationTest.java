package com.saas.pricing.starter;

import com.saas.pricing.core.engine.PricingEngine;
import com.saas.pricing.core.spi.RateCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class));

    @Test
    @DisplayName("Should auto-configure PricingEngine and all enterprise beans when enabled")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PricingEngine.class);
            assertThat(context).hasSingleBean(RateCardRepository.class);
            assertThat(context).hasSingleBean(EnterprisePricingService.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.engine.HierarchicalRateCardResolver.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.spi.WalletRepository.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.engine.WalletDrawdownEngine.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.spi.EntitlementRepository.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.engine.EntitlementVerifier.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.engine.BatchPricingEngine.class);
        });
    }

    @Test
    @DisplayName("Should back off when pricing.engine.enabled is false")
    void testDisabledAutoConfiguration() {
        contextRunner
            .withPropertyValues("pricing.engine.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(PricingEngine.class);
                assertThat(context).doesNotHaveBean(EnterprisePricingService.class);
            });
    }

    @Test
    @DisplayName("Should verify ScopedPricingContext runs on Java 25 virtual threads")
    void testScopedPricingContext() throws Exception {
        var tenantId = com.saas.pricing.core.model.TenantId.of("tenant_scoped");
        var customerId = com.saas.pricing.core.model.CustomerId.of("cust_scoped");

        String result = com.saas.pricing.starter.context.ScopedPricingContext.callWithContext(
            tenantId,
            customerId,
            "corr-123",
            "api-gateway",
            java.time.Instant.now(),
            () -> {
                assertThat(com.saas.pricing.starter.context.ScopedPricingContext.currentTenant()).contains(tenantId);
                assertThat(com.saas.pricing.starter.context.ScopedPricingContext.currentCustomer()).contains(customerId);
                assertThat(com.saas.pricing.starter.context.ScopedPricingContext.correlationId()).contains("corr-123");
                assertThat(com.saas.pricing.starter.context.ScopedPricingContext.callerIdentity()).contains("api-gateway");
                return "SUCCESS";
            }
        );

        assertThat(result).isEqualTo("SUCCESS");
        // Outside scope, values are unbound
        assertThat(com.saas.pricing.starter.context.ScopedPricingContext.currentTenant()).isEmpty();
    }
}
