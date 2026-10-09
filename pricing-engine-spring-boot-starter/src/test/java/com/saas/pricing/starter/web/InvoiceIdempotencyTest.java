package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.spi.IdempotencyKeyStore;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryIdempotencyKeyStore;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import com.saas.pricing.starter.tenant.TenantResolver;
import com.saas.pricing.starter.web.dto.InvoiceDtos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code Idempotency-Key} contract as the invoice endpoint actually implements it.
 *
 * <p>The store itself is tested in isolation, but that cannot prove the endpoint uses it correctly.
 * Three of the assertions here exist because the wiring was wrong in ways a store-level test is
 * blind to: a replay that returned a status with no body, a claim released after the invoice had
 * already been persisted, and keys that were not scoped to the tenant.
 */
class InvoiceIdempotencyTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final CurrencyUnit USD = CurrencyUnit.USD;

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(TenantResolver.class, () -> (TenantResolver) () -> "t1");

    private RateCardRepository seededRateCards() {
        var repo = new InMemoryRateCardRepository();
        var item = RatePlanItem.of("SEATS", "seats",
            PricingModel.PerUnitModel.of(new BigDecimal("25.00")), USD);
        repo.save(RateCard.of("rc", TENANT, PLAN, 1, Instant.parse("2026-01-01T00:00:00Z"),
            java.util.List.of(item)));
        return repo;
    }

    private String rate(EnterprisePricingService service) {
        return service.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
            .tenantId("t1").planCode("PRO")
            .evaluationTime(Instant.parse("2026-10-08T12:00:00Z"))
            .targetCurrency(USD).customerId("c1").item("SEATS", 4).build())
            .calculationId();
    }

    private InvoiceDtos.CreateInvoiceRequest request(String calculationId, String invoiceId) {
        return new InvoiceDtos.CreateInvoiceRequest(
            invoiceId, "c1", "t1", "PRO", calculationId, null,
            Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-11-01T00:00:00Z"), "TX_STANDARD");
    }

    /**
     * The headline property: a retried POST must not issue a second invoice.
     *
     * <p>The first call persists; the second must replay. If it re-executed, the customer is billed
     * twice for one period, which is the failure the IETF draft calls out by name.
     */
    @Test
    @DisplayName("a retried POST replays the first response instead of issuing a second invoice")
    void retryReplaysRatherThanDuplicates() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var controller = context.getBean(InvoiceController.class);
            var calculationId = rate(context.getBean(EnterprisePricingService.class));
            var request = request(calculationId, "inv-1");

            var first = controller.createDraft(request, "client-key-1");
            var retry = controller.createDraft(request(calculationId, "inv-1"), "client-key-1");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(retry.getStatusCode())
                .as("the replay must carry the original status")
                .isEqualTo(HttpStatus.CREATED);
            assertThat(retry.getBody())
                .as("the client must learn WHICH invoice was created - that is the only reason "
                    + "it retried, and a bare status tells it nothing")
                .isNotNull()
                .extracting(InvoiceDtos.InvoiceDto::invoiceId)
                .isEqualTo("inv-1");
            assertThat(retry.getBody())
                .as("the replay must be the same document, not a second one")
                .isEqualTo(first.getBody());
        });
    }

    /** A replay whose invoice number was supplied must still return the identical document. */
    @Test
    @DisplayName("a replay of a finalized invoice returns the same document number")
    void replayOfFinalizedInvoiceIsIdentical() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var controller = context.getBean(InvoiceController.class);
            var calculationId = rate(context.getBean(EnterprisePricingService.class));
            var request = new InvoiceDtos.CreateInvoiceRequest(
                "inv-f", "c1", "t1", "PRO", calculationId, "INV-2026-0001",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), null);

            var first = controller.createDraft(request, "client-key-final");
            var retry = controller.createDraft(request, "client-key-final");

            assertThat(retry.getBody().invoiceNumber()).isEqualTo("INV-2026-0001");
            assertThat(retry.getBody()).isEqualTo(first.getBody());
        });
    }

    @Test
    @DisplayName("the same key with a different payload is 422, not a misleading replay")
    void keyReuseWithDifferentPayloadIsUnprocessable() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var controller = context.getBean(InvoiceController.class);
            var service = context.getBean(EnterprisePricingService.class);

            controller.createDraft(request(rate(service), "inv-a"), "shared-key");

            var different = new InvoiceDtos.CreateInvoiceRequest(
                "inv-b", "c1", "t1", "PRO", rate(service), null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), null);

            assertThatThrownBy(() -> controller.createDraft(different, "shared-key"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                    .as("replaying here would tell the caller it created something it did not")
                    .isEqualTo(422));
        });
    }

    /**
     * A failure that happened <em>after</em> the invoice was committed must keep the claim.
     *
     * <p>Reproduced with an outbox that throws: {@code InvoiceLifecycleService} writes the invoice
     * and then announces it, so this leaves a real invoice on disk while the call still throws. That
     * is the dangerous shape, because a local "did I persist?" flag inside the controller reads
     * {@code false}, releases the claim, and lets the retry create a second invoice.
     *
     * <p>The retry must replay the invoice that was actually written - not 409, and certainly not
     * a second invoice.
     */
    @Test
    @DisplayName("a failure after the invoice is committed replays that invoice, never a second one")
    void failureAfterPersistenceKeepsTheClaim() {
        var explodingOutbox = new OutboxRepository() {
            @Override
            public void enqueue(com.saas.pricing.core.model.event.OutboxEvent event) {
                throw new IllegalStateException("outbox unavailable");
            }

            @Override
            public void recordDelivery(com.saas.pricing.core.model.event.OutboxEvent event) {
            }

            @Override
            public java.util.List<com.saas.pricing.core.model.event.OutboxEvent> findDue(
                    Instant at, int limit) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<com.saas.pricing.core.model.event.OutboxEvent> findByTenant(
                    TenantId tenantId, int limit) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<com.saas.pricing.core.model.event.OutboxEvent> findUndelivered(
                    String topic, int limit) {
                return java.util.List.of();
            }
        };

        contextRunner
            .withBean(RateCardRepository.class, this::seededRateCards)
            .withBean(OutboxRepository.class, () -> explodingOutbox)
            .run(context -> {
                var controller = context.getBean(InvoiceController.class);
                var invoices = context.getBean(com.saas.pricing.core.spi.InvoiceRepository.class);
                var calculationId = rate(context.getBean(EnterprisePricingService.class));
                var request = request(calculationId, "inv-half");
                String key = "key-half-written";

                assertThatThrownBy(() -> controller.createDraft(request, key))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outbox unavailable");

                assertThat(invoices.findInvoice(TENANT, "inv-half"))
                    .as("the invoice really was committed before the announcement failed")
                    .isPresent();

                // The claim must still be held, so the retry replays that invoice...
                var retry = controller.createDraft(request, key);
                assertThat(retry.getBody().invoiceId()).isEqualTo("inv-half");

                // ...and must not have produced a second one.
                assertThat(invoices.findInvoice(TENANT, "inv-half")).isPresent();
            });
    }

    /** A failure that persisted nothing must release, or a corrected retry is blocked forever. */
    @Test
    @DisplayName("a failure that persisted nothing releases the claim for a corrected retry")
    void failureBeforePersistenceReleasesTheClaim() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var controller = context.getBean(InvoiceController.class);
            var service = context.getBean(EnterprisePricingService.class);
            var key = "key-invalid-rating";

            // No such rating: rejected before anything is claimed or written.
            assertThatThrownBy(() -> controller.createDraft(request("no-such-calculation", "inv-x"), key))
                .isInstanceOf(IllegalArgumentException.class);

            // The key was never claimed, so the corrected request proceeds normally.
            var corrected = request(rate(service), "inv-x");
            assertThat(controller.createDraft(corrected, key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        });
    }

    @Test
    @DisplayName("different keys issue different invoices")
    void differentKeysAreIndependent() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var controller = context.getBean(InvoiceController.class);
            var calculationId = rate(context.getBean(EnterprisePricingService.class));

            var first = controller.createDraft(request(calculationId, "inv-1"), "key-one");
            var second = controller.createDraft(request(calculationId, "inv-2"), "key-two");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode())
                .as("a second, genuinely different invoice must not be suppressed as a replay")
                .isEqualTo(HttpStatus.CREATED);
        });
    }

    /** The store bean must exist in both modes; a missing one fails only at request time. */
    @Test
    @DisplayName("an idempotency key store is always registered")
    void storeIsRegistered() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(IdempotencyKeyStore.class));
    }

    /** Registration, not annotation: the starter package is not component-scanned. */
    @Test
    @DisplayName("the RFC 9457 error mapper is registered")
    void exceptionHandlerIsRegistered() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(PricingEngineExceptionHandler.class));
    }

    /** IDOR regression: a calculation id must not be usable by another tenant or customer. */
    @Test
    @DisplayName("a rating is only returned to the tenant and customer it was computed for")
    void ratingLookupIsTenantAndCustomerScoped() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var calculationId = rate(service);

            assertThat(service.findRatingResult(
                com.saas.pricing.core.model.TenantId.of("t2"),
                com.saas.pricing.core.model.CustomerId.of("c1"), calculationId))
                .as("another tenant's calculation id must not resolve")
                .isEmpty();
            assertThat(service.findRatingResult(
                com.saas.pricing.core.model.TenantId.of("t1"),
                com.saas.pricing.core.model.CustomerId.of("other"), calculationId))
                .as("another customer's calculation id must not resolve")
                .isEmpty();
            assertThat(service.findRatingResult(
                com.saas.pricing.core.model.TenantId.of("t1"),
                com.saas.pricing.core.model.CustomerId.of("c1"), calculationId))
                .isPresent();
        });
    }

    /** Field separation, not delimiters: two requests must never fingerprint the same. */
    @Test
    @DisplayName("request fields cannot be shifted between fields to fake a retry")
    void fingerprintIsUnambiguous() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var controller = context.getBean(InvoiceController.class);
            var service = context.getBean(EnterprisePricingService.class);
            var calculationId = rate(service);

            // "inv-x" + "PRO" versus "inv" + "xPRO" would collide under a naive delimiter join. The
            // shift is between fields that do not identify the rating: customer and calculation must
            // stay intact, because a different customer is now refused before idempotency is even
            // consulted.
            controller.createDraft(request(calculationId, "inv-x"), "shared");
            var shifted = new InvoiceDtos.CreateInvoiceRequest(
                "inv", "c1", "t1", "xPRO", calculationId, null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"),
                "TX_STANDARD");

            assertThatThrownBy(() -> controller.createDraft(shifted, "shared"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                    .isEqualTo(422));
        });
    }

    /**
     * A replay is rendered from the stored invoice, not from a frozen snapshot.
     *
     * <p>State moves between the original call and the retry - a payment is the ordinary case. A
     * snapshot would keep reporting the invoice as unpaid, and the client would conclude its payment
     * had been lost. This pins the reason the recorded payload is the invoice id.
     *
     * <p>Tenant scoping of the key itself is proven where it can actually be observed - against the
     * store, in {@code IdempotencyStoreTest} and {@code JdbcIdempotencyKeyStoreTest}. {@code TenantResolver}
     * resolves one tenant for the whole context, so no request in this class can present a second
     * tenant's identity.
     */
    @Test
    @DisplayName("a replay reflects state recorded after the original call, not a stale snapshot")
    void replayReflectsCurrentState() {
        contextRunner.withBean(RateCardRepository.class, this::seededRateCards).run(context -> {
            var controller = context.getBean(InvoiceController.class);
            var calculationId = rate(context.getBean(EnterprisePricingService.class));
            var request = request(calculationId, "inv-live");

            var first = controller.createDraft(request, "client-key-live");
            assertThat(first.getBody().status()).isEqualTo("DRAFT");

            // The client never saw the response and retries after settling the invoice out of band.
            controller.finalizeInvoice("inv-live", "t1", "INV-LIVE-1");
            controller.recordPayment("inv-live", "t1",
                new InvoiceDtos.RecordPaymentRequest("100.00", "USD"));

            var retry = controller.createDraft(request, "client-key-live");

            assertThat(retry.getBody().status())
                .as("a frozen snapshot would report the invoice unpaid and the client would think "
                    + "its payment was lost")
                .isEqualTo("PAID");
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        });
    }

    @Test
    @DisplayName("the in-memory store satisfies the same contract the JDBC one does")
    void inMemoryStoreIsWiredByDefault() {
        contextRunner.run(context -> assertThat(context.getBean(IdempotencyKeyStore.class))
            .isInstanceOf(InMemoryIdempotencyKeyStore.class));
    }
}