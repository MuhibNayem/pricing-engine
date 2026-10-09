package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.fx.FxBooking;
import com.saas.pricing.core.model.fx.FxRate;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceLineItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An invoice's foreign-currency amount is booked at an estimate and trued up on settlement.
 *
 * <p>The property that matters: booking the estimated figure without recording the rate would leave
 * a later true-up with nothing to compare against, and the recognised revenue for a period already
 * closed would silently change.
 */
class InvoiceFxBookingTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
                  () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1");

    private static Invoice eurInvoice(InvoiceLifecycleService lifecycle) {
        Invoice draft = Invoice.draft("inv-fx", TENANT, CUSTOMER, PLAN, CurrencyUnit.EUR,
                T0, T0.plusSeconds(86400 * 30), T0)
            .addLine(InvoiceLineItem.of("LICENCE", "Licence", BigDecimal.ONE,
                Money.of("30.00", CurrencyUnit.EUR), "TX"))
            .build();
        return lifecycle.createDraft(draft).finalizeInvoice("INV-FX-1", T0);
    }

    @Test
    @DisplayName("a foreign-currency invoice is booked at the estimated rate")
    void booksAtEstimate() {
        contextRunner.run(context -> {
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            var invoice = eurInvoice(lifecycle);

            var booking = lifecycle.bookForeignCurrency(invoice, CurrencyUnit.USD,
                new FxRate(CurrencyUnit.EUR, CurrencyUnit.USD, new BigDecimal("1.20"),
                    T0, FxRate.RateSource.REFERENCE, "ECB"));

            assertThat(booking.estimatedAmount().amount()).isEqualByComparingTo("36.00");
            assertThat(booking.isSettled()).isFalse();
            assertThat(booking.invoiceId()).isEqualTo("inv-fx");
        });
    }

    @Test
    @DisplayName("settlement posts the delta as an FX loss without touching the estimate")
    void settlementPostsDelta() {
        contextRunner.run(context -> {
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            var invoice = eurInvoice(lifecycle);

            var booked = lifecycle.bookForeignCurrency(invoice, CurrencyUnit.USD,
                new FxRate(CurrencyUnit.EUR, CurrencyUnit.USD, new BigDecimal("1.20"),
                    T0, FxRate.RateSource.REFERENCE, "ECB"));

            var settled = lifecycle.settleForeignCurrency(booked,
                new FxRate(CurrencyUnit.EUR, CurrencyUnit.USD, new BigDecimal("1.10"),
                T0.plusSeconds(86400 * 15), FxRate.RateSource.SETTLEMENT, "BANK"));

            assertThat(settled.realizedAmount()).contains(Money.of("33.00", CurrencyUnit.USD));
            assertThat(settled.fxLoss().amount())
                .as("money came in worth less than booked, so a loss is posted")
                .isEqualByComparingTo("3.00");
            assertThat(booked.estimatedAmount().amount())
                .as("the booked figure for a closed period must not move")
                .isEqualByComparingTo("36.00");
        });
    }

    @Test
    @DisplayName("a domestic invoice refuses an FX booking")
    void domesticInvoiceRefusesBooking() {
        contextRunner.run(context -> {
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            Invoice domestic = lifecycle.createDraft(Invoice.draft("inv-us", TENANT, CUSTOMER, PLAN,
                    CurrencyUnit.USD, T0, T0.plusSeconds(86400 * 30), T0)
                .addLine(InvoiceLineItem.of("L", "L", BigDecimal.ONE, Money.of("10.00", CurrencyUnit.USD), ""))
                .build());

            assertThatThrownBy(() -> lifecycle.bookForeignCurrency(domestic, CurrencyUnit.USD,
                    new FxRate(CurrencyUnit.USD, CurrencyUnit.EUR, BigDecimal.ONE, T0,
                        FxRate.RateSource.SPOT, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two different currencies");
        });
    }
}