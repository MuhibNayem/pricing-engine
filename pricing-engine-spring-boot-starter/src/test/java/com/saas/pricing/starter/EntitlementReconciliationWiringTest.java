package com.saas.pricing.starter;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.FeatureType;
import com.saas.pricing.core.model.entitlement.EntitlementEvent;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.spi.impl.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciliation path must actually be wired.
 *
 * <p>An earlier version of the auto-configuration accepted the event repository as a bean parameter
 * and then failed to pass it to the service, which compiled cleanly and left
 * reconcileEntitlements() permanently throwing. These tests exist so that class of silence cannot
 * come back.
 */
class EntitlementReconciliationWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
                  () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1");

    @Test
    @DisplayName("the auto-configured service can reconcile entitlements")
    void serviceCanReconcile() {
        contextRunner.run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var events = context.getBean(com.saas.pricing.core.spi.EntitlementEventRepository.class);
            var legacy = context.getBean(com.saas.pricing.core.spi.EntitlementRepository.class);

            var tenant = TenantId.of("t1");
            var customer = CustomerId.of("c1");
            var now = Instant.now();

            events.append(List.of(EntitlementEvent.grant("e1", tenant, customer, "A",
                FeatureType.BOOLEAN, Optional.empty(), now, now, "contract")));

            // A legacy row that says the customer does NOT hold it: a real divergence.
            legacy.saveEntitlement(CustomerEntitlement.booleanEntitlement("ent-A", tenant, customer,
                PlanCode.of("PRO"), "A", false, now));

            var report = service.reconcileEntitlements(tenant, customer);

            assertThat(report.isInSync()).isFalse();
            assertThat(report.wronglyDenied())
                .as("the stream says the customer holds A but the legacy row denies it")
                .containsExactly("A");
        });
    }

    @Test
    @DisplayName("an event repository bean is always present")
    void eventRepositoryAlwaysRegistered() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(com.saas.pricing.core.spi.EntitlementEventRepository.class));
    }

    @Test
    @DisplayName("the outbox, collection ledger and event store are all registered")
    void eventInfrastructureIsRegistered() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(com.saas.pricing.core.model.event.OutboxRepository.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.spi.CollectionRepository.class);
            assertThat(context).hasSingleBean(com.saas.pricing.core.spi.EntitlementEventRepository.class);
        });
    }
}
