package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.EvaluationTrace;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RatedLineItem;
import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An invoice must carry the figures the customer was quoted, discounts included.
 *
 * <p>The document used to be built from each line's gross amount, so every discounted rating
 * produced an invoice that charged the pre-discount price.
 */
class InvoiceFactoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final CurrencyUnit USD = CurrencyUnit.USD;

    @Test
    @DisplayName("a discounted rating produces an invoice that matches its totals")
    void discountedRatingIsBilledNet() {
        // Line: gross $100, 10% discount, $9 tax on the discounted base.
        RatedLineItem line = new RatedLineItem(
            "SEATS", BigDecimal.TEN, BigDecimal.TEN,
            Money.of("100.00", USD), Money.of("10.00", USD), Money.of("90.00", USD),
            Money.of("9.00", USD), Money.of("99.00", USD), List.of());

        PricingResult result = new PricingResult(
            "calc-1", TenantId.of("t1"), Optional.of(CustomerId.of("c1")), PlanCode.of("PRO"),
            T0, USD,
            Money.of("100.00", USD), Money.of("10.00", USD), Money.of("90.00", USD),
            Money.of("9.00", USD), Money.of("99.00", USD), List.of(line),
            new EvaluationTrace("calc-1", T0, new java.util.ArrayList<>()));

        Invoice invoice = InvoiceFactory.draftFrom(result, "inv-1", CustomerId.of("c1"),
            T0, T0.plusSeconds(86_400), T0, "TX_STANDARD");

        assertThat(invoice.subtotal().amount()).isEqualByComparingTo("90.00");
        assertThat(invoice.taxTotal().amount()).isEqualByComparingTo("9.00");
        assertThat(invoice.total().amount())
            .as("the invoice must bill the discounted amount, not the gross")
            .isEqualByComparingTo("99.00");
        assertThat(invoice.lineItems().getFirst().amount().amount()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("a non-exact unit-price division does not change the amount owed")
    void nonExactDivisionKeepsTheNetAmount() {
        // 100 / 3 cannot be represented; the line amount must stay exactly 33.33 + whatever was
        // rounded, not become quantity x rounded-unit-price.
        RatedLineItem line = new RatedLineItem(
            "API_CALLS", new BigDecimal("3"), new BigDecimal("3"),
            Money.of("33.33", USD), Money.zero(USD), Money.of("33.33", USD),
            Money.zero(USD), Money.of("33.33", USD), List.of());

        PricingResult result = new PricingResult(
            "calc-2", TenantId.of("t1"), Optional.empty(), PlanCode.of("PRO"),
            T0, USD,
            Money.of("33.33", USD), Money.zero(USD), Money.of("33.33", USD),
            Money.zero(USD), Money.of("33.33", USD), List.of(line),
            new EvaluationTrace("calc-2", T0, new java.util.ArrayList<>()));

        Invoice invoice = InvoiceFactory.draftFrom(result, "inv-2", CustomerId.of("c1"),
            T0, T0.plusSeconds(86_400), T0, null);

        assertThat(invoice.total().amount()).isEqualByComparingTo("33.33");
        assertThat(invoice.subtotal().amount()).isEqualByComparingTo("33.33");
    }
}
