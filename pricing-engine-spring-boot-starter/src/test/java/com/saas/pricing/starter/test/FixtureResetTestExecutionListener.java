package com.saas.pricing.starter.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Structural test execution listener that resets all in-memory repositories, caches,
 * and stateful stores in a cached Spring test context before and after each test method.
 *
 * <p>Prevents state leakage across test methods and test classes running in a shared/cached
 * Spring ApplicationContext.</p>
 */
public class FixtureResetTestExecutionListener extends AbstractTestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(FixtureResetTestExecutionListener.class);

    @Override
    public int getOrder() {
        // Run before Spring's @BeforeEach lifecycle callbacks (before test setup runs)
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        resetAllState(testContext.getApplicationContext());
        try {
            com.saas.pricing.starter.web.TestTenantConfiguration.reset();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        resetAllState(testContext.getApplicationContext());
        try {
            com.saas.pricing.starter.web.TestTenantConfiguration.reset();
        } catch (Throwable ignored) {
        }
    }

    public static void resetAllState(ApplicationContext context) {
        if (context == null) {
            return;
        }

        // 1. Explicit typed clearance for all in-memory repository and store SPIs
        clearBeansOfType(context, com.saas.pricing.core.spi.RateCardRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.SubscriptionRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.WalletRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.EntitlementRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.ContractOverrideRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.InvoiceRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.model.event.OutboxRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.CollectionRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.EntitlementEventRepository.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.AuditSink.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.SequenceAllocator.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.IdempotencyKeyStore.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.CacheProvider.class);
        clearBeansOfType(context, com.saas.pricing.core.spi.CurrencyExchangeProvider.class);

        clearBeansOfType(context, com.saas.pricing.metering.spi.MeterEventRepository.class);
        clearBeansOfType(context, com.saas.pricing.metering.spi.MeterAggregationRepository.class);
        clearBeansOfType(context, com.saas.pricing.metering.spi.MeterDefinitionRepository.class);
        clearBeansOfType(context, com.saas.pricing.metering.spi.RatingClaimStore.class);
        clearBeansOfType(context, com.saas.pricing.metering.spi.IdempotencyStore.class);

        // 2. Clear any other com.saas bean in the context providing a public void clear() method
        for (String beanName : context.getBeanDefinitionNames()) {
            try {
                Object bean = context.getBean(beanName);
                if (bean.getClass().getName().startsWith("com.saas")) {
                    invokeClearMethodIfPresent(bean);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static <T> void clearBeansOfType(ApplicationContext context, Class<T> type) {
        try {
            Map<String, T> beans = context.getBeansOfType(type);
            for (T bean : beans.values()) {
                invokeClearMethodIfPresent(bean);
            }
        } catch (Exception ignored) {
        }
    }

    private static void invokeClearMethodIfPresent(Object bean) {
        if (bean == null) return;
        try {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(bean);
            Method clearMethod = target.getClass().getMethod("clear");
            if (clearMethod.getReturnType().equals(Void.TYPE) && clearMethod.getParameterCount() == 0) {
                clearMethod.invoke(target);
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            log.trace("Could not invoke clear() on {}", bean.getClass().getSimpleName(), e);
        }
    }
}
