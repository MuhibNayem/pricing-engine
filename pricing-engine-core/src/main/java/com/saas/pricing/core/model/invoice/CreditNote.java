package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A credit note: a document that reduces what a customer owes, issued <em>against</em> an invoice.
 *
 * <h2>Why this is not a negative invoice</h2>
 * A refund must not erase the original invoice. The invoice number appears on the customer's
 * statement, in tax filings for periods that have closed, and in audits long after the refund. Real
 * systems therefore issue a separate document that references the original, and cap the total of all
 * credit notes against one invoice at the invoice total. {@link CreditNoteLedger} enforces that cap;
 * the individual record cannot, because it cannot see its siblings.
 *
 * <h2>Disposition</h2>
 * The credit has to end up somewhere concrete:
 * <ul>
 *   <li>{@link Disposition#REFUND} — money returned to the customer, e.g. to a card</li>
 *   <li>{@link Disposition#CREDIT_BALANCE} — held on the customer account against future invoices</li>
 *   <li>{@link Disposition#REPLACE} — the invoice was issued in error and is being replaced</li>
 * </ul>
 *
 * @param creditNoteId unique identifier
 * @param invoiceId    the invoice this credit is issued against
 * @param invoiceNumber the number of that invoice, denormalised so a printed credit note is self-contained
 * @param currency     must match the invoice currency
 * @param status       ISSUED (awaiting application) or VOID (cancelled, e.g. issued in error)
 * @param disposition  what happens to the money
 * @param lineItems    the credit lines; amounts are negative
 * @param total        sum of the credit line amounts, always zero or negative
 * @param issuedAt     when the credit note was issued
 * @param voidedAt     set when voided
 * @param reason       why the credit was issued; required, because a credit without a cause is unauditable
 */
public record CreditNote(
    String creditNoteId,
    String invoiceId,
    String invoiceNumber,
    CurrencyUnit currency,
    CreditNoteStatus status,
    Disposition disposition,
    List<InvoiceLineItem> lineItems,
    Money total,
    Instant issuedAt,
    Optional<Instant> voidedAt,
    String reason,
    Map<String, String> metadata
) implements Serializable {

    /** Lifecycle of a credit note. */
    public enum CreditNoteStatus {
        /** Issued and awaiting application to a balance, refund or replacement. */
        ISSUED,
        /** Cancelled - for example the original invoice was corrected, so the credit is redundant. */
        VOID
    }

    /** Where the credited money goes. */
    public enum Disposition {
        /** Returned to the customer's payment method. */
        REFUND,
        /** Retained on the customer account against future invoices. */
        CREDIT_BALANCE,
        /** The invoice was wrong and is being reissued. */
        REPLACE
    }

    public CreditNote {
        Objects.requireNonNull(creditNoteId, "creditNoteId cannot be null");
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        Objects.requireNonNull(invoiceNumber, "invoiceNumber cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(disposition, "disposition cannot be null");
        Objects.requireNonNull(lineItems, "lineItems cannot be null");
        Objects.requireNonNull(total, "total cannot be null");
        Objects.requireNonNull(issuedAt, "issuedAt cannot be null");
        Objects.requireNonNull(voidedAt, "voidedAt cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        lineItems = List.copyOf(lineItems);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);

        if (lineItems.isEmpty()) {
            throw new IllegalArgumentException("A credit note must carry at least one line");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("A credit note must state why the credit was issued");
        }
        if (total.isPositive()) {
            throw new IllegalArgumentException(
                    "A credit note total must be zero or negative, got " + total);
        }
        Money summed = Money.zero(currency);
        for (InvoiceLineItem item : lineItems) {
            if (!item.amount().currency().equals(currency)) {
                throw new IllegalArgumentException("Credit line currency must match the credit note currency");
            }
            summed = summed.plus(item.amount());
        }
        summed = summed.roundToCurrency();
        if (summed.compareTo(total) != 0) {
            throw new IllegalArgumentException(
                    "Credit note total " + total + " does not equal the sum of its lines " + summed);
        }
    }

    /**
     * Issues a credit against the whole of {@code invoice}, tax included.
     *
     * <p>Tax must be reversed with the invoice. Crediting only the line subtotal would leave the
     * customer charged tax on revenue they did not receive - the classic partial-refund tax
     * exposure, and the reason a full credit is represented as the reversed lines <em>plus</em> a
     * reversed tax line rather than as a single number.
     *
     * @throws IllegalArgumentException if the invoice is a draft (it has no number to reference yet)
     */
    public static CreditNote forFullInvoice(String creditNoteId, Invoice invoice, String reason, Instant at) {
        if (invoice.status() == InvoiceStatus.DRAFT) {
            throw new IllegalArgumentException(
                    "Cannot credit a draft invoice; it has no number to reference");
        }
        List<InvoiceLineItem> credits = new java.util.ArrayList<>();
        for (InvoiceLineItem line : invoice.lineItems()) {
            credits.add(line.negated("Credit: " + line.description()));
        }
        if (!invoice.taxTotal().isZero()) {
            credits.add(InvoiceLineItem.of("TAX", "Tax", BigDecimal.ONE, invoice.taxTotal(),
                    taxCodeOf(invoice))
                .negated("Credit: tax on " + invoice.invoiceNumber().orElse(invoice.invoiceId())));
        }
        Money total = Money.zero(invoice.currency());
        for (InvoiceLineItem line : credits) {
            total = total.plus(line.amount());
        }
        return new CreditNote(creditNoteId, invoice.invoiceId(), invoice.invoiceNumber().orElse(""),
            invoice.currency(), CreditNoteStatus.ISSUED, Disposition.REFUND, credits,
            total.roundToCurrency(), at, Optional.empty(), reason, Map.of());
    }

    /** The tax code of the invoice's first line, carried onto the reversed tax line. */
    private static String taxCodeOf(Invoice invoice) {
        return invoice.lineItems().isEmpty() ? "" : invoice.lineItems().getFirst().taxCode();
    }

    /**
     * Issues a credit for specific lines only, which is how a single-line correction is made
     * without disturbing the rest of the invoice.
     */
    public static CreditNote forLines(String creditNoteId, Invoice invoice, List<InvoiceLineItem> linesToCredit,
                                      Disposition disposition, String reason, Instant at) {
        if (invoice.status() == InvoiceStatus.DRAFT) {
            throw new IllegalArgumentException("Cannot credit a draft invoice");
        }
        if (linesToCredit.isEmpty()) {
            throw new IllegalArgumentException("A credit note must credit at least one line");
        }
        List<InvoiceLineItem> credits = linesToCredit.stream()
                .map(line -> line.negated("Credit: " + line.description()))
                .toList();
        Money total = Money.zero(invoice.currency());
        for (InvoiceLineItem line : credits) {
            total = total.plus(line.amount());
        }
        return new CreditNote(creditNoteId, invoice.invoiceId(), invoice.invoiceNumber().orElse(""),
            invoice.currency(), CreditNoteStatus.ISSUED, disposition, credits, total.roundToCurrency(),
            at, Optional.empty(), reason, Map.of());
    }

    /** Absolute value of the credit, for comparisons against the invoice total. */
    public Money absoluteTotal() {
        return total.abs();
    }

    public CreditNote voidCreditNote(Instant at) {
        if (status == CreditNoteStatus.VOID) {
            throw new IllegalStateException("Credit note " + creditNoteId + " is already void");
        }
        return new CreditNote(creditNoteId, invoiceId, invoiceNumber, currency, CreditNoteStatus.VOID,
            disposition, lineItems, total, issuedAt, Optional.of(at), reason, metadata);
    }
}