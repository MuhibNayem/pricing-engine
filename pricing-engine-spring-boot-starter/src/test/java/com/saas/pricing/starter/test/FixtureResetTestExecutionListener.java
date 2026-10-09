package com.saas.pricing.starter.test;

import com.saas.pricing.core.spi.ResettableForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.AopTestUtils;

import java.util.Map;

/**
 * Test execution listener that resets all {@link ResettableForTesting} beans in a cached
 * Spring ApplicationContext before and after each test method.
 *
 * <p>Prevents state leakage across test methods and test classes running in a shared context.
 * Only beans that explicitly opt in via the {@code ResettableForTesting} marker are touched;
 * no reflective discovery, no eager instantiation of the entire context.
 */
public class FixtureResetTestExecutionListener extends AbstractTestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(FixtureResetTestExecutionListener.class);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        resetAllState(testContext.getApplicationContext());
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        resetAllState(testContext.getApplicationContext());
    }

    public static void resetAllState(ApplicationContext context) {
        if (context == null) {
            return;
        }

        Map<String, ResettableForTesting> resettables = context.getBeansOfType(ResettableForTesting.class);
        for (Map.Entry<String, ResettableForTesting> entry : resettables.entrySet()) {
            try {
                ResettableForTesting target = AopTestUtils.getUltimateTargetObject(entry.getValue());
                target.resetForTesting();
            } catch (Exception e) {
                log.warn("Failed to reset bean '{}': {}", entry.getKey(), e.getMessage());
            }
        }

        // Also clear any CacheProvider instances (clear() is part of the CacheProvider SPI contract)
        Map<String, com.saas.pricing.core.spi.CacheProvider> caches =
            context.getBeansOfType(com.saas.pricing.core.spi.CacheProvider.class);
        for (Map.Entry<String, com.saas.pricing.core.spi.CacheProvider> entry : caches.entrySet()) {
            try {
                entry.getValue().clear();
            } catch (Exception e) {
                log.warn("Failed to clear CacheProvider '{}': {}", entry.getKey(), e.getMessage());
            }
        }

        // Also clear any FxRateCache instances
        Map<String, com.saas.pricing.core.spi.FxRateCache> fxCaches =
            context.getBeansOfType(com.saas.pricing.core.spi.FxRateCache.class);
        for (Map.Entry<String, com.saas.pricing.core.spi.FxRateCache> entry : fxCaches.entrySet()) {
            try {
                entry.getValue().clear();
            } catch (Exception e) {
                log.warn("Failed to clear FxRateCache '{}': {}", entry.getKey(), e.getMessage());
            }
        }

        // Reset TestTenantConfiguration if available
        try {
            com.saas.pricing.starter.web.TestTenantConfiguration.reset();
        } catch (Throwable ignored) {
            // TestTenantConfiguration may not be on the classpath in all test contexts
        }
    }
}
