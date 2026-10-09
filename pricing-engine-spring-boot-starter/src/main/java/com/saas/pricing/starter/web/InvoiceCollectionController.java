package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.DunningSchedule;
import com.saas.pricing.core.model.collection.InvoiceCollectionService;
import com.saas.pricing.core.model.collection.PaymentMethod;
import com.saas.pricing.core.model.collection.PaymentMethodType;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.spi.InvoiceRepository;
import com.saas.pricing.core.spi.PaymentProcessor;
import com.saas.pricing.starter.tenant.TenantGuard;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Collects an invoice against a stored payment method.
 *
 * <h2>Why this is gated on a {@link PaymentProcessor}</h2>
 * The engine deliberately does not know how to reach Stripe, Adyen or a bank gateway — it defines
 * the contract and the host supplies the adapter. With no processor present the endpoint does not
 * exist, rather than existing and returning a confusing failure at the moment somebody tries to
 * take money.
 */
@RestController
@RequestMapping("/api/v1/pricing")
@ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(PaymentProcessor.class)
public class InvoiceCollectionController {

    private final InvoiceCollectionService collection;
    private final InvoiceRepository invoices;
    private final DunningSchedule schedule;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public InvoiceCollectionController(InvoiceCollectionService collection,
                                       InvoiceRepository invoices,
                                       DunningSchedule schedule,
                                       TenantGuard tenantGuard,
                                       Clock clock) {
        this.collection = Objects.requireNonNull(collection, "collection cannot be null");
        this.invoices = Objects.requireNonNull(invoices, "invoices cannot be null");
        this.schedule = Objects.requireNonNull(schedule, "schedule cannot be null");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Charges an OPEN invoice.
     *
     * <p>The response distinguishes <em>settled</em> from <em>pending</em>, because those are
     * different claims and a client that collapses them will ship goods against a bank debit that
     * may still be returned.
     */
    @PostMapping("/invoices/{invoiceId}/collect")
    public ResponseEntity<CollectionResultDto> collect(
        @PathVariable String invoiceId,
        @Valid @RequestBody CollectRequest request
    ) {
        TenantId tenantId = TenantId.of(tenantGuard.verify(request.tenantId()));

        Invoice invoice = invoices.findInvoice(tenantId, invoiceId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown invoice " + invoiceId));

        var method = new PaymentMethod(
            request.paymentMethodId(), tenantId,
            com.saas.pricing.core.model.CustomerId.of(request.customerId()),
            request.methodType(), request.processorRef(),
            java.util.Optional.ofNullable(request.country()), false,
            java.util.Optional.empty(), clock.instant());

        var result = collection.collect(invoice, method, clock.instant(), schedule);

        return ResponseEntity.status(result.isSettled() ? HttpStatus.OK : HttpStatus.ACCEPTED)
            .body(new CollectionResultDto(
                invoiceId,
                result.isSettled() ? "SETTLED" : "PENDING",
                result.attempt().attemptId(),
                result.attempt().attemptNumber(),
                result.invoice().status().name(),
                result.invoice().balanceDue().amount().toPlainString(),
                result.invoice().currency().code()));
    }

    /**
     * @param paymentMethodId the engine's identifier for the method
     * @param customerId      the customer being charged
     * @param methodType      the instrument; decides whether settlement is immediate or delayed
     * @param processorRef    the processor's identifier for that method
     */
    public record CollectRequest(
        String tenantId,
        String paymentMethodId,
        String customerId,
        PaymentMethodType methodType,
        String processorRef,
        String country
    ) {
    }

    /** @param outcome SETTLED only when the money moved; PENDING means accepted, not paid. */
    public record CollectionResultDto(
        String invoiceId,
        String outcome,
        String attemptId,
        int attemptNumber,
        String invoiceStatus,
        String balanceDue,
        String currency
    ) {
    }
}