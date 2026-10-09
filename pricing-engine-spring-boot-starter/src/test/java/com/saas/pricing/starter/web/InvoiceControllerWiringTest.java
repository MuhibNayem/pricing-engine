package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceStatus;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import com.saas.pricing.starter.tenant.TenantResolver;
import com.saas.pricing.starter.web.dto.InvoiceDtos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invoice API must be reachable and must actually invoice a real rating.
 *
 * <p>Two silent gaps prompted this class. {@code InvoiceRepository} was never registered as a bean,
 * and {@code InvoiceController} was never wired - both compiled, both passed every existing test,
 * and both left the entire invoice subsystem unreachable from a running application. These tests
 * exist so that class of omission cannot return.
 */
class InvoiceControllerWiringTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final CurrencyUnit USD = CurrencyUnit.USD;

    // Web-aware: the controller beans are @ConditionalOnWebApplication, so a plain
    // ApplicationContextRunner would silently skip them - which is exactly how a missing bean hides.
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(TenantResolver.class, () -> (TenantResolver) () -> "t1");

    private void seedRateCard(RateCardRepository repo) {
        var item = RatePlanItem.of("SEATS", "seats",
            PricingModel.PerUnitModel.of(new BigDecimal("25.00")), USD);
        repo.save(RateCard.of("rc", TENANT, PLAN, 1, Instant.parse("2026-01-01T00:00:00Z"),
            java.util.List.of(item)));
    }

    /**
     * Issues a draft with an explicit idempotency key.
     *
     * <p>These tests are not about replay, so they unwrap the ordinary created response.
     */
    private InvoiceDtos.InvoiceDto createDraft(InvoiceController controller, String idempotencyKey,
                                               InvoiceDtos.CreateInvoiceRequest request) {
        return controller.createDraft(request, idempotencyKey).getBody();
    }

    @Test
    @DisplayName("the invoice repository and controller are both registered")
    void beansAreRegistered() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(com.saas.pricing.core.spi.InvoiceRepository.class);
            assertThat(context).hasSingleBean(InvoiceController.class);
        });
    }

    @Test
    @DisplayName("a rated request can be turned into a finalized, paid invoice")
    void ratedRequestBecomesPaidInvoice() {
        contextRunner.withBean(RateCardRepository.class, () -> {
            var repo = new InMemoryRateCardRepository();
            seedRateCard(repo);
            return repo;
        }).run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var controller = context.getBean(InvoiceController.class);
            var invoices = context.getBean(com.saas.pricing.core.spi.InvoiceRepository.class);

            var rated = service.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO")
                .evaluationTime(Instant.parse("2026-10-08T12:00:00Z"))
                .targetCurrency(USD).customerId("c1")
                .item("SEATS", 4)
                .build());

            var created = createDraft(controller, "key-inv-1",
                new InvoiceDtos.CreateInvoiceRequest(
                    "inv-1", "c1", "t1", "PRO", rated.calculationId(), null,
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-11-01T00:00:00Z"), "TX_STANDARD"));

            assertThat(created.status()).isEqualTo("DRAFT");
            assertThat(created.subtotal()).isEqualTo("100.00");

            var finalized = controller.finalizeInvoice("inv-1", "t1", "INV-2026-0001");
            assertThat(finalized.getBody().status()).isEqualTo("OPEN");
            assertThat(finalized.getBody().invoiceNumber()).isEqualTo("INV-2026-0001");

            var paid = controller.recordPayment("inv-1", "t1",
                new InvoiceDtos.RecordPaymentRequest("100.00", "USD"));
            assertThat(paid.getBody().status()).isEqualTo("PAID");
            assertThat(paid.getBody().balanceDue()).isEqualTo("0.00");

            var reloaded = invoices.findInvoice(TENANT, "inv-1").orElseThrow();
            assertThat(reloaded.status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(reloaded.isSettled()).isTrue();
        });
    }

    @Test
    @DisplayName("an invoice cannot be raised from client-supplied amounts")
    void invoiceMustComeFromARating() {
        contextRunner.withBean(RateCardRepository.class, () -> {
            var repo = new InMemoryRateCardRepository();
            seedRateCard(repo);
            return repo;
        }).run(context -> {
            var controller = context.getBean(InvoiceController.class);

            // Without this guard a caller could invoice a customer whatever it liked by passing
            // client-side line amounts.
            assertThatThrownBy(() -> controller.createDraft(new InvoiceDtos.CreateInvoiceRequest(
                "inv-bad", "c1", "t1", "PRO", "made-up-calculation-id", null,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"), null), "key-inv-bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("computed rating");
        });
    }

    @Test
    @DisplayName("a credit note is refused when it would exceed the invoice")
    void creditCapEnforcedThroughApi() {
        contextRunner.withBean(RateCardRepository.class, () -> {
            var repo = new InMemoryRateCardRepository();
            seedRateCard(repo);
            return repo;
        }).run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var controller = context.getBean(InvoiceController.class);

            var rated = service.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO")
                .evaluationTime(Instant.parse("2026-10-08T12:00:00Z"))
                .targetCurrency(USD).customerId("c1").item("SEATS", 4).build());

            createDraft(controller, "key-inv-2", new InvoiceDtos.CreateInvoiceRequest("inv-2", "c1", "t1", "PRO",
                rated.calculationId(), null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), null));
            controller.finalizeInvoice("inv-2", "t1", "INV-2026-0002");

            var credit = controller.creditInvoice("inv-2",
                new InvoiceDtos.CreateCreditNoteRequest("cn-1", "service cancelled", "REFUND", "t1"));
            assertThat(credit.getBody().status()).isEqualTo("ISSUED");

            // In-memory repo recomputes the cap from notes in force, so a second full credit is
            // refused rather than refunding more than was invoiced.
            assertThatThrownBy(() -> controller.creditInvoice("inv-2",
                new InvoiceDtos.CreateCreditNoteRequest("cn-2", "duplicate", "REFUND", "t1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot exceed");
        });
    }
}