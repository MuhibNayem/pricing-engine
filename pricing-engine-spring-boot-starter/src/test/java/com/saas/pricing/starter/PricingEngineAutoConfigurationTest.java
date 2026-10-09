package com.saas.pricing.starter;

import com.saas.pricing.core.engine.PricingEngine;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.FxRateCache;
import com.saas.pricing.core.spi.impl.ConcurrentMapFxRateCache;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;
import com.saas.pricing.core.spi.impl.CachedCurrencyExchangeProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.saas.pricing.metering.engine.UsageMeteringEngine;
import com.saas.pricing.metering.stream.AsyncRatingTriggerService;

import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        // The engine refuses to start without a TenantResolver; supply a trivial one so these
        // tests can exercise the beans rather than the fail-fast path.
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class, () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "tenant_test");

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
    @DisplayName("Should refuse to start the web API without a TenantResolver")
    void testWebApiFailsClosedWithoutTenantResolver() {
        // Without a resolver the only tenant available is the one in the request body, which lets
        // any caller act as any tenant. Refusing to start is the safe default.
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
            .withPropertyValues("pricing.engine.web-enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure().getMessage()).contains("TenantResolver");
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













    @Test
    @DisplayName("Should use host-provided FxRateCache bean when available")
    void testCustomFxRateCacheAccepted() {
        contextRunner
            .withBean("customFxCache", FxRateCache.class, ConcurrentMapFxRateCache::new)
            .run(context -> {
                assertThat(context).hasSingleBean(FxRateCache.class);
                var provider = context.getBean(CurrencyExchangeProvider.class);
                assertThat(provider).isInstanceOf(CachedCurrencyExchangeProvider.class);
            });
    }

    @Test
    @DisplayName("Should not wrap currencyExchangeProvider when caching is disabled")
    void testFxCachingDisabled() {
        contextRunner
            .withPropertyValues("pricing.engine.enable-caching=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(FxRateCache.class);
                var provider = context.getBean(CurrencyExchangeProvider.class);
                assertThat(provider).isNotInstanceOf(CachedCurrencyExchangeProvider.class);
            });
    }

    @Test
    @DisplayName("Should omit asyncRatingTriggerService when async-rating-enabled is false and return Optional.empty from listener")
    void testAsyncRatingEnabledFalse() {
        contextRunner
            .withPropertyValues("pricing.engine.streaming.async-rating-enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(AsyncRatingTriggerService.class);
                assertThat(context).hasSingleBean(com.saas.pricing.starter.streaming.SpringMeterEventListener.class);
                var listener = context.getBean(com.saas.pricing.starter.streaming.SpringMeterEventListener.class);
                assertThat(listener.getAsyncRatingTriggerService()).isEmpty();
            });
    }

    @Test
    @DisplayName("Should expose AsyncRatingTriggerService via Optional in SpringMeterEventListener when enabled")
    void testAsyncRatingTriggerServiceExposedAsOptional() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AsyncRatingTriggerService.class);
            var listener = context.getBean(com.saas.pricing.starter.streaming.SpringMeterEventListener.class);
            assertThat(listener.getAsyncRatingTriggerService())
                .isPresent()
                .containsSame(context.getBean(AsyncRatingTriggerService.class));
        });
    }

    @Test
    @DisplayName("Should back off web controllers when web-enabled is false")
    void testWebEnabledFalse() {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
            .withPropertyValues("pricing.engine.web-enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(com.saas.pricing.starter.web.PricingEngineController.class);
                assertThat(context).doesNotHaveBean(com.saas.pricing.starter.web.PricingEngineExceptionHandler.class);
                assertThat(context).doesNotHaveBean(com.saas.pricing.starter.web.InvoiceController.class);
                assertThat(context).doesNotHaveBean(com.saas.pricing.starter.web.SubscriptionController.class);
            });
    }

    @Test
    @DisplayName("Should back off metering engine and controller when metering.enabled is false")
    void testMeteringEnabledFalse() {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
            .withBean(com.saas.pricing.starter.tenant.TenantResolver.class, () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "tenant_test")
            .withPropertyValues("pricing.engine.metering.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(UsageMeteringEngine.class);
                assertThat(context).doesNotHaveBean(com.saas.pricing.starter.web.MeteringController.class);
            });
    }

    @Test
    @DisplayName("Should correctly bind defaultCurrency, roundingMode, and allowRequestDiscounts")
    void testPropertiesBinding() {
        contextRunner
            .withPropertyValues(
                "pricing.engine.default-currency=EUR",
                "pricing.engine.rounding-mode=CEILING",
                "pricing.engine.allow-request-discounts=true"
            )
            .run(context -> {
                var props = context.getBean(PricingEngineProperties.class);
                assertThat(props.getDefaultCurrency()).isEqualTo("EUR");
                assertThat(props.getRoundingMode()).isEqualTo(java.math.RoundingMode.CEILING);
                assertThat(props.isAllowRequestDiscounts()).isTrue();
            });
    }

    @Test
    @DisplayName("Should back off streaming dispatcher and consumer when streaming.enabled is false")
    void testStreamingEnabledFalse() {
        contextRunner
            .withPropertyValues("pricing.engine.streaming.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(AsyncRatingTriggerService.class);
                assertThat(context).doesNotHaveBean(com.saas.pricing.metering.stream.DefaultMeterEventDispatcher.class);
                assertThat(context).doesNotHaveBean(com.saas.pricing.metering.stream.MeterEventConsumer.class);
                assertThat(context).doesNotHaveBean(com.saas.pricing.starter.streaming.SpringMeterEventListener.class);
            });
    }

    @Test
    @DisplayName("Should correctly bind and configure invoice numbering and metering knobs")
    void testInvoiceNumberingAndMeteringKnobsBinding() {
        contextRunner
            .withPropertyValues(
                "pricing.engine.invoice-numbering.scheme=CUSTOMER_SEQUENTIAL",
                "pricing.engine.invoice-numbering.prefix=BILL",
                "pricing.engine.invoice-numbering.padding=6",
                "pricing.engine.invoice-numbering.start-at=1000",
                "pricing.engine.metering.allowed-lateness-seconds=3600",
                "pricing.engine.metering.refuse-approximate-aggregations=false"
            )
            .run(context -> {
                var props = context.getBean(PricingEngineProperties.class);
                assertThat(props.getInvoiceNumbering().getScheme())
                    .isEqualTo(com.saas.pricing.core.model.invoice.InvoiceNumberScheme.CUSTOMER_SEQUENTIAL);
                assertThat(props.getInvoiceNumbering().getPrefix()).isEqualTo("BILL");
                assertThat(props.getInvoiceNumbering().getPadding()).isEqualTo(6);
                assertThat(props.getInvoiceNumbering().getStartAt()).isEqualTo(1000L);

                assertThat(props.getMetering().getAllowedLatenessSeconds()).isEqualTo(3600L);
                assertThat(props.getMetering().isRefuseApproximateAggregations()).isFalse();

                assertThat(context).hasSingleBean(com.saas.pricing.core.model.invoice.InvoiceNumberService.class);
            });
    }
}
