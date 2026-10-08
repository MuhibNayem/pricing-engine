package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.hierarchy.ContractOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcContractOverrideRepositoryTest extends BaseJdbcRepositoryTest {

    private JdbcContractOverrideRepository repository;
    private final TenantId tenantId = TenantId.of("tenant_co");
    private final CustomerId customerId = CustomerId.of("cust_vip");
    private final PlanCode planCode = PlanCode.of("PRO_PLAN");

    @BeforeEach
    void setUp() {
        repository = new JdbcContractOverrideRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Should save and query customer contract override")
    void testSaveAndFindOverride() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        var customItem = RatePlanItem.of("SEAT", "Seat", PricingModel.PerUnitModel.of(new BigDecimal("8.00")), CurrencyUnit.USD);
        var discount = Discount.percentage("VIP_15", new BigDecimal("15"));

        ContractOverride override = new ContractOverride(
            "co_vip_1",
            tenantId,
            customerId,
            planCode,
            1,
            from,
            Optional.empty(),
            from,
            Optional.empty(),
            List.of(customItem),
            List.of(discount),
            Optional.empty(),
            Map.of("salesRep", "Sarah")
        );

        repository.save(override);

        Optional<ContractOverride> retrieved = repository.findEffectiveOverride(tenantId, customerId, planCode, Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().contractId()).isEqualTo("co_vip_1");
        assertThat(retrieved.get().overriddenItems()).hasSize(1);
        assertThat(retrieved.get().customDiscounts()).hasSize(1);
    }

    @Test
    @DisplayName("Should find contract override at exact effectiveTo inclusive boundary instant")
    void testEffectiveToInclusiveBoundary() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");
        PlanCode plan = PlanCode.of("BOUND_PLAN");

        ContractOverride override = new ContractOverride(
            "co_bound_1",
            tenantId,
            customerId,
            plan,
            1,
            from,
            Optional.of(to),
            from,
            Optional.empty(),
            List.of(),
            List.of(),
            Optional.empty(),
            Map.of()
        );

        repository.save(override);

        Optional<ContractOverride> found = repository.findEffectiveOverride(tenantId, customerId, plan, to);
        assertThat(found).isPresent();
        assertThat(found.get().contractId()).isEqualTo("co_bound_1");
    }

    @Test
    @DisplayName("Should persist and deserialize SpendCommitment in contract override")
    void testSpendCommitmentInOverride() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        PlanCode plan = PlanCode.of("COMMIT_PLAN");

        var commitment = com.saas.pricing.core.model.wallet.SpendCommitment.of(
            "commit_1",
            tenantId,
            customerId,
            com.saas.pricing.core.model.Money.of("5000.00", CurrencyUnit.USD),
            com.saas.pricing.core.model.BillingCadence.MONTHLY,
            from
        );

        ContractOverride override = new ContractOverride(
            "co_commit_1",
            tenantId,
            customerId,
            plan,
            1,
            from,
            Optional.empty(),
            from,
            Optional.empty(),
            List.of(),
            List.of(),
            Optional.of(commitment),
            Map.of()
        );

        repository.save(override);

        Optional<ContractOverride> retrieved = repository.findEffectiveOverride(tenantId, customerId, plan, from.plusSeconds(3600));
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().spendCommitment()).isPresent();
        assertThat(retrieved.get().spendCommitment().get().minimumAmount().amount()).isEqualByComparingTo("5000.00");
    }
}
