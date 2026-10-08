package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcEntitlementRepositoryTest extends BaseJdbcRepositoryTest {

    private JdbcEntitlementRepository repository;
    private final TenantId tenantId = TenantId.of("tenant_ent");
    private final CustomerId customerId = CustomerId.of("cust_ent");
    private final PlanCode planCode = PlanCode.of("PLAN_A");

    @BeforeEach
    void setUp() {
        repository = new JdbcEntitlementRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Should save, retrieve, and atomically increment usage on customer entitlement")
    void testAtomicUsageIncrement() {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        CustomerEntitlement entitlement = CustomerEntitlement.metered(
            "ent_1",
            tenantId,
            customerId,
            planCode,
            "EXPORT_REPORTS",
            new BigDecimal("100"), // limit
            BigDecimal.ZERO,        // initial usage
            true,                   // hard limit
            now
        );

        repository.saveEntitlement(entitlement);

        // Atomic usage consumption +15
        repository.recordUsage(tenantId, customerId, "EXPORT_REPORTS", new BigDecimal("15"));

        Optional<CustomerEntitlement> updatedOpt = repository.findEntitlement(tenantId, customerId, "EXPORT_REPORTS", now.plusSeconds(60));
        assertThat(updatedOpt).isPresent();
        assertThat(updatedOpt.get().currentUsage()).isEqualByComparingTo("15");

        // Another consumption +20
        repository.recordUsage(tenantId, customerId, "EXPORT_REPORTS", new BigDecimal("20"));

        Optional<CustomerEntitlement> updatedOpt2 = repository.findEntitlement(tenantId, customerId, "EXPORT_REPORTS", now.plusSeconds(120));
        assertThat(updatedOpt2).isPresent();
        assertThat(updatedOpt2.get().currentUsage()).isEqualByComparingTo("35");
    }

    @Test
    @DisplayName("Should find entitlement at exact effectiveTo inclusive boundary instant")
    void testEffectiveToInclusiveBoundary() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");

        CustomerEntitlement entitlement = new CustomerEntitlement(
            "ent_bound",
            tenantId,
            customerId,
            planCode,
            "SSO_LOGIN",
            com.saas.pricing.core.model.entitlement.FeatureType.BOOLEAN,
            true,
            Optional.empty(),
            BigDecimal.ZERO,
            true,
            from,
            Optional.of(to)
        );

        repository.saveEntitlement(entitlement);

        Optional<CustomerEntitlement> found = repository.findEntitlement(tenantId, customerId, "SSO_LOGIN", to);
        assertThat(found).isPresent();
        assertThat(found.get().entitlementId()).isEqualTo("ent_bound");
    }
}
