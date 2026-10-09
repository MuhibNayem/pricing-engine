package com.saas.pricing.starter;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.InvoiceFactory;
import com.saas.pricing.core.spi.InvoiceRepository;
import com.saas.pricing.core.spi.impl.InMemoryInvoiceRepository;
import com.saas.pricing.starter.repository.InMemoryRateCardRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Numbering as the finalize path actually uses it.
 *
 * <p>The store tests prove allocation is gapless and the format rules are enforced; only this proves
 * the two are connected — that a document number appears exactly when an invoice becomes a demand
 * for payment, and not before.
 */
class InvoiceNumberingWiringTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    private final AtomicInteger sequence = new AtomicInteger();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
            () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1")
        .withBean(RateCardRepository.class, () -> {
            var repo = new InMemoryRateCardRepository();
            repo.save(RateCard.of("rc", TENANT, PLAN, 1, T0,
                List.of(RatePlanItem.of("SEATS", "seats",
                    PricingModel.PerUnitModel.of(new BigDecimal("25.00")), USD))));
            return repo;
        });

    /** Persists a fresh draft and returns its id. */
    private String draft(org.springframework.context.ApplicationContext context) {
        var rated = context.getBean(EnterprisePricingService.class).evaluate(
            com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(USD).customerId("c1").item("SEATS", 4).build());
        var id = "inv-" + sequence.incrementAndGet();
        context.getBean(InvoiceLifecycleService.class).createDraft(InvoiceFactory.draftFrom(
            rated, id, com.saas.pricing.core.model.CustomerId.of("c1"),
            T0, T0.plusSeconds(86400), T0, "TX_STANDARD"));
        return id;
    }

    @Test
    @DisplayName("a draft carries no number, and finalizing allocates one")
    void numberIsAllocatedAtFinalization() {
        contextRunner.run(context -> {
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            var id = draft(context);

            assertThat(context.getBean(InvoiceRepository.class).findInvoice(TENANT, id)
                .orElseThrow().invoiceNumber())
                .as("a draft is editable and often abandoned; a number taken now would be a gap")
                .isEmpty();

            assertThat(lifecycle.finalizeInvoice(TENANT, id, null).invoiceNumber())
                .contains("INV-0001");
        });
    }

    @Test
    @DisplayName("successive finalizations issue 1, 2, 3 with no repeats")
    void successiveFinalizationsAreSequential() {
        contextRunner.run(context -> {
            var lifecycle = context.getBean(InvoiceLifecycleService.class);

            assertThat(lifecycle.finalizeInvoice(TENANT, draft(context), null).invoiceNumber())
                .contains("INV-0001");
            assertThat(lifecycle.finalizeInvoice(TENANT, draft(context), null).invoiceNumber())
                .contains("INV-0002");
            assertThat(lifecycle.finalizeInvoice(TENANT, draft(context), null).invoiceNumber())
                .contains("INV-0003");
        });
    }

    /**
     * A migrated invoice keeps the number it already has.
     *
     * <p>Reassigning one would produce a second document with a number that already exists in the
     * tenant's history and on the customer's records.
     */
    @Test
    @DisplayName("an explicitly supplied number is honoured verbatim")
    void suppliedNumberIsHonoured() {
        contextRunner.run(context -> {
            var lifecycle = context.getBean(InvoiceLifecycleService.class);

            assertThat(lifecycle.finalizeInvoice(TENANT, draft(context), "LEGACY-0042").invoiceNumber())
                .contains("LEGACY-0042");

            assertThat(lifecycle.finalizeInvoice(TENANT, draft(context), null).invoiceNumber())
                .as("an imported number must not consume a slot in the tenant's own series")
                .contains("INV-0001");
        });
    }

    /**
     * A failed finalization must cost neither a duplicate nor a hole.
     *
     * <p>Without the release, every transient error would punch a permanent, unrecoverable hole in
     * the tenant's tax series.
     */
    @Test
    @DisplayName("a finalization that fails to persist returns the number")
    void failedFinalizationReleasesTheNumber() {
        contextRunner
            .withBean(InvoiceRepository.class, FailingOnFinalize::new)
            .run(context -> {
                var lifecycle = context.getBean(InvoiceLifecycleService.class);
                var id = draft(context);

                assertThatThrownBy(() -> lifecycle.finalizeInvoice(TENANT, id, null))
                    .isInstanceOf(IllegalStateException.class);

                // The numbering service still hands out 0001 next, so the series stayed contiguous.
                var numbers = context.getBean(com.saas.pricing.core.model.invoice.InvoiceNumberService.class);
                assertThat(numbers.peekNext(TENANT, com.saas.pricing.core.model.CustomerId.of("c1")))
                    .isEqualTo("INV-0001");
            });
    }

    /** Fails only on the finalize write, so the draft still exists to be finalized. */
    private static final class FailingOnFinalize extends InMemoryInvoiceRepository {
        @Override
        public void updateInvoice(com.saas.pricing.core.model.invoice.Invoice invoice) {
            if (invoice.invoiceNumber().isPresent()) {
                throw new IllegalStateException("database unavailable");
            }
            super.updateInvoice(invoice);
        }
    }
}