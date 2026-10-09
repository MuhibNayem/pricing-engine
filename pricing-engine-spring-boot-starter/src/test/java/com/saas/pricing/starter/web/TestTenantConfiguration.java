package com.saas.pricing.starter.web;

import com.saas.pricing.starter.tenant.TenantResolver;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the {@link TenantResolver} the engine requires.
 *
 * <p>The starter refuses to start the REST API without one, because tenant identity must come from
 * the authenticated caller rather than the request body. Tests therefore have to say which tenant
 * they are acting as.
 *
 * <p>This is a top-level class rather than a nested one because a class-level annotation cannot
 * reference a nested type declared in the class it annotates - the nested type is not in scope
 * until the class body has been entered.
 */
@TestConfiguration
public class TestTenantConfiguration {

    /**
     * Stands in for the authenticated session. A test sets this to act as a given tenant; the guard
     * then rejects any request claiming a different one, which is the behaviour under test.
     */
    private static final ThreadLocal<String> AUTHENTICATED_TENANT =
            ThreadLocal.withInitial(() -> "tenant_rest");

    @Bean
    public TenantResolver tenantResolver() {
        return () -> AUTHENTICATED_TENANT.get();
    }

    /** Acts as {@code tenantId} for the remainder of the current test. */
    public static void actAs(String tenantId) {
        AUTHENTICATED_TENANT.set(tenantId);
    }

    /** Resets the authenticated tenant to the default "tenant_rest". */
    public static void reset() {
        AUTHENTICATED_TENANT.remove();
    }
}