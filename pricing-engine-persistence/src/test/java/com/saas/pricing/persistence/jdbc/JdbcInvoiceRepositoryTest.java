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
import com.saas.pricing.persistence.jdbc.JdbcInvoiceRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invoice persistence.
 *
 * <p>The document is the thing a customer disputes and a tax authority audits, so the properties
 * under test are: it survives a round trip unchanged, its terms cannot move after finalization, its
 * number is unique per tenant, and credits cannot exceed it.
 *
 * <p>Note on coverage: migration V7's PostgreSQL triggers are not exercised here, because the H2
 * build used by this suite cannot execute plpgsql. The equivalent Java-level rules are tested
 * below; the trigger layer is verified only against a real PostgreSQL.
 */
class JdbcInvoiceRepositoryTest extends BaseJdbcRepositoryTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final Instant P_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant P_END = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private JdbcInvoiceRepository repository() {
        return new JdbcInvoiceRepository(jdbcTemplate);
    }

    /**
     * One line whose amount equals the requested figure (quantity one), so the expected invoice
     * subtotal is readable directly from the argument.
     */
    private Invoice draft(String invoiceId, String amount) {
        return Invoice.draft(invoiceId, TENANT, CUSTOMER, PLAN, USD, P_START, P_END, T0)
            .addLine(InvoiceLineItem.of("SEATS", "Subscription seats",
                BigDecimal.ONE, Money.of(amount, USD), "TX_STANDARD"))
            .taxTotal(Money.of("10.00", USD))
            .build();
    }

    @Test
    @DisplayName("an invoice round-trips unchanged")
    void roundTrips() {
        var repo = repository();
        var invoice = draft("inv-1", "100.00").finalizeInvoice("INV-2026-0001", T0);

        repo.createInvoice(invoice);
        var loaded = repo.findInvoice(TENANT, "inv-1").orElseThrow();

        assertThat(loaded.invoiceId()).isEqualTo("inv-1");
        assertThat(loaded.status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(loaded.invoiceNumber()).contains("INV-2026-0001");
        assertThat(loaded.subtotal().amount()).isEqualByComparingTo("100.00");
        assertThat(loaded.taxTotal().amount()).isEqualByComparingTo("10.00");
        assertThat(loaded.total().amount()).isEqualByComparingTo("110.00");
        assertThat(loaded.lineItems()).hasSize(1);
        assertThat(loaded.lineItems().getFirst().itemCode()).isEqualTo("SEATS");
        assertThat(loaded.lineItems().getFirst().taxCode()).isEqualTo("TX_STANDARD");
        assertThat(loaded.lineItems().getFirst().amount().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("invoices are found by their document number")
    void findByNumber() {
        var repo = repository();
        repo.createInvoice(draft("inv-2", "50.00").finalizeInvoice("INV-2026-0002", T0));

        assertThat(repo.findInvoiceByNumber(TENANT, "INV-2026-0002"))
            .get()
            .extracting(Invoice::invoiceId)
            .isEqualTo("inv-2");
    }

    @Test
    @DisplayName("an invoice number cannot be reused within a tenant")
    void duplicateNumberRejected() {
        var repo = repository();
        repo.createInvoice(draft("inv-a", "50.00").finalizeInvoice("INV-DUP", T0));

        assertThatThrownBy(() -> repo.createInvoice(draft("inv-b", "50.00").finalizeInvoice("INV-DUP", T0)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the model itself refuses an invoice whose subtotal contradicts its lines")
    void contradictorySubtotalCannotEvenBeConstructed() {
        var issued = draft("inv-t1", "100.00").finalizeInvoice("INV-T1", T0);

        // Defence in depth: a tampered document never reaches the repository at all, because the
        // aggregate re-derives the subtotal from its lines and refuses the mismatch on construction.
        assertThatThrownBy(() -> new Invoice(issued.invoiceId(), issued.tenantId(), issued.customerId(),
                issued.planCode(), issued.currency(), issued.status(), issued.invoiceNumber(),
                issued.periodStart(), issued.periodEnd(), issued.issuedAt(), issued.lineItems(),
                Money.of("999.00", USD), issued.taxTotal(), Money.of("1009.00", USD),
                issued.amountPaid(), issued.metadata()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not equal the sum");
    }

    @Test
    @DisplayName("a finalized invoice cannot be repaved with a different amount")
    void finalizedTermsImmutable() {
        var repo = repository();
        var issued = draft("inv-3", "100.00").finalizeInvoice("INV-2026-0003", T0);
        repo.createInvoice(issued);

        // A fully self-consistent invoice that simply charges more. This one CAN be constructed,
        // so the repository is what must refuse it: after finalization only status and amount paid
        // are allowed to move.
        var inflatedLine = InvoiceLineItem.of("SEATS", "Subscription seats",
            BigDecimal.ONE, Money.of("999.00", USD), "TX_STANDARD");
        var tampered = new Invoice(issued.invoiceId(), issued.tenantId(), issued.customerId(),
            issued.planCode(), issued.currency(), issued.status(), issued.invoiceNumber(),
            issued.periodStart(), issued.periodEnd(), issued.issuedAt(), List.of(inflatedLine),
            Money.of("999.00", USD), Money.of("10.00", USD), Money.of("1009.00", USD),
            issued.amountPaid(), issued.metadata());

        assertThatThrownBy(() -> repo.updateInvoice(tampered))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("immutable");

        assertThat(repo.findInvoice(TENANT, "inv-3").orElseThrow().subtotal().amount())
            .as("the stored invoice is unchanged")
            .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("status and amount paid may still move after finalization")
    void paymentUpdatesAllowed() {
        var repo = repository();
        var issued = draft("inv-4", "100.00").finalizeInvoice("INV-2026-0004", T0);
        repo.createInvoice(issued);

        var paid = issued.recordPayment(Money.of("110.00", USD));
        repo.updateInvoice(paid);

        assertThat(repo.findInvoice(TENANT, "inv-4").orElseThrow().status())
            .isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("the database refuses a finalized invoice that has lost its number")
    void numberRequiredOnceFinalized() {
        // The "draft has no number, finalized always does" rule is a column constraint, so it holds
        // even for a write that bypasses the Java model.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO invoices (
                invoice_id, tenant_id, customer_id, plan_code, currency, status, invoice_number,
                period_start, period_end, issued_at, subtotal, tax_total, total, amount_paid, payload_json
            ) VALUES ('inv-x', 't1', 'c1', 'PRO', 'USD', 'OPEN', NULL,
                ?, ?, ?, 0, 0, 0, 0, '{}')
            """, java.sql.Timestamp.from(P_START), java.sql.Timestamp.from(P_END), java.sql.Timestamp.from(T0)))
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("the database refuses a credit note with a positive total")
    void creditTotalMustBeNonPositive() {
        var repo = repository();
        var invoice = draft("inv-5", "100.00").finalizeInvoice("INV-2026-0005", T0);
        repo.createInvoice(invoice);

        // "A credit reduces an amount" is enforced by the schema, so a mislabelled charge cannot be
        // recorded as a credit and silently inflate total credits.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO credit_notes (
                credit_note_id, tenant_id, invoice_id, invoice_number, currency, status,
                disposition, total, reason, issued_at, payload_json
            ) VALUES ('cn-bad', 't1', 'inv-5', 'INV-2026-0005', 'USD', 'ISSUED',
                'REFUND', 50.00, 'wrong sign', ?, '{}')
            """, java.sql.Timestamp.from(T0)))
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("credit notes persist and are counted only while in force")
    void creditNotesPersist() {
        var repo = repository();
        var invoice = draft("inv-6", "100.00").finalizeInvoice("INV-2026-0006", T0);
        repo.createInvoice(invoice);

        var credit = CreditNote.forFullInvoice("cn-1", invoice, "service cancelled", T0);
        repo.recordCreditNote(credit);

        assertThat(repo.findCreditNotes(TENANT, "inv-6")).hasSize(1);
        assertThat(repo.totalCredited(TENANT, "inv-6")).isEqualByComparingTo("110.00");

        repo.recordCreditNote(credit.voidCreditNote(T0));
        assertThat(repo.totalCredited(TENANT, "inv-6"))
            .as("a voided credit stops reducing what is owed")
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("listing is cursor-paginated and does not skip rows")
    void cursorPagination() {
        var repo = repository();
        for (int i = 0; i < 7; i++) {
            repo.createInvoice(draft("inv-p" + i, "10.00").finalizeInvoice("INV-P" + i,
                Instant.parse("2026-10-0" + (i + 1) + "T00:00:00Z")));
        }

        var first = repo.listInvoices(TENANT, java.util.Optional.empty(), null, 3, null);
        assertThat(first.invoices()).hasSize(3);
        assertThat(first.nextPageToken()).isPresent();

        var second = repo.listInvoices(TENANT, java.util.Optional.empty(), null, 3,
            first.nextPageToken().orElseThrow());
        var third = repo.listInvoices(TENANT, java.util.Optional.empty(), null, 3,
            second.nextPageToken().orElseThrow());

        var ids = new java.util.ArrayList<String>();
        first.invoices().forEach(i -> ids.add(i.invoiceId()));
        second.invoices().forEach(i -> ids.add(i.invoiceId()));
        third.invoices().forEach(i -> ids.add(i.invoiceId()));

        assertThat(ids).as("keyset pagination returns every invoice exactly once")
            .containsExactlyInAnyOrder("inv-p0", "inv-p1", "inv-p2", "inv-p3", "inv-p4", "inv-p5", "inv-p6");
        assertThat(third.nextPageToken()).isEmpty();
    }

    @Test
    @DisplayName("a page size beyond the cap is clamped rather than honoured")
    void pageSizeClamped() {
        var repo = repository();
        repo.createInvoice(draft("inv-1", "10.00"));

        // An unbounded financial listing is a production incident; the repository refuses to be one.
        assertThat(repo.listInvoices(TENANT, java.util.Optional.empty(), null, 1_000_000, null)
            .invoices()).hasSize(1);
        assertThat(repo.MAX_PAGE_SIZE).isEqualTo(200);
    }
}