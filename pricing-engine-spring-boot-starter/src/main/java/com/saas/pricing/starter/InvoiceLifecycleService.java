package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.fx.FxBooking;
import com.saas.pricing.core.model.fx.FxRate;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.DomainEventFactory;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.model.invoice.CreditNote;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceNumberService;
import com.saas.pricing.core.spi.InvoiceRepository;

import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Invoice lifecycle: persists the state change <em>and</em> announces it.
 *
 * <p>These operations live here rather than in the controller for one reason: a state change that is
 * persisted without being announced cannot be recovered. Splitting them lets a caller mutate an
 * invoice and forget the event, and the result is a committed change that no subscriber ever heard
 * about - which is the hardest class of billing bug to detect, because the invoice looks fine in
 * the database.
 *
 * <p>The outbox write joins the caller's transaction on a JDBC backend, so the invoice row and its
 * event commit or roll back together.
 */
public class InvoiceLifecycleService {

    private final InvoiceRepository invoiceRepository;
    private final OutboxRepository outboxRepository;
    private final InvoiceNumberService invoiceNumbers;
    private final Clock clock;

    public InvoiceLifecycleService(InvoiceRepository invoiceRepository,
                                   OutboxRepository outboxRepository,
                                   InvoiceNumberService invoiceNumbers,
                                   Clock clock) {
        this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository cannot be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository cannot be null");
        this.invoiceNumbers = Objects.requireNonNull(invoiceNumbers, "invoiceNumbers cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Finalizes an invoice, numbering it if no number was supplied, and announces it.
     *
     * <p>A supplied number is honoured verbatim: that is the path for a migrated or imported invoice
     * whose number already exists in the tenant's history. Otherwise the number is allocated here,
     * at issuance, rather than at draft time — a draft is editable and often abandoned, and a number
     * consumed by a document that never existed is exactly the gap sequential numbering exists to
     * rule out.
     *
     * <p>If the write fails after allocation the number is returned to its series, so a transient
     * error costs the tenant neither a duplicate nor a hole.
     */
    @Transactional
    public Invoice finalizeInvoice(TenantId tenantId, String invoiceId, String invoiceNumber) {
        Invoice invoice = require(tenantId, invoiceId);

        boolean allocated = invoiceNumber == null || invoiceNumber.isBlank();
        String number = allocated
            ? invoiceNumbers.allocateNext(tenantId, invoice.customerId())
            : invoiceNumber;

        Invoice finalized;
        try {
            finalized = invoice.finalizeInvoice(number, clock.instant());
            invoiceRepository.updateInvoice(finalized);
        } catch (RuntimeException e) {
            if (allocated) {
                invoiceNumbers.release(tenantId, invoice.customerId(), number);
            }
            throw e;
        }

        outboxRepository.enqueue(DomainEventFactory.invoiceFinalized(
            tenantId.value(), finalized.invoiceId(), finalized.invoiceNumber().orElse(number),
            finalized.status().name(), finalized.total().amount().toPlainString(),
            finalized.currency().code(), clock.instant()));
        return finalized;
    }

    /** Records a payment and announces it. */
    @Transactional
    public Invoice recordPayment(TenantId tenantId, String invoiceId, String amount) {
        Invoice invoice = require(tenantId, invoiceId);
        Invoice paid = invoice.recordPayment(
            new com.saas.pricing.core.model.Money(new java.math.BigDecimal(amount), invoice.currency()));
        invoiceRepository.updateInvoice(paid);
        outboxRepository.enqueue(DomainEventFactory.invoicePaid(
            tenantId.value(), paid.invoiceId(), paid.status().name(),
            paid.amountPaid().amount().toPlainString(),
            paid.balanceDue().amount().toPlainString(),
            paid.currency().code(), clock.instant()));
        return paid;
    }

    /** Voids an invoice and announces it. */
    @Transactional
    public Invoice voidInvoice(TenantId tenantId, String invoiceId) {
        Invoice invoice = require(tenantId, invoiceId);
        Invoice voided = invoice.voidInvoice(clock.instant());
        invoiceRepository.updateInvoice(voided);
        outboxRepository.enqueue(DomainEventFactory.invoiceVoided(
            tenantId.value(), voided.invoiceId(), voided.total().amount().toPlainString(),
            voided.currency().code(), clock.instant()));
        return voided;
    }

    /** Issues a credit note and announces it. */
    @Transactional
    public CreditNote issueCreditNote(TenantId tenantId, String invoiceId, String creditNoteId,
                                      String reason, String disposition) {
        Invoice invoice = require(tenantId, invoiceId);
        CreditNote credit = CreditNote.forFullInvoice(creditNoteId, invoice, reason, clock.instant());
        if (disposition != null && !disposition.isBlank()) {
            credit = new CreditNote(credit.creditNoteId(), credit.invoiceId(), credit.invoiceNumber(),
                credit.currency(), credit.status(),
                CreditNote.Disposition.valueOf(disposition.toUpperCase(java.util.Locale.ROOT)),
                credit.lineItems(), credit.total(), credit.issuedAt(), credit.voidedAt(),
                credit.reason(), credit.metadata());
        }
        invoiceRepository.recordCreditNote(credit);
        outboxRepository.enqueue(DomainEventFactory.creditNoteIssued(
            tenantId.value(), credit.creditNoteId(), credit.invoiceId(), credit.invoiceNumber(),
            credit.total().amount().toPlainString(), credit.currency().code(),
            credit.disposition().name(), credit.reason(), clock.instant()));
        return credit;
    }

    /** Creates a draft, announced under its own topic because it is still editable. */
    @Transactional
    public Invoice createDraft(Invoice draft) {
        invoiceRepository.createInvoice(draft);
        outboxRepository.enqueue(DomainEventFactory.invoiceDrafted(
            draft.tenantId().value(), draft.invoiceId(),
            draft.total().amount().toPlainString(), draft.currency().code(), clock.instant()));
        return draft;
    }

    /**
     * Books an invoice's foreign-currency amount at an estimated rate.
     *
     * <p>An invoice denominated in EUR but settled in USD must be recognised before the money
     * arrives, and only an <em>estimate</em> exists at finalization. Booking it without recording the
     * rate and its source means a later true-up has nothing to compare against, and a closed period
     * silently changes value.
     *
     * <p>Deliberately separate from {@link #finalizeInvoice}: a booking is only required when the
     * invoice currency differs from the functional currency, and forcing one on every invoice would
     * put a meaningless FX record on domestic ones.
     *
     * @throws IllegalArgumentException if the invoice is already in the functional currency,
     *                                  because no FX booking is then meaningful
     */
    @Transactional
    public FxBooking bookForeignCurrency(Invoice invoice, CurrencyUnit functionalCurrency,
                                         FxRate estimatedRate) {
        if (invoice.currency().equals(functionalCurrency)) {
            throw new IllegalArgumentException(
                "Invoice " + invoice.invoiceId() + " is already in " + functionalCurrency.code()
                    + "; an FX booking needs two different currencies");
        }
        return FxBooking.estimate("fx-" + invoice.invoiceId(), invoice.invoiceId(),
            invoice.total(), functionalCurrency, estimatedRate, clock.instant());
    }

    /** Trues a booking up to the realised rate and posts the delta as an FX gain or loss. */
    @Transactional
    public FxBooking settleForeignCurrency(FxBooking booking, FxRate realizedRate) {
        return booking.settle(realizedRate, clock.instant());
    }

    /**
     * Drafts an invoice carrying the credit/debit pair for a mid-period plan change.
     *
     * <p>Both sides land on the document. Collapsing them to a net figure would hide which plan the
     * customer was charged for, and on a downgrade the credit is precisely the line they need to
     * see: money they are owed.
     *
     * @param recorded effective time of the change; may be earlier than now for a backdated change
     */
    @Transactional
    public Invoice draftForPlanChange(String invoiceId, TenantId tenantId, CustomerId customerId,
                                      com.saas.pricing.core.model.PlanCode planCode,
                                      com.saas.pricing.core.model.CurrencyUnit currency,
                                      String itemCode, String oldPlanCode, com.saas.pricing.core.model.Money oldPrice,
                                      String newPlanCode, com.saas.pricing.core.model.Money newPrice,
                                      Instant periodStart, Instant periodEnd,
                                      Instant effectiveAt, Instant recordedAt) {

        // Only route through the backdating path when the change really was recorded after it took
        // effect. Using it unconditionally would also reject the ordinary case where a change is
        // recorded at the instant it happens.
        boolean backdated = recordedAt != null && effectiveAt.isBefore(recordedAt);
        var adjustments = backdated
            ? com.saas.pricing.core.model.invoice.ProrationCalculator.forBackdatedChange(
                itemCode, oldPlanCode, oldPrice, newPlanCode, newPrice,
                periodStart, periodEnd, effectiveAt, recordedAt, currency)
            : com.saas.pricing.core.model.invoice.ProrationCalculator.forPlanChange(
                itemCode, oldPlanCode, oldPrice, newPlanCode, newPrice,
                periodStart, periodEnd, effectiveAt, currency);

        Instant issued = clock.instant();
        var draft = Invoice.draft(invoiceId, tenantId, customerId, planCode, currency,
                periodStart, periodEnd, issued)
            .addProrations(adjustments)
            .taxTotal(Money.zero(currency))
            .build();
        return createDraft(draft);
    }

    private Invoice require(TenantId tenantId, String invoiceId) {
        return invoiceRepository.findInvoice(tenantId, invoiceId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown invoice " + invoiceId));
    }
}