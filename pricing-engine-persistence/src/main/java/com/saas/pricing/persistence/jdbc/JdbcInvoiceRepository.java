package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.CreditNote;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceLineItem;
import com.saas.pricing.core.model.invoice.InvoiceStatus;
import com.saas.pricing.core.spi.InvoiceRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC / PostgreSQL persistence for invoices and credit notes.
 *
 * <p>Line items are written to {@code invoice_line_items} as rows, not only inside the JSON payload,
 * because e-invoicing regimes (DE B2B, FR Factur-X, IT SdI, IN GST, SA ZATCA) require a structured
 * rate/base/amount breakdown per line that a tax authority can read without parsing JSON.
 */
public class JdbcInvoiceRepository implements InvoiceRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcInvoiceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    private static final String SELECT_INVOICE = """
        SELECT invoice_id, tenant_id, customer_id, plan_code, currency, status, invoice_number,
               period_start, period_end, issued_at, subtotal, tax_total, total, amount_paid, payload_json
        FROM invoices
        """;

    @Override
    @Transactional
    public void createInvoice(Invoice invoice) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        String sql = """
            INSERT INTO invoices (
                invoice_id, tenant_id, customer_id, plan_code, currency, status, invoice_number,
                period_start, period_end, issued_at, subtotal, tax_total, total, amount_paid,
                payload_json, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try {
            jdbcTemplate.update(sql,
                    invoice.invoiceId(), invoice.tenantId().value(), invoice.customerId().value(),
                    invoice.planCode().value(), invoice.currency().code(), invoice.status().name(),
                    invoice.invoiceNumber().orElse(null),
                    Timestamp.from(invoice.periodStart()), Timestamp.from(invoice.periodEnd()),
                    Timestamp.from(invoice.issuedAt()),
                    invoice.subtotal().amount(), invoice.taxTotal().amount(), invoice.total().amount(),
                    invoice.amountPaid().amount(), PricingJsonMapper.toJson(invoice),
                    Timestamp.from(invoice.issuedAt()));
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Invoice " + invoice.invoiceId() + " already exists", e);
        }
        replaceLines(invoice);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only status and amount paid are written. The terms are not re-persisted at all, so a bug
     * that mutated a finalized invoice cannot reach the row even before the database trigger in V7
     * would reject it.
     */
    @Override
    @Transactional
    public void updateInvoice(Invoice invoice) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        Invoice existing = findInvoice(invoice.tenantId(), invoice.invoiceId())
            .orElseThrow(() -> new IllegalStateException("Unknown invoice " + invoice.invoiceId()));

        if (existing.status().isImmutable() && !sameTerms(existing, invoice)) {
            throw new IllegalStateException(
                    "Invoice " + invoice.invoiceId() + " is finalized; its terms are immutable. "
                        + "Issue a credit note instead.");
        }

        jdbcTemplate.update(
                "UPDATE invoices SET status = ?, amount_paid = ?, updated_at = ? WHERE invoice_id = ?",
                invoice.status().name(), invoice.amountPaid().amount(), Timestamp.from(invoice.issuedAt()),
                invoice.invoiceId());
    }

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

    /**
     * Compares lines by VALUE rather than by record equality.
     *
     * <p>A money value round-tripped through a {@code NUMERIC(24,8)} column comes back as
     * 100.00000000 rather than 100.00, and {@link java.math.BigDecimal#equals} is scale-sensitive.
     * A naive record comparison would therefore report a finalized invoice's terms as changed after
     * a round trip, and refuse a legitimate payment.
     */
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

    private void replaceLines(Invoice invoice) {
        jdbcTemplate.update("DELETE FROM invoice_line_items WHERE invoice_id = ?", invoice.invoiceId());
        int index = 0;
        for (InvoiceLineItem line : invoice.lineItems()) {
            jdbcTemplate.update("""
                    INSERT INTO invoice_line_items (
                        invoice_id, line_index, item_code, description, quantity,
                        unit_price, amount, tax_code, currency, metadata_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                invoice.invoiceId(), index++, line.itemCode(), line.description(), line.quantity(),
                line.unitPrice().amount(), line.amount().amount(), line.taxCode(),
                line.amount().currency().code(), PricingJsonMapper.toJson(line.metadata()));
        }
    }

    @Override
    public Optional<Invoice> findInvoice(TenantId tenantId, String invoiceId) {
        return jdbcTemplate.query(SELECT_INVOICE + " WHERE tenant_id = ? AND invoice_id = ?",
                (rs, rowNum) -> readInvoice(rs), tenantId.value(), invoiceId)
            .stream().findFirst();
    }

    @Override
    public Optional<Invoice> findInvoiceByNumber(TenantId tenantId, String invoiceNumber) {
        return jdbcTemplate.query(SELECT_INVOICE + " WHERE tenant_id = ? AND invoice_number = ?",
                (rs, rowNum) -> readInvoice(rs), tenantId.value(), invoiceNumber)
            .stream().findFirst();
    }

    @Override
    @Transactional
    public void recordCreditNote(CreditNote creditNote) {
        Objects.requireNonNull(creditNote, "creditNote cannot be null");

        // Voiding is a status change on the existing note, not a new document: the id, number and
        // lines are unchanged, so inserting again would violate the primary key AND lose the record
        // that a credit was raised and then cancelled.
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_notes WHERE credit_note_id = ?", Integer.class,
                creditNote.creditNoteId());
        if (existing != null && existing > 0) {
            jdbcTemplate.update(
                    "UPDATE credit_notes SET status = ?, voided_at = ?, payload_json = ? WHERE credit_note_id = ?",
                    creditNote.status().name(),
                    creditNote.voidedAt().map(Timestamp::from).orElse(null),
                    PricingJsonMapper.toJson(creditNote), creditNote.creditNoteId());
            return;
        }

        jdbcTemplate.update("""
            INSERT INTO credit_notes (
                credit_note_id, tenant_id, invoice_id, invoice_number, currency, status,
                disposition, total, reason, issued_at, voided_at, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            creditNote.creditNoteId(), tenantOf(creditNote.invoiceId()), creditNote.invoiceId(),
            creditNote.invoiceNumber(), creditNote.currency().code(), creditNote.status().name(),
            creditNote.disposition().name(), creditNote.total().amount(), creditNote.reason(),
            Timestamp.from(creditNote.issuedAt()),
            creditNote.voidedAt().map(Timestamp::from).orElse(null),
            PricingJsonMapper.toJson(creditNote));

        int index = 0;
        for (InvoiceLineItem line : creditNote.lineItems()) {
            jdbcTemplate.update("""
                INSERT INTO credit_note_lines (
                    credit_note_id, line_index, item_code, description, quantity,
                    unit_price, amount, tax_code, currency, metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                creditNote.creditNoteId(), index++, line.itemCode(), line.description(), line.quantity(),
                line.unitPrice().amount(), line.amount().amount(), line.taxCode(),
                line.amount().currency().code(), PricingJsonMapper.toJson(line.metadata()));
        }
    }

    /** Tenant is denormalised onto the credit note row; resolve it from the referenced invoice. */
    private String tenantOf(String invoiceId) {
        return jdbcTemplate.queryForObject("SELECT tenant_id FROM invoices WHERE invoice_id = ?",
                String.class, invoiceId);
    }

    @Override
    public List<CreditNote> findCreditNotes(TenantId tenantId, String invoiceId) {
        // The document itself is stored as JSON; the normalised line rows exist for tax reporting.
        return jdbcTemplate.query("SELECT payload_json FROM credit_notes WHERE invoice_id = ? ORDER BY issued_at",
                (rs, rowNum) -> PricingJsonMapper.fromJson(rs.getString(1), CreditNote.class), invoiceId);
    }

    @Override
    public BigDecimal totalCredited(TenantId tenantId, String invoiceId) {
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(-total), 0) FROM credit_notes WHERE invoice_id = ? AND status = 'ISSUED'",
                BigDecimal.class, invoiceId);
        return total == null ? BigDecimal.ZERO : total;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Keyset pagination on {@code (issued_at, invoice_id)} rather than OFFSET. Offset pages skip
     * or repeat rows when an invoice is inserted mid-scroll, which on a financial listing means a
     * customer is silently never shown some of their documents.
     */
    @Override
    public InvoicePage listInvoices(TenantId tenantId, Optional<CustomerId> customerId,
                                    Instant afterIssuedAt, int limit, String pageToken) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        StringBuilder sql = new StringBuilder(SELECT_INVOICE)
                .append(" WHERE tenant_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenantId.value());
        if (customerId.isPresent()) {
            sql.append(" AND customer_id = ?");
            params.add(customerId.get().value());
        }
        if (pageToken != null && !pageToken.isBlank()) {
            String[] parts = pageToken.split("\\|", 2);
            sql.append(" AND (issued_at < ? OR (issued_at = ? AND invoice_id < ?))");
            params.add(Timestamp.from(Instant.parse(parts[0])));
            params.add(Timestamp.from(Instant.parse(parts[0])));
            params.add(parts.length > 1 ? parts[1] : "");
        } else if (afterIssuedAt != null) {
            sql.append(" AND issued_at > ?");
            params.add(Timestamp.from(afterIssuedAt));
        }
        sql.append(" ORDER BY issued_at DESC, invoice_id DESC LIMIT ?");
        params.add(effectiveLimit);

        List<Invoice> page = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> readInvoice(rs), params.toArray());

        Optional<String> next = page.size() < effectiveLimit || page.isEmpty()
            ? Optional.empty()
            : Optional.of(page.getLast().issuedAt() + "|" + page.getLast().invoiceId());
        return new InvoicePage(page, next);
    }

    private Invoice readInvoice(java.sql.ResultSet rs) throws java.sql.SQLException {
        String invoiceId = rs.getString("invoice_id");
        // The normalised columns are authoritative; the payload is a convenience copy.
        return new Invoice(
                rs.getString("invoice_id"),
                TenantId.of(rs.getString("tenant_id")),
                CustomerId.of(rs.getString("customer_id")),
                PlanCode.of(rs.getString("plan_code")),
                CurrencyUnit.of(rs.getString("currency")),
                InvoiceStatus.valueOf(rs.getString("status")),
                Optional.ofNullable(rs.getString("invoice_number")),
                rs.getTimestamp("period_start").toInstant(),
                rs.getTimestamp("period_end").toInstant(),
                rs.getTimestamp("issued_at").toInstant(),
                readLines(invoiceId),
                Money.of(rs.getBigDecimal("subtotal"), CurrencyUnit.of(rs.getString("currency"))),
                Money.of(rs.getBigDecimal("tax_total"), CurrencyUnit.of(rs.getString("currency"))),
                Money.of(rs.getBigDecimal("total"), CurrencyUnit.of(rs.getString("currency"))),
                Money.of(rs.getBigDecimal("amount_paid"), CurrencyUnit.of(rs.getString("currency"))),
                Map.of());
    }

    /**
     * Reads the normalised line rows, falling back to the JSON payload when a legacy invoice was
     * written before the line table existed.
     */
    private List<InvoiceLineItem> readLines(String invoiceId) {
        List<InvoiceLineItem> lines = jdbcTemplate.query("""
            SELECT item_code, description, quantity, unit_price, amount, tax_code, currency, metadata_json
            FROM invoice_line_items WHERE invoice_id = ? ORDER BY line_index
            """, (rs, rowNum) -> {
                CurrencyUnit currency = CurrencyUnit.of(rs.getString("currency"));
                return new InvoiceLineItem(
                    rs.getString("item_code"),
                    rs.getString("description"),
                    rs.getBigDecimal("quantity"),
                    Money.of(rs.getBigDecimal("unit_price"), currency),
                    Money.of(rs.getBigDecimal("amount"), currency),
                    rs.getString("tax_code"),
                    readMetadata(rs.getString("metadata_json")));
            }, invoiceId);

        if (!lines.isEmpty()) {
            return lines;
        }
        Invoice fromPayload = jdbcTemplate.query(SELECT_INVOICE + " WHERE invoice_id = ?",
            (rs, rowNum) -> PricingJsonMapper.fromJson(rs.getString("payload_json"), Invoice.class), invoiceId)
            .stream().findFirst().orElse(null);
        return fromPayload == null ? List.of() : fromPayload.lineItems();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return Map.of();
        }
        try {
            return PricingJsonMapper.fromJson(json, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}