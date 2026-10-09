package com.saas.pricing.starter.loadtest;

import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import com.saas.pricing.starter.tenant.TenantResolver;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;


/**
 * A minimal host application, for load testing the engine over HTTP.
 *
 * <p>This repository is a library, so there is no server to drive until somebody writes one. That
 * is not a gap in the engine — it is the library/host boundary working as designed — but it does
 * mean an HTTP load test needs a host. This is the smallest one that exercises the real wiring:
 * auto-configuration, tenant resolution, the rate card, and the web layer.
 *
 * <p><b>Test scope only.</b> It lives under {@code src/test/java} and is never published. It exists
 * to produce a throughput number for <i>this library driven by a host</i>, which is the only figure
 * a host team can act on. It is deliberately not a reference application: no auth, no UI, no
 * persistence tuning, no configuration beyond what the tests need.
 *
 * <p>Run the load profile against it:
 *
 * <pre>{@code mvn test -Dtest=HttpLoadTest -Dsurefire.excludedGroups=}</pre>
 */
@SpringBootApplication(scanBasePackages = "com.saas.pricing.starter.loadtest")
@Import(PricingEngineAutoConfiguration.class)
public class LoadTestHostApplication {

    /** Every request in the load profile belongs to one tenant; isolation is not under test here. */
    @Bean
    TenantResolver tenantResolver() {
        return () -> "t-load";
    }

}
