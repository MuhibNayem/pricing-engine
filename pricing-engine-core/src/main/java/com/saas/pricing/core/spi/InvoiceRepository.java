package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.CreditNote;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for invoices and the credit notes issued against them.
 *
 * <p>An invoice is a financial document, so the repository has obligations a generic record store
 * does not:
 *
 * <ul>
 *   <li><strong>Finalized invoices are immutable.</strong> Saving must refuse to change the terms of
 *       an invoice that is no longer {@link InvoiceStatus#DRAFT}; corrections go through a
 *       {@link CreditNote}. Silently updating a finalized document is the defect class that makes
 *       a billing system indefensible in a dispute.</li>
 *   <li><strong>Invoice numbers are unique per tenant</strong> once assigned, so a customer cannot
 *       be shown two documents with the same number.</li>
 *   <li><strong>Listing is cursor-based, never offset.</strong> Offset pagination skips or repeats
 *       rows when a new invoice is inserted mid-scroll, which on a financial listing means a
 *       customer is silently never shown some of their invoices.</li>
 * </ul>
 */
public interface InvoiceRepository {

    /**
     * Saves a new invoice.
     *
     * @throws IllegalStateException if the invoice id already exists
     */
    void createInvoice(Invoice invoice);

    /**
     * Updates an invoice's mutable state (status and amount paid only).
     *
     * @throws IllegalStateException if the invoice is finalized and the terms would change
     */
    void updateInvoice(Invoice invoice);

    Optional<Invoice> findInvoice(TenantId tenantId, String invoiceId);

    Optional<Invoice> findInvoiceByNumber(TenantId tenantId, String invoiceNumber);

    /**
     * Records a credit note against an invoice.
     *
     * @throws IllegalStateException if the credit would take total credits above the invoice total
     */
    void recordCreditNote(CreditNote creditNote);

    List<CreditNote> findCreditNotes(TenantId tenantId, String invoiceId);

    /** Total of all credit notes still in force against an invoice. */
    java.math.BigDecimal totalCredited(TenantId tenantId, String invoiceId);

    /**
     * Lists invoices for a customer, newest period first, using a cursor.
     *
     * @param afterIssuedAt return invoices issued strictly after this instant; {@code null} to start
     * @param limit         maximum rows to return; callers pass an explicit bound because an
     *                      unbounded financial listing is a production incident waiting to happen
     * @param pageToken     opaque cursor returned by a previous call, or {@code null} to start
     */
    InvoicePage listInvoices(TenantId tenantId, Optional<CustomerId> customerId,
                             Instant afterIssuedAt, int limit, String pageToken);

    /** A page of invoices plus the cursor to resume from. */
    record InvoicePage(List<Invoice> invoices, Optional<String> nextPageToken) {
        public InvoicePage {
            invoices = invoices == null ? List.of() : List.copyOf(invoices);
            nextPageToken = nextPageToken == null ? Optional.empty() : nextPageToken;
        }
    }

    /** Hard upper bound on page size, so a caller cannot ask the database for the whole table. */
    int MAX_PAGE_SIZE = 200;

    /** Default page size when a caller does not choose one. */
    int DEFAULT_PAGE_SIZE = 50;
}