package com.saas.pricing.starter;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.PaymentMethodType;
import com.saas.pricing.core.model.invoice.InvoiceFactory;
import com.saas.pricing.core.model.invoice.InvoiceStatus;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.PaymentProcessor;
import com.saas.pricing.starter.repository.InMemoryRateCardRepository;
import com.saas.pricing.starter.web.InvoiceCollectionController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collection endpoint.
 *
 * <p>Two things are worth proving and neither is visible from the engine: that the endpoint only
 * exists when the host actually supplied a processor, and that it reports <em>pending</em> as
 * distinct from <em>settled</em> rather than collapsing them into a 200.
 */
class InvoiceCollectionEndpointTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    private final WebApplicationContextRunner base = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
            () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1")
        .withBean(RateCardRepository.class, () -> {
            var repo = new InMemoryRateCardRepository();
            repo.save(RateCard.of("rc", TENANT, PlanCode.of("PRO"), 1, T0,
                List.of(RatePlanItem.of("SEATS", "seats",
                    PricingModel.PerUnitModel.of(new BigDecimal("25.00")), CurrencyUnit.USD))));
            return repo;
        });

    /** The engine must not collect when it has no way to reach a processor. */
    @Test
    @DisplayName("without a processor there is no collection service and no endpoint")
    void absentWithoutProcessor() {
        base.run(context -> {
            assertThat(context).doesNotHaveBean(
                com.saas.pricing.core.model.collection.InvoiceCollectionService.class);
            assertThat(context).doesNotHaveBean(InvoiceCollectionController.class);
        });
    }

    private void seedOpenInvoice(org.springframework.context.ApplicationContext context, String id) {
        var rated = context.getBean(EnterprisePricingService.class).evaluate(
            com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(CurrencyUnit.USD).customerId("c1").item("SEATS", 4).build());
        context.getBean(InvoiceLifecycleService.class).createDraft(InvoiceFactory.draftFrom(
            rated, id, CustomerId.of("c1"), T0, T0.plusSeconds(86400), T0, "TX_STANDARD"));
        context.getBean(InvoiceLifecycleService.class).finalizeInvoice(TENANT, id, "INV-" + id);
    }

    private PaymentProcessor processor(PaymentProcessor.Outcome outcome) {
        return request -> outcome;
    }

    @Test
    @DisplayName("a settled card charge returns 200 and marks the invoice paid")
    void settledChargeMarksInvoicePaid() {
        base.withBean(PaymentProcessor.class,
                () -> processor(PaymentProcessor.Outcome.settled("ch_1", T0)))
            .run(context -> {
                seedOpenInvoice(context, "inv-paid");
                var controller = context.getBean(InvoiceCollectionController.class);

                var response = controller.collect("inv-paid", new InvoiceCollectionController.CollectRequest(
                    "t1", "pm-1", "c1", PaymentMethodType.CARD, "proc_1", "US"));

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody().outcome()).isEqualTo("SETTLED");
                assertThat(response.getBody().invoiceStatus()).isEqualTo(InvoiceStatus.PAID.name());
            });
    }

    /**
     * Accepted is not paid.
     *
     * <p>An accepted ACH debit must answer 202 with the invoice still OPEN. Returning 200 and
     * marking it paid is the defect this distinction exists to prevent.
     */
    @Test
    @DisplayName("an accepted ACH debit returns 202 and leaves the invoice unpaid")
    void pendingChargeLeavesInvoiceUnpaid() {
        base.withBean(PaymentProcessor.class,
                () -> processor(PaymentProcessor.Outcome.pending("ch_2")))
            .run(context -> {
                seedOpenInvoice(context, "inv-pending");
                var controller = context.getBean(InvoiceCollectionController.class);

                var response = controller.collect("inv-pending", new InvoiceCollectionController.CollectRequest(
                    "t1", "pm-1", "c1", PaymentMethodType.ACH_DEBIT, "proc_1", "US"));

                assertThat(response.getStatusCode().value())
                    .as("accepted is not settled, so this must not answer 200")
                    .isEqualTo(202);
                assertThat(response.getBody().outcome()).isEqualTo("PENDING");
                assertThat(response.getBody().invoiceStatus()).isEqualTo(InvoiceStatus.OPEN.name());
                assertThat(response.getBody().balanceDue()).isEqualTo("100.00");
            });
    }

    /** The endpoint is tenant-guarded like every other. */
    @Test
    @DisplayName("the endpoint refuses a tenant the caller is not authenticated as")
    void endpointIsTenantGuarded() {
        base.withBean(PaymentProcessor.class,
                () -> processor(PaymentProcessor.Outcome.settled("ch_3", T0)))
            .run(context -> {
                seedOpenInvoice(context, "inv-guard");
                var controller = context.getBean(InvoiceCollectionController.class);

                org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.collect(
                    "inv-guard", new InvoiceCollectionController.CollectRequest(
                        "someone-else", "pm-1", "c1", PaymentMethodType.CARD, "proc_1", "US")))
                    .isInstanceOf(RuntimeException.class);
            });
    }
}