package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An invoice: a numbered financial document, and the first thing this project actually modelled
 * rather than merely computed.
 *
 * <p>Until this existed, the engine produced a {@code PricingResult} — an amount plus a trace — and
 * stopped. There was no document, no numbering, no lifecycle, and therefore no way to represent a
 * refund correctly. Real systems do not express a refund as a negative invoice: they issue a
 * <em>separate</em> {@link CreditNote} against the original, because the original invoice number
 * must remain on file and auditable. See {@link CreditNote}.
 *
 * <h2>The finalization boundary</h2>
 * {@link InvoiceStatus#DRAFT} is the only editable state. {@link #finalize(String, Instant)} assigns
 * the document number and moves to {@code OPEN}, after which the commercial terms are immutable.
 * Corrections go through a credit note, never an edit. This is the property that makes an invoice
 * defensible in a dispute.
 *
 * <p>Every transition returns a <em>new</em> invoice; an invoice is a value, not a mutable object,
 * so a finalized document cannot be silently mutated by a caller holding a reference.
 */
public record Invoice(
    String invoiceId,
    TenantId tenantId,
    CustomerId customerId,
    PlanCode planCode,
    CurrencyUnit currency,
    InvoiceStatus status,
    Optional<String> invoiceNumber,
    Instant periodStart,
    Instant periodEnd,
    Instant issuedAt,
    List<InvoiceLineItem> lineItems,
    Money subtotal,
    Money taxTotal,
    Money total,
    Money amountPaid,
    Map<String, String> metadata
) implements Serializable {

    public Invoice {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(invoiceNumber, "invoiceNumber cannot be null");
        Objects.requireNonNull(periodStart, "periodStart cannot be null");
        Objects.requireNonNull(periodEnd, "periodEnd cannot be null");
        Objects.requireNonNull(issuedAt, "issuedAt cannot be null");
        Objects.requireNonNull(lineItems, "lineItems cannot be null");
        Objects.requireNonNull(subtotal, "subtotal cannot be null");
        Objects.requireNonNull(taxTotal, "taxTotal cannot be null");
        Objects.requireNonNull(total, "total cannot be null");
        Objects.requireNonNull(amountPaid, "amountPaid cannot be null");
        lineItems = List.copyOf(lineItems);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);

        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("Invoice periodEnd must be after periodStart");
        }
        for (InvoiceLineItem item : lineItems) {
            if (!item.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "Line item " + item.itemCode() + " is in " + item.amount().currency().code()
                            + " but the invoice is in " + currency.code()
                            + "; a mixed-currency invoice cannot be totalled without a stored FX rate");
            }
        }
        // Draft and finalized invoices must agree; otherwise the document says one thing and its
        // totals say another, which is the defect class this whole design exists to remove.
        Money recomputedSubtotal = sumLineAmounts(lineItems, currency);
        if (recomputedSubtotal.compareTo(subtotal) != 0) {
            throw new IllegalArgumentException(
                    "Invoice subtotal " + subtotal + " does not equal the sum of its line items "
                        + recomputedSubtotal);
        }
    }

    /** Starts a draft. Only drafts can have lines added. */
    public static Builder draft(String invoiceId, TenantId tenantId, CustomerId customerId,
                                 PlanCode planCode, CurrencyUnit currency,
                                 Instant periodStart, Instant periodEnd, Instant createdAt) {
        return new Builder(invoiceId, tenantId, customerId, planCode, currency, periodStart, periodEnd, createdAt);
    }

    private static Money sumLineAmounts(List<InvoiceLineItem> items, CurrencyUnit currency) {
        Money sum = Money.zero(currency);
        for (InvoiceLineItem item : items) {
            sum = sum.plus(item.amount());
        }
        return sum.roundToCurrency();
    }

    /** Money still outstanding after payments and applied credit notes. */
    public Money balanceDue() {
        return total.minus(amountPaid).roundToCurrency();
    }

    /** True when this invoice has no outstanding balance. */
    public boolean isSettled() {
        return balanceDue().isZero();
    }

    // ------------------------------------------------------------------
    // Lifecycle transitions. Each returns a NEW invoice; nothing mutates.
    // ------------------------------------------------------------------

    /**
     * Finalizes the invoice: assigns its document number and freezes the terms.
     *
     * @param invoiceNumber the number shown on the document and quoted in disputes
     * @throws IllegalStateException if the invoice is not a draft, or the number is blank
     */
    public Invoice finalizeInvoice(String invoiceNumber, Instant at) {
        requireDraft("finalize");
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new IllegalArgumentException("An invoice number is required to finalize an invoice");
        }
        return new Invoice(invoiceId, tenantId, customerId, planCode, currency, InvoiceStatus.OPEN,
            Optional.of(invoiceNumber), periodStart, periodEnd, at, lineItems, subtotal, taxTotal, total,
            amountPaid, metadata);
    }

    /**
     * Records a payment.
     *
     * @throws IllegalStateException if the invoice is void or draft, or the payment exceeds the
     *                               outstanding balance
     */
    public Invoice recordPayment(Money payment) {
        requireCollectibleState("record a payment against");
        if (payment.isNegative()) {
            throw new IllegalArgumentException("A payment cannot be negative");
        }
        Money newPaid = amountPaid.plus(payment).roundToCurrency();
        if (newPaid.compareTo(total) > 0) {
            throw new IllegalStateException(
                    "Payment of " + payment + " exceeds the outstanding balance of " + balanceDue());
        }
        InvoiceStatus newStatus = newPaid.compareTo(total) == 0 ? InvoiceStatus.PAID : status;
        return new Invoice(invoiceId, tenantId, customerId, planCode, currency, newStatus, invoiceNumber,
            periodStart, periodEnd, issuedAt, lineItems, subtotal, taxTotal, total, newPaid, metadata);
    }

    /** Cancels the invoice. A voided invoice is retained for audit and is never collected. */
    public Invoice voidInvoice(Instant at) {
        if (status == InvoiceStatus.VOID) {
            throw new IllegalStateException("Invoice " + invoiceId + " is already void");
        }
        return new Invoice(invoiceId, tenantId, customerId, planCode, currency, InvoiceStatus.VOID,
            invoiceNumber, periodStart, periodEnd, at, lineItems, subtotal, taxTotal, total,
            amountPaid, metadata);
    }

    /** Writes the invoice off. Still reported, but no longer expected to be collected. */
    public Invoice markUncollectible() {
        if (status != InvoiceStatus.OPEN) {
            throw new IllegalStateException(
                    "Only an OPEN invoice can be marked uncollectible, this one is " + status);
        }
        return new Invoice(invoiceId, tenantId, customerId, planCode, currency,
            InvoiceStatus.UNCOLLECTIBLE, invoiceNumber, periodStart, periodEnd, issuedAt, lineItems,
            subtotal, taxTotal, total, amountPaid, metadata);
    }

    private void requireDraft(String action) {
        if (status != InvoiceStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot " + action + " an invoice in state " + status
                        + "; its terms are immutable and a correction must be issued as a credit note");
        }
    }

    private void requireCollectibleState(String action) {
        if (status == InvoiceStatus.VOID) {
            throw new IllegalStateException("Cannot " + action + " a voided invoice");
        }
        if (status == InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Cannot " + action + " a draft; finalize it first");
        }
    }

    /** Mutable builder for drafts only. */
    public static final class Builder {
        private final String invoiceId;
        private final TenantId tenantId;
        private final CustomerId customerId;
        private final PlanCode planCode;
        private final CurrencyUnit currency;
        private final Instant periodStart;
        private final Instant periodEnd;
        private final Instant createdAt;
        private final List<InvoiceLineItem> lineItems = new ArrayList<>();
        private Money taxTotal;
        private Map<String, String> metadata = Map.of();

        private Builder(String invoiceId, TenantId tenantId, CustomerId customerId, PlanCode planCode,
                        CurrencyUnit currency, Instant periodStart, Instant periodEnd, Instant createdAt) {
            this.invoiceId = invoiceId;
            this.tenantId = tenantId;
            this.customerId = customerId;
            this.planCode = planCode;
            this.currency = currency;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.createdAt = createdAt;
            this.taxTotal = Money.zero(currency);
        }

        /**
         * Adds the credit/debit pair from a mid-period plan change.
         *
         * <p>Both sides go on the document, not just the net. Collapsing them into a single line
         * would hide which plan the customer was charged for, and on a downgrade the credit is the
         * line the customer needs to see - it is money they are owed.
         *
         * @return this builder
         */
        public Builder addProrations(List<ProrationAdjustment> adjustments) {
            Objects.requireNonNull(adjustments, "adjustments cannot be null");
            for (ProrationAdjustment adjustment : adjustments) {
                lineItems.add(adjustment.toLineItem());
            }
            return this;
        }

        public Builder addLine(InvoiceLineItem line) {
            Objects.requireNonNull(line, "line cannot be null");
            // Checked here rather than at build() so the failure points at the line that caused it,
            // instead of surfacing as a currency mismatch inside the subtotal sum.
            if (!line.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "Line item " + line.itemCode() + " is in " + line.amount().currency().code()
                            + " but the invoice is in " + currency.code()
                            + "; a mixed-currency invoice cannot be totalled without a stored FX rate");
            }
            lineItems.add(line);
            return this;
        }

        public Builder taxTotal(Money tax) {
            this.taxTotal = Objects.requireNonNull(tax, "tax cannot be null");
            return this;
        }

        public Builder metadata(Map<String, String> meta) {
            this.metadata = meta == null ? Map.of() : Map.copyOf(meta);
            return this;
        }

        public Invoice build() {
            Money subtotal = sumLineAmounts(lineItems, currency);
            Money total = subtotal.plus(taxTotal).roundToCurrency();
            return new Invoice(invoiceId, tenantId, customerId, planCode, currency, InvoiceStatus.DRAFT,
                Optional.empty(), periodStart, periodEnd, createdAt, List.copyOf(lineItems),
                subtotal, taxTotal.roundToCurrency(), total, Money.zero(currency), metadata);
        }
    }
}