package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.model.entitlement.EntitlementReconciler;
import com.saas.pricing.core.model.idempotency.IdempotencyDecision;
import com.saas.pricing.core.model.idempotency.IdempotencyRecord;
import com.saas.pricing.core.model.invoice.CreditNote;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceFactory;
import com.saas.pricing.core.model.invoice.InvoiceLineItem;
import com.saas.pricing.core.spi.IdempotencyKeyStore;
import com.saas.pricing.core.spi.InvoiceRepository;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.tenant.TenantGuard;
import com.saas.pricing.starter.web.dto.InvoiceDtos;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Invoicing and reconciliation API.
 *
 * <p>Invoices are financial documents with a lifecycle, so the surface is deliberately narrow: a
 * draft may gain lines, a finalized invoice may only be paid, voided or credited. Those rules live
 * in the {@link Invoice} aggregate, and this controller exists only to expose them - it does not
 * re-implement them, because two implementations of an invoice state machine is how they diverge.
 *
 * <p>Every endpoint is tenant-scoped through {@link TenantGuard}; a body-supplied {@code tenantId}
 * that disagrees with the authenticated tenant is rejected rather than honoured.
 */
@RestController
@RequestMapping("/api/v1/pricing")
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
public class InvoiceController {

    private final EnterprisePricingService pricingService;
    private final InvoiceRepository invoiceRepository;
    private final com.saas.pricing.starter.InvoiceLifecycleService lifecycle;
    private final TenantGuard tenantGuard;
    private final IdempotencyKeyStore idempotencyStore;

    /** Published retention window for idempotency keys, per the IETF header guidance. */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    public InvoiceController(EnterprisePricingService pricingService,
                             InvoiceRepository invoiceRepository,
                             com.saas.pricing.starter.InvoiceLifecycleService lifecycle,
                             TenantGuard tenantGuard,
                             IdempotencyKeyStore idempotencyStore) {
        this.pricingService = pricingService;
        this.invoiceRepository = invoiceRepository;
        this.lifecycle = lifecycle;
        this.tenantGuard = tenantGuard;
        this.idempotencyStore = idempotencyStore;
    }

    private Instant now() {
        return Instant.now();
    }

    /**
     * Creates a draft invoice from a previously computed rating.
     *
     * <p><strong>The {@code Idempotency-Key} header is required.</strong> This endpoint issues a
     * financial document, and the IETF draft is explicit that duplicate records "involving any kind
     * of money transfer MUST NOT be allowed". A retrying client, a proxy, or a lost response is not
     * an edge case here; it is the normal way HTTP behaves. Making the header optional would leave
     * the duplicate unguarded for exactly the clients that most need it.
     *
     * <p>A missing header is rejected by binding as <strong>400 Bad Request</strong> before this
     * method runs; {@code 409} means an identical attempt is still running and {@code 422} means
     * the key was reused for a different payload.
     */
    @PostMapping("/invoices")
    public ResponseEntity<InvoiceDtos.InvoiceDto> createDraft(
        @Valid @RequestBody InvoiceDtos.CreateInvoiceRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        TenantId tenantId = TenantId.of(tenantGuard.verify(request.tenantId()));
        CustomerId customerId = CustomerId.of(request.customerId());

        // Validate before claiming. A request that cannot succeed must not burn the client's key,
        // or a corrected retry would be rejected as a conflict with the failed attempt.
        var result = pricingService.findRatingResult(tenantId, customerId, request.calculationId())
            .orElseThrow(() -> new IllegalArgumentException(
                "No rating result for calculation '" + request.calculationId()
                    + "' for this tenant and customer; an invoice must be raised from a computed "
                    + "rating, not from client-supplied amounts"));

        Invoice draft = InvoiceFactory.draftFrom(result, request.invoiceId(), customerId,
            request.periodStart(), request.periodEnd(), now(), request.taxCode());

        IdempotencyDecision decision = idempotencyStore.decide(
            tenantId, idempotencyKey, fingerprintOf(request), IDEMPOTENCY_TTL, Instant.now());

        if (decision.action() == IdempotencyDecision.Action.REPLAY) {
            // The recorded payload is the invoice id, and the document is re-read rather than
            // replayed from a stored snapshot. Two reasons: a snapshot goes stale the moment the
            // invoice moves (a payment recorded between the call and the retry would still report
            // the old state), and serialising one would add a failure mode that fires *after* the
            // money has moved.
            String recordedId = decision.stored().responseBody();
            Invoice replayed = invoiceRepository.findInvoice(tenantId, recordedId)
                .orElseThrow(() -> new IllegalStateException(
                    "Idempotency-Key " + idempotencyKey + " records invoice '" + recordedId
                        + "', which is no longer retrievable for tenant " + tenantId.value()));
            return ResponseEntity.status(decision.httpStatus()).body(toDto(replayed));
        }
        if (decision.action() == IdempotencyDecision.Action.IN_FLIGHT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "An identical request is still in progress for this Idempotency-Key");
        }
        if (decision.action() == IdempotencyDecision.Action.CONFLICT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "This Idempotency-Key was already used for a different request body");
        }

        IdempotencyRecord claim = decision.claim();

        // Through the lifecycle service, never invoiceRepository.createInvoice directly: a state
        // change persisted without its outbox event cannot be recovered.
        Invoice created;
        try {
            created = lifecycle.createDraft(draft);
            if (request.invoiceNumber() != null && !request.invoiceNumber().isBlank()) {
                created = lifecycle.finalizeInvoice(tenantId, created.invoiceId(), request.invoiceNumber());
            }
        } catch (RuntimeException e) {
            settleFailedClaim(tenantId, claim, request.invoiceId());
            throw e;
        }

        idempotencyStore.complete(tenantId, claim, HttpStatus.CREATED.value(), created.invoiceId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    /**
     * Decides whether a failed attempt may be retried, by asking whether it left anything behind.
     *
     * <p>A local "did I persist?" flag is not good enough here. {@code InvoiceLifecycleService}
     * writes the invoice and <em>then</em> announces it, so a failure in the announcement leaves the
     * invoice committed on disk while the call still throws - the flag would read {@code false},
     * the claim would be released, and the client's retry would create a second invoice. That is
     * precisely the duplicate this endpoint exists to prevent, reached by a path that looks correct.
     *
     * <p>So the repository is asked instead of inferred:
     * <ul>
     *   <li>the invoice is there &rarr; the work happened, so the key records it and a retry
     *       replays that invoice;</li>
     *   <li>the invoice is not there &rarr; nothing happened, so the claim is released and a
     *       corrected retry is allowed.</li>
     * </ul>
     *
     * <p>If the lookup itself fails the answer is unknown, and the claim is held. Unknown must not
     * be resolved as "safe to retry" when the consequence is a duplicate invoice; the TTL releases
     * the key eventually.
     */
    private void settleFailedClaim(TenantId tenantId, IdempotencyRecord claim, String invoiceId) {
        java.util.Optional<Invoice> persisted;
        try {
            persisted = invoiceRepository.findInvoice(tenantId, invoiceId);
        } catch (RuntimeException lookupFailure) {
            return;
        }
        if (persisted.isPresent()) {
            idempotencyStore.complete(tenantId, claim, HttpStatus.CREATED.value(), persisted.get().invoiceId());
        } else {
            idempotencyStore.release(tenantId, claim);
        }
    }

    /**
     * A stable description of everything that determines the outcome of this request.
     *
     * <p>Length-prefixed rather than delimiter-joined: a delimiter that a client can put inside a
     * field value ({@code "a|b"} vs {@code "a", "b"}) makes two different requests hash the same,
     * which would replay one invoice's response for another. {@link String#valueOf(Object)} on the
     * record is not used either - it silently changes when the DTO gains a field, and includes
     * nothing about which fields were meant to matter.
     */
    private static String fingerprintOf(InvoiceDtos.CreateInvoiceRequest request) {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[] {
            request.invoiceId(), request.customerId(), request.tenantId(), request.planCode(),
            request.calculationId(), request.invoiceNumber(),
            String.valueOf(request.periodStart()), String.valueOf(request.periodEnd()),
            request.taxCode() }) {
            // Length-prefixed, with null distinguished from empty so an absent tax code and an
            // empty one are not conflated.
            String value = field == null ? "\u0000" : field;
            canonical.append(value.length()).append(':').append(value);
        }
        return IdempotencyRecord.fingerprintOf(canonical.toString());
    }

    private static String fingerprintOf(InvoiceDtos.PlanChangeRequest request) {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[] {
            request.invoiceId(), request.tenantId(), request.customerId(), request.planCode(),
            request.currency(), request.itemCode(), request.oldPlanCode(), request.newPlanCode(),
            String.valueOf(request.periodStart()), String.valueOf(request.periodEnd()),
            String.valueOf(request.effectiveAt()), String.valueOf(request.recordedAt()) }) {
            String value = field == null ? "\u0000" : field;
            canonical.append(value.length()).append(':').append(value);
        }
        return IdempotencyRecord.fingerprintOf(canonical.toString());
    }

    /**
     * Drafts an invoice for a mid-period plan change.
     *
     * <p>Produces the credit/debit pair rather than a single net figure: the customer needs to see
     * which plan they were charged for, and on a downgrade the credit is the money they are owed.
     *
     * <p>Both full-period prices are rated by the engine from the rate cards in force at
     * {@code effectiveAt}. The request carries no prices, because an endpoint that lets a caller
     * state what a plan costs lets it mint arbitrary credits.
     *
     * <p>Like invoice creation, this moves money and therefore requires an {@code Idempotency-Key}.
     */
    @PostMapping("/invoices/plan-change")
    public ResponseEntity<InvoiceDtos.InvoiceDto> draftForPlanChange(
        @Valid @RequestBody InvoiceDtos.PlanChangeRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        TenantId tenantId = TenantId.of(tenantGuard.verify(request.tenantId()));
        CustomerId customerId = CustomerId.of(request.customerId());
        var currency = CurrencyUnit.of(request.currency());
        Instant recordedAt = request.recordedAt() != null ? request.recordedAt() : Instant.now();
        Instant effectiveAt = request.effectiveAt();

        Money oldPrice = fullPeriodPrice(request.oldPlanCode(), request.itemCode(),
            tenantId, customerId, currency, effectiveAt);
        Money newPrice = fullPeriodPrice(request.newPlanCode(), request.itemCode(),
            tenantId, customerId, currency, effectiveAt);

        IdempotencyDecision decision = idempotencyStore.decide(
            tenantId, idempotencyKey, fingerprintOf(request), IDEMPOTENCY_TTL, Instant.now());
        if (decision.action() == IdempotencyDecision.Action.REPLAY) {
            Invoice replayed = invoiceRepository.findInvoice(tenantId, decision.stored().responseBody())
                .orElseThrow(() -> new IllegalStateException(
                    "Idempotency-Key " + idempotencyKey + " records invoice '"
                        + decision.stored().responseBody() + "', which is no longer retrievable"));
            return ResponseEntity.status(decision.httpStatus()).body(toDto(replayed));
        }
        if (decision.action() == IdempotencyDecision.Action.IN_FLIGHT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "An identical request is still in progress for this Idempotency-Key");
        }
        if (decision.action() == IdempotencyDecision.Action.CONFLICT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "This Idempotency-Key was already used for a different request body");
        }

        IdempotencyRecord claim = decision.claim();
        Invoice draft;
        try {
            draft = lifecycle.draftForPlanChange(
                request.invoiceId(), tenantId, customerId,
                PlanCode.of(request.planCode()), currency,
                request.itemCode(), request.oldPlanCode(), oldPrice,
                request.newPlanCode(), newPrice,
                request.periodStart(), request.periodEnd(),
                effectiveAt, recordedAt);
        } catch (RuntimeException e) {
            idempotencyStore.release(tenantId, claim);
            throw e;
        }

        idempotencyStore.complete(tenantId, claim, HttpStatus.OK.value(), draft.invoiceId());
        return ResponseEntity.ok(toDto(draft));
    }

    /** Rates one full period of a plan's item at quantity one, in the request currency. */
    private Money fullPeriodPrice(String planCode, String itemCode, TenantId tenantId,
                                  CustomerId customerId, CurrencyUnit currency, Instant at) {
        return pricingService.evaluate(PricingRequest.builder()
            .tenantId(tenantId.value())
            .customerId(customerId.value())
            .planCode(planCode)
            .targetCurrency(currency)
            .evaluationTime(at)
            .item(itemCode, 1)
            .build()).finalTotal();
    }

    /** Finalizes a draft, assigning its document number and freezing its terms. */
    @PostMapping("/invoices/{invoiceId}/finalize")
    public ResponseEntity<InvoiceDtos.InvoiceDto> finalizeInvoice(
        @PathVariable String invoiceId,
        @RequestParam String tenantId,
        @RequestParam String invoiceNumber
    ) {
        TenantId tid = TenantId.of(tenantGuard.verify(tenantId));
        return ResponseEntity.ok(toDto(lifecycle.finalizeInvoice(tid, invoiceId, invoiceNumber)));
    }

    /**
     * Records a payment.
     *
     * <p>Partial payments are allowed; overpayment is refused by the aggregate, because an invoice
     * may not be settled beyond its own total.
     */
    @PostMapping("/invoices/{invoiceId}/payments")
    public ResponseEntity<InvoiceDtos.InvoiceDto> recordPayment(
        @PathVariable String invoiceId,
        @RequestParam String tenantId,
        @Valid @RequestBody InvoiceDtos.RecordPaymentRequest request
    ) {
        TenantId tid = TenantId.of(tenantGuard.verify(tenantId));
        return ResponseEntity.ok(toDto(lifecycle.recordPayment(tid, invoiceId, request.amount())));
    }

    /** Voids an invoice. It is retained for audit and is never collected. */
    @PostMapping("/invoices/{invoiceId}/void")
    public ResponseEntity<InvoiceDtos.InvoiceDto> voidInvoice(
        @PathVariable String invoiceId,
        @RequestParam String tenantId
    ) {
        TenantId tid = TenantId.of(tenantGuard.verify(tenantId));
        return ResponseEntity.ok(toDto(lifecycle.voidInvoice(tid, invoiceId)));
    }

    /**
     * Issues a credit note against an invoice.
     *
     * <p>A separate document referencing the original, never a negative invoice: the original number
     * must survive on statements and in closed-period tax filings.
     */
    @PostMapping("/invoices/{invoiceId}/credit-notes")
    public ResponseEntity<InvoiceDtos.CreditNoteDto> creditInvoice(
        @PathVariable String invoiceId,
        @Valid @RequestBody InvoiceDtos.CreateCreditNoteRequest request
    ) {
        TenantId tid = TenantId.of(tenantGuard.verify(request.tenantId()));
        CreditNote credit = lifecycle.issueCreditNote(tid, invoiceId, request.creditNoteId(),
            request.reason(), request.disposition());
        return ResponseEntity.ok(toDto(credit));
    }

    @GetMapping("/invoices/{invoiceId}")
    public ResponseEntity<InvoiceDtos.InvoiceDto> get(
        @PathVariable String invoiceId,
        @RequestParam String tenantId
    ) {
        return ResponseEntity.ok(toDto(require(TenantId.of(tenantGuard.verify(tenantId)), invoiceId)));
    }

    /** Cursor-paginated listing; never offset, which would skip invoices inserted mid-scroll. */
    @GetMapping("/invoices")
    public ResponseEntity<InvoiceDtos.InvoicePageDto> list(
        @RequestParam String tenantId,
        @RequestParam(required = false) String customerId,
        @RequestParam(required = false) String pageToken,
        @RequestParam(defaultValue = "50") int pageSize
    ) {
        TenantId tid = TenantId.of(tenantGuard.verify(tenantId));
        Optional<CustomerId> customer = customerId == null || customerId.isBlank()
            ? Optional.empty() : Optional.of(CustomerId.of(customerId));

        // null, not Optional.empty(): the SPI takes a nullable lower bound, not an Optional.
        var page = invoiceRepository.listInvoices(tid, customer, null, pageSize, pageToken);
        return ResponseEntity.ok(new InvoiceDtos.InvoicePageDto(
            page.invoices().stream().map(InvoiceController::toDto).toList(),
            page.nextPageToken().orElse(null)));
    }

    /**
     * Reconciles derived entitlement state against the stored rows.
     *
     * <p>Operator-facing on purpose: "is this customer's access in sync" is a support question, and
     * answering it should not require a deploy.
     */
    @GetMapping("/entitlements/reconciliation")
    public ResponseEntity<InvoiceDtos.EntitlementReconciliationDto> reconcile(
        @RequestParam String tenantId,
        @RequestParam String customerId
    ) {
        TenantId tid = TenantId.of(tenantGuard.verify(tenantId));
        var report = pricingService.reconcileEntitlements(tid, CustomerId.of(customerId));

        List<InvoiceDtos.FeatureComparisonDto> features = report.comparisons().stream()
            .map(c -> new InvoiceDtos.FeatureComparisonDto(
                c.featureKey(), c.drift().name(), c.remediation().name(),
                c.derivedQuota().map(Object::toString).orElse(null),
                c.storedQuota().map(Object::toString).orElse(null),
                c.derivedUsage().map(Object::toString).orElse(null),
                c.storedUsage().map(Object::toString).orElse(null)))
            .toList();

        return ResponseEntity.ok(new InvoiceDtos.EntitlementReconciliationDto(
            report.isInSync(), tid.value(), customerId, report.evaluatedAt(),
            report.wronglyDenied(), report.wronglyGranted(), features));
    }

    private Invoice require(TenantId tenantId, String invoiceId) {
        return invoiceRepository.findInvoice(tenantId, invoiceId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown invoice " + invoiceId));
    }

    private static InvoiceDtos.InvoiceDto toDto(Invoice invoice) {
        List<InvoiceDtos.InvoiceLineDto> lines = invoice.lineItems().stream()
            .map(InvoiceController::toDto).toList();
        return new InvoiceDtos.InvoiceDto(
            invoice.invoiceId(), invoice.tenantId().value(), invoice.customerId().value(),
            invoice.planCode().value(), invoice.currency().code(), invoice.status().name(),
            invoice.invoiceNumber().orElse(null), invoice.periodStart(), invoice.periodEnd(),
            invoice.issuedAt(), invoice.subtotal().amount().toPlainString(),
            invoice.taxTotal().amount().toPlainString(), invoice.total().amount().toPlainString(),
            invoice.amountPaid().amount().toPlainString(),
            invoice.balanceDue().amount().toPlainString(),
            lines, invoice.metadata());
    }

    private static InvoiceDtos.InvoiceLineDto toDto(InvoiceLineItem line) {
        return new InvoiceDtos.InvoiceLineDto(
            line.itemCode(), line.description(), line.quantity(),
            line.unitPrice().amount().toPlainString(), line.amount().amount().toPlainString(),
            line.taxCode());
    }

    private static InvoiceDtos.CreditNoteDto toDto(CreditNote credit) {
        return new InvoiceDtos.CreditNoteDto(
            credit.creditNoteId(), credit.invoiceId(), credit.invoiceNumber(),
            credit.currency().code(), credit.status().name(), credit.disposition().name(),
            credit.total().amount().toPlainString(), credit.reason(), credit.issuedAt());
    }
}