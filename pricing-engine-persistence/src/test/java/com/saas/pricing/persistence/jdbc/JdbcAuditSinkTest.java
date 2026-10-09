package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.EvaluationTrace;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RatedLineItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.TraceStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditSinkTest extends BaseJdbcRepositoryTest {

    private JdbcAuditSink auditSink;
    private final TenantId tenantId = TenantId.of("tenant_audit");
    private final CustomerId customerId = CustomerId.of("cust_audit");
    private final PlanCode planCode = PlanCode.of("AUDIT_PLAN");

    @BeforeEach
    void setUp() {
        auditSink = new JdbcAuditSink(jdbcTemplate);
    }

    @Test
    @DisplayName("Should persist and retrieve complete PricingResult audit ledger with trace")
    void testAuditSinkRecordAndRetrieve() {
        Instant now = Instant.parse("2026-10-08T15:00:00Z");

        EvaluationTrace trace = new EvaluationTrace(
            "calc_audit_1",
            now,
            List.of(
                TraceStep.of("STEP_1", "Starting evaluation"),
                TraceStep.of("STEP_2", "Discounts applied")
            )
        );

        RatedLineItem lineItem = new RatedLineItem(
            "SEATS",
            new BigDecimal("10"),
            new BigDecimal("10"),
            Money.of("150.00", CurrencyUnit.USD),
            Money.of("15.00", CurrencyUnit.USD),
            Money.of("135.00", CurrencyUnit.USD),
            Money.of("10.80", CurrencyUnit.USD),
            Money.of("145.80", CurrencyUnit.USD),
            List.of(TraceStep.of("LINE_RATED", "Seats rated"))
        );

        PricingResult result = new PricingResult(
            "calc_audit_1",
            tenantId,
            Optional.of(customerId),
            planCode,
            now,
            CurrencyUnit.USD,
            Money.of("150.00", CurrencyUnit.USD),
            Money.of("15.00", CurrencyUnit.USD),
            Money.of("135.00", CurrencyUnit.USD),
            Money.of("10.80", CurrencyUnit.USD),
            Money.of("145.80", CurrencyUnit.USD),
            List.of(lineItem),
            trace
        );

        auditSink.record(result);

        Optional<PricingResult> retrieved = auditSink.findAuditRecord(tenantId, "calc_audit_1");
        assertThat(retrieved).isPresent();

        PricingResult r = retrieved.get();
        assertThat(r.calculationId()).isEqualTo("calc_audit_1");
        assertThat(r.tenantId()).isEqualTo(tenantId);
        assertThat(r.customerId()).contains(customerId);
        assertThat(r.finalTotal().amount()).isEqualByComparingTo("145.80");
        assertThat(r.lineItems()).hasSize(1);
        assertThat(r.trace().steps()).hasSize(2);

        List<PricingResult> tenantResults = auditSink.findByTenant(tenantId, now.minusSeconds(60), now.plusSeconds(60));
        assertThat(tenantResults).hasSize(1);
    }
}
