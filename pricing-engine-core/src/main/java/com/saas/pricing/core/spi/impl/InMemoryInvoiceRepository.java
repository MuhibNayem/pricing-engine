package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.CreditNote;
import com.saas.pricing.core.model.invoice.CreditNoteLedger;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceLineItem;
import com.saas.pricing.core.model.invoice.InvoiceStatus;
import com.saas.pricing.core.spi.InvoiceRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory invoice repository, suitable for tests and for single-process deployments.
 *
 * <p>Enforces the same rules as the JDBC adapter: finalized invoices are immutable, invoice numbers
 * are unique per tenant, and credits cannot exceed the invoice. The point of enforcing them here as
 * well is that a deployment which forgets to configure JDBC must not silently lose the guarantees
 * that make invoices defensible.
 */
public class InMemoryInvoiceRepository implements InvoiceRepository {

    private final Map<String, Invoice> invoices = new ConcurrentHashMap<>();
    private final Map<String, List<CreditNote>> creditNotes = new ConcurrentHashMap<>();

    private static String key(TenantId tenantId, String invoiceId) {
        return tenantId.value() + "::" + invoiceId;
    }

    @Override
    public void createInvoice(Invoice invoice) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        String k = key(invoice.tenantId(), invoice.invoiceId());
        if (invoices.putIfAbsent(k, invoice) != null) {
            throw new IllegalStateException("Invoice " + invoice.invoiceId() + " already exists");
        }
        assertNumberUnique(invoice);
    }

    @Override
    public void updateInvoice(Invoice invoice) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        String k = key(invoice.tenantId(), invoice.invoiceId());
        Invoice existing = invoices.get(k);
        if (existing == null) {
            throw new IllegalStateException("Unknown invoice " + invoice.invoiceId());
        }
        if (existing.status().isImmutable() && !sameTerms(existing, invoice)) {
            throw new IllegalStateException(
                    "Invoice " + invoice.invoiceId() + " is finalized; its terms are immutable. "
                        + "Issue a credit note instead.");
        }
        invoices.put(k, invoice);
    }

    /**
     * Compares only the commercial terms. Status and amount paid are expected to move after
     * finalization; the rest are not.
     *
     * <p>Line items are compared by VALUE, not by record equality. A money value round-tripped
     * through a {@code NUMERIC(24,8)} column comes back as 100.00000000 rather than 100.00, and
     * {@link java.math.BigDecimal#equals} is scale-sensitive - so a naive equals would report a
     * finalized invoice's terms as changed after a round trip.
     */
    private static boolean sameTerms(Invoice a, Invoice b) {
        return a.tenantId().equals(b.tenantId())
            && a.customerId().equals(b.customerId())
            && a.planCode().equals(b.planCode())
            && a.currency().equals(b.currency())
            && a.periodStart().equals(b.periodStart())
            && a.periodEnd().equals(b.periodEnd())
            && a.subtotal().compareTo(b.subtotal()) == 0
            && a.taxTotal().compareTo(b.taxTotal()) == 0
            && a.total().compareTo(b.total()) == 0
            && a.invoiceNumber().equals(b.invoiceNumber())
            && sameLineItems(a.lineItems(), b.lineItems());
    }

    private static boolean sameLineItems(List<InvoiceLineItem> a, List<InvoiceLineItem> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            InvoiceLineItem left = a.get(i);
            InvoiceLineItem right = b.get(i);
            if (!left.itemCode().equals(right.itemCode())
                    || !left.description().equals(right.description())
                    || left.quantity().compareTo(right.quantity()) != 0
                    || !left.taxCode().equals(right.taxCode())
                    || left.unitPrice().amount().compareTo(right.unitPrice().amount()) != 0
                    || left.amount().amount().compareTo(right.amount().amount()) != 0) {
                return false;
            }
        }
        return true;
    }

    private void assertNumberUnique(Invoice invoice) {
        if (invoice.invoiceNumber().isEmpty()) {
            return;
        }
        boolean clash = invoices.values().stream()
                .anyMatch(other -> other.tenantId().equals(invoice.tenantId())
                    && other.invoiceNumber().equals(invoice.invoiceNumber())
                    && !other.invoiceId().equals(invoice.invoiceId()));
        if (clash) {
            throw new IllegalStateException(
                    "Invoice number " + invoice.invoiceNumber().get() + " is already used by this tenant");
        }
    }

    @Override
    public Optional<Invoice> findInvoice(TenantId tenantId, String invoiceId) {
        return Optional.ofNullable(invoices.get(key(tenantId, invoiceId)));
    }

    @Override
    public Optional<Invoice> findInvoiceByNumber(TenantId tenantId, String invoiceNumber) {
        return invoices.values().stream()
                .filter(i -> i.tenantId().equals(tenantId) && i.invoiceNumber().equals(invoiceNumber))
                .findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The cap is recomputed from the notes already in force rather than trusted from a stored
     * total, so a lost update cannot let a pair of concurrent credits both slip past the limit.
     */
    @Override
    public void recordCreditNote(CreditNote creditNote) {
        Objects.requireNonNull(creditNote, "creditNote cannot be null");
        Invoice invoice = findById(creditNote.invoiceId());
        if (invoice == null) {
            throw new IllegalStateException("Cannot credit unknown invoice " + creditNote.invoiceId());
        }
        List<CreditNote> notes = creditNotes.computeIfAbsent(
                key(invoice.tenantId(), invoice.invoiceId()), k -> new ArrayList<>());

        synchronized (notes) {
            if (notes.stream().anyMatch(n -> n.creditNoteId().equals(creditNote.creditNoteId()))) {
                throw new IllegalStateException(
                        "Credit note " + creditNote.creditNoteId() + " already exists");
            }
            BigDecimal already = notes.stream()
                    .filter(n -> n.status() == CreditNote.CreditNoteStatus.ISSUED)
                    .map(n -> n.absoluteTotal().amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal projected = already.add(creditNote.absoluteTotal().amount());

            if (projected.compareTo(invoice.total().amount()) > 0) {
                throw new IllegalStateException(
                        "Total credits for invoice " + invoice.invoiceId() + " would reach " + projected
                            + ", above the invoice total of " + invoice.total()
                            + "; the sum of credit notes against an invoice cannot exceed the invoice");
            }
            notes.add(creditNote);
        }
    }

    private Invoice findById(String invoiceId) {
        return invoices.values().stream()
                .filter(i -> i.invoiceId().equals(invoiceId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<CreditNote> findCreditNotes(TenantId tenantId, String invoiceId) {
        List<CreditNote> notes = creditNotes.get(key(tenantId, invoiceId));
        return notes == null ? List.of() : List.copyOf(notes);
    }

    @Override
    public BigDecimal totalCredited(TenantId tenantId, String invoiceId) {
        Invoice invoice = findInvoice(tenantId, invoiceId).orElse(null);
        if (invoice == null) {
            return BigDecimal.ZERO;
        }
        CreditNoteLedger ledger = new CreditNoteLedger(invoice);
        for (CreditNote note : findCreditNotes(tenantId, invoiceId)) {
            if (note.status() == CreditNote.CreditNoteStatus.ISSUED) {
                ledger.issue(note);
            }
        }
        return ledger.totalCredited().amount();
    }

    /**
     * Cursor pagination on (issuedAt, invoiceId), newest first.
     *
     * <p>The cursor is the last row's issuedAt plus its id, so a row inserted between pages shifts
     * nothing: offset pagination would silently skip or repeat invoices, which on a financial
     * listing means a customer is never shown some of their documents.
     */
    @Override
    public InvoicePage listInvoices(TenantId tenantId, Optional<CustomerId> customerId,
                                    Instant afterIssuedAt, int limit, String pageToken) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        List<Invoice> candidates = new ArrayList<>(invoices.values().stream()
            .filter(i -> i.tenantId().equals(tenantId))
            .filter(i -> customerId.isEmpty() || i.customerId().equals(customerId.get()))
            .toList());
        candidates.sort(Comparator.comparing(Invoice::issuedAt).reversed()
            .thenComparing(Invoice::invoiceId, Comparator.reverseOrder()));

        Instant cursorTime = afterIssuedAt;
        String cursorId = null;
        if (pageToken != null && !pageToken.isBlank()) {
            String[] parts = pageToken.split("\\|", 2);
            cursorTime = Instant.parse(parts[0]);
            cursorId = parts.length > 1 ? parts[1] : null;
        }

        List<Invoice> page = new ArrayList<>();
        for (Invoice candidate : candidates) {
            if (cursorTime != null) {
                int cmp = candidate.issuedAt().compareTo(cursorTime);
                if (cmp > 0) {
                    continue;
                }
                if (cmp == 0 && cursorId != null && candidate.invoiceId().compareTo(cursorId) >= 0) {
                    continue;
                }
            }
            page.add(candidate);
            if (page.size() == effectiveLimit) {
                break;
            }
        }

        Optional<String> next = page.size() < effectiveLimit || page.isEmpty()
            ? Optional.empty()
            : Optional.of(page.getLast().issuedAt() + "|" + page.getLast().invoiceId());
        return new InvoicePage(page, next);
    }

    public void clear() {
        invoices.clear();
        creditNotes.clear();
    }
}