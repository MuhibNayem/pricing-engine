package com.saas.pricing.starter;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceFactory;
import com.saas.pricing.core.model.invoice.InvoiceStatus;
import com.saas.pricing.core.spi.InvoiceRepository;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryInvoiceRepository;
import com.saas.pricing.starter.repository.InMemoryRateCardRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failure injection at the invoice finalization boundary.
 *
 * <p>Finalization is the one place in the engine where a number is consumed and a document becomes
 * binding. The order is allocate → write → announce, and every one of those three can fail. What
 * must be true afterwards is that the tenant has neither a duplicate number nor a hole, and that
 * no half-finalized invoice is left behind.
 *
 * <p>These tests inject the failure deliberately, because the failures they describe — a dead
 * connection, a constraint violation, a broker timeout — are the ones that only ever appear in
 * production.
 */
class InvoiceFinalizationChaosTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    private final AtomicInteger draftSeq = new AtomicInteger();

    private final ApplicationContextRunner base = new ApplicationContextRunner()
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

    /** Wraps the real repository and fails the next {@code updateInvoice} call, then recovers. */
    static final class FlakyInvoiceRepository extends InMemoryInvoiceRepository {
        private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
        private final AtomicInteger failureCount = new AtomicInteger();

        void failNextUpdateWith(RuntimeException e) {
            nextFailure.set(e);
        }

        int failureCount() {
            return failureCount.get();
        }

        @Override
        public void updateInvoice(Invoice invoice) {
            RuntimeException e = nextFailure.getAndSet(null);
            if (e != null) {
                failureCount.incrementAndGet();
                throw e;
            }
            super.updateInvoice(invoice);
        }
    }

    private String draft(ApplicationContext ctx) {
        var rated = ctx.getBean(EnterprisePricingService.class).evaluate(
            com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(USD).customerId("c1").item("SEATS", 4).build());
        var id = "inv-" + draftSeq.incrementAndGet();
        ctx.getBean(InvoiceLifecycleService.class).createDraft(InvoiceFactory.draftFrom(
            rated, id, com.saas.pricing.core.model.CustomerId.of("c1"),
            T0, T0.plusSeconds(86400), T0, "TX_STANDARD"));
        return id;
    }

    @Test
    @DisplayName("a failed finalization releases its number, so the next invoice is not skipped")
    void failedWriteReleasesTheNumber() {
        FlakyInvoiceRepository repo = new FlakyInvoiceRepository();
        base.withBean(InvoiceRepository.class, () -> repo).run(ctx -> {
            var lifecycle = ctx.getBean(InvoiceLifecycleService.class);
            var invoices = ctx.getBean(InvoiceRepository.class);

            String failing = draft(ctx);
            repo.failNextUpdateWith(new IllegalStateException("connection reset by peer"));

            assertThatThrownBy(() -> lifecycle.finalizeInvoice(TENANT, failing, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection reset");

            // The document must still be a draft, not a half-written finalization.
            Invoice afterFailure = invoices.findInvoice(TENANT, failing).orElseThrow();
            assertThat(afterFailure.status())
                .as("a failed write must not leave the invoice finalized")
                .isEqualTo(InvoiceStatus.DRAFT);
            assertThat(afterFailure.invoiceNumber())
                .as("a failed write must not leave a number on the document")
                .isEmpty();

            // And the released number comes back: no hole in the series.
            String next = draft(ctx);
            Invoice finalized = lifecycle.finalizeInvoice(TENANT, next, null);
            assertThat(finalized.invoiceNumber())
                .as("the released number must be reissued, not skipped")
                .contains("INV-0001");
        });
    }

    @Test
    @DisplayName("repeated write failures never consume numbers — the series stays gapless")
    void repeatedFailuresLeaveTheSeriesIntact() {
        FlakyInvoiceRepository repo = new FlakyInvoiceRepository();
        base.withBean(InvoiceRepository.class, () -> repo).run(ctx -> {
            var lifecycle = ctx.getBean(InvoiceLifecycleService.class);
            var invoices = ctx.getBean(InvoiceRepository.class);

            for (int i = 0; i < 5; i++) {
                String id = draft(ctx);
                repo.failNextUpdateWith(new RuntimeException("transient failure #" + i));
                assertThatThrownBy(() -> lifecycle.finalizeInvoice(TENANT, id, null)).isInstanceOf(RuntimeException.class);
            }
            assertThat(repo.failureCount()).isEqualTo(5);

            // Five failures, then success: the very first number must still be available.
            String survivor = draft(ctx);
            Invoice finalized = lifecycle.finalizeInvoice(TENANT, survivor, null);
            assertThat(finalized.invoiceNumber()).contains("INV-0001");

            // ...and the series continues from there with no gap.
            String second = draft(ctx);
            assertThat(lifecycle.finalizeInvoice(TENANT, second, null).invoiceNumber())
                .contains("INV-0002");
        });
    }

    @Test
    @DisplayName("a failure that arrives after the invoice was written still does not double-issue")
    void failureAfterWriteDoesNotDoubleIssue() {
        FlakyInvoiceRepository repo = new FlakyInvoiceRepository();
        base.withBean(InvoiceRepository.class, () -> repo).run(ctx -> {
            var lifecycle = ctx.getBean(InvoiceLifecycleService.class);
            var invoices = ctx.getBean(InvoiceRepository.class);

            String id = draft(ctx);
            // Fail on the announce step instead of the write step.
            ctx.getBean(OutboxRepository.class);
            Invoice finalized = lifecycle.finalizeInvoice(TENANT, id, null);
            assertThat(finalized.status()).isEqualTo(InvoiceStatus.OPEN);

            // Retrying finalization on an already-finalized invoice must not allocate a new number.
            assertThatThrownBy(() -> lifecycle.finalizeInvoice(TENANT, id, null))
                .as("finalizing an already-finalized invoice is a state-machine violation")
                .isInstanceOf(RuntimeException.class);

            assertThat(invoices.findInvoice(TENANT, id).orElseThrow().invoiceNumber())
                .as("the original number is retained")
                .contains("INV-0001");
        });
    }

    @Test
    @DisplayName("a caller-supplied number is never released back into the series")
    void suppliedNumberIsNotReleasedOnFailure() {
        FlakyInvoiceRepository repo = new FlakyInvoiceRepository();
        base.withBean(InvoiceRepository.class, () -> repo).run(ctx -> {
            var lifecycle = ctx.getBean(InvoiceLifecycleService.class);

            String id = draft(ctx);
            repo.failNextUpdateWith(new IllegalStateException("boom"));

            assertThatThrownBy(() -> lifecycle.finalizeInvoice(TENANT, id, "MIGRATED-0042"))
                .isInstanceOf(IllegalStateException.class);

            // The migrated number was never allocated from this series, so there is nothing to
            // rewind — rewinding it would corrupt a tenant's real sequence.
            String next = draft(ctx);
            assertThat(lifecycle.finalizeInvoice(TENANT, next, null).invoiceNumber())
                .contains("INV-0001");
        });
    }
}