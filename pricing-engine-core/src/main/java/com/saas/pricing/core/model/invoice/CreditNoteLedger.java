package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tracks the credit notes issued against a single invoice and enforces the total credit cap.
 *
 * <p><strong>Why the cap exists.</strong> Credits against one invoice can never exceed that
 * invoice's total. Without this rule a retry or a duplicated credit issuance refunds a customer more
 * than they paid - and unlike most billing defects, that one reaches a bank account.
 *
 * <p>The cap is stateful, which is why it cannot live on the {@link CreditNote} record: an
 * individual credit note cannot see its siblings. A voided note stops counting, because a void
 * credit is no longer a credit.
 *
 * <p>Not thread-safe: intended to be held per-invoice behind the invoice's own lock.
 */
public final class CreditNoteLedger {

    private final Invoice invoice;
    private final List<CreditNote> creditNotes = new ArrayList<>();

    public CreditNoteLedger(Invoice invoice) {
        this.invoice = Objects.requireNonNull(invoice, "invoice cannot be null");
    }

    /**
     * Issues a credit and records it.
     *
     * @throws IllegalStateException    if this credit would take total credits above the invoice total
     * @throws IllegalArgumentException if the credit does not reference this invoice
     */
    public CreditNote issue(CreditNote creditNote) {
        Objects.requireNonNull(creditNote, "creditNote cannot be null");
        if (!creditNote.invoiceId().equals(invoice.invoiceId())) {
            throw new IllegalArgumentException(
                    "Credit note " + creditNote.creditNoteId() + " references invoice "
                        + creditNote.invoiceId() + ", not " + invoice.invoiceId());
        }
        if (!creditNote.currency().code().equals(invoice.currency().code())) {
            throw new IllegalArgumentException("Credit note currency must match the invoice currency");
        }
        if (creditNote.status() == CreditNote.CreditNoteStatus.VOID) {
            throw new IllegalArgumentException("A void credit note cannot be issued");
        }

        Money newTotal = totalCredited().plus(creditNote.absoluteTotal());
        if (newTotal.compareTo(invoice.total()) > 0) {
            throw new IllegalStateException(
                    "Crediting " + creditNote.absoluteTotal() + " would bring total credits to "
                        + newTotal + ", above the invoice total of " + invoice.total()
                        + "; the sum of credit notes against an invoice cannot exceed the invoice");
        }
        creditNotes.add(creditNote);
        return creditNote;
    }

    /**
 * Voids a credit note that was issued in error.
 *
     * <p>The original credit note is retained as VOID rather than deleted: an audit trail shows
     * that a credit was raised and then cancelled, which a deletion would erase. A voided note
     * stops counting towards {@link #totalCredited()} and frees the cap for a corrected credit.
     *
     * @throws IllegalArgumentException if no such credit note exists against this invoice
     * @throws IllegalStateException    if it is already void
     */
    public CreditNote voidCreditNote(String creditNoteId, java.time.Instant at) {
        Objects.requireNonNull(creditNoteId, "creditNoteId cannot be null");
        for (int i = 0; i < creditNotes.size(); i++) {
            CreditNote note = creditNotes.get(i);
            if (note.creditNoteId().equals(creditNoteId)) {
                CreditNote voided = note.voidCreditNote(at);
                creditNotes.set(i, voided);
                return voided;
            }
        }
        throw new IllegalArgumentException(
                "No credit note " + creditNoteId + " against invoice " + invoice.invoiceId());
    }

    /** Sum of every credit note still in force; voided notes do not count. */
    public Money totalCredited() {
        Money total = Money.zero(invoice.currency());
        for (CreditNote note : creditNotes) {
            if (note.status() == CreditNote.CreditNoteStatus.ISSUED) {
                total = total.plus(note.absoluteTotal());
            }
        }
        return total.roundToCurrency();
    }

    /** What the customer still owes after payments and credits in force. */
    public Money balanceDue() {
        return invoice.balanceDue().minus(totalCredited()).roundToCurrency();
    }

    /** True when the invoice is fully settled by payments and credits combined. */
    public boolean isFullyCredited() {
        return totalCredited().compareTo(invoice.total()) >= 0;
    }

    public List<CreditNote> creditNotes() {
        return List.copyOf(creditNotes);
    }

    public Invoice invoice() {
        return invoice;
    }
}