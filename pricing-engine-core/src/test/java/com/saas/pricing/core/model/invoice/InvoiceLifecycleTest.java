package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invoice lifecycle and credit-note semantics.
 *
 * <p>The two properties being defended here are the ones a real billing system cannot get wrong:
 * a finalized invoice's terms never change, and credits against one invoice can never exceed that
 * invoice.
 */
class InvoiceLifecycleTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");

    private static final Instant PERIOD_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");

    /** Money is a record; assertions compare the decimal amount, not the wrapper. */
    private static BigDecimal amt(Money money) {
        return money.amount();
    }

    private Invoice draftWithOneLine(String amount) {
        return Invoice.draft("inv-1", TENANT, CUSTOMER, PLAN, USD, PERIOD_START, PERIOD_END, CREATED)
            .addLine(InvoiceLineItem.of("SEATS", "Pro seats",
                new BigDecimal("1"), Money.of(amount, USD), "TX_STANDARD"))
            .taxTotal(Money.of("10.00", USD))
            .build();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("subtotal always equals the sum of the lines")
        void subtotalMatchesLines() {
            var invoice = draftWithOneLine("100.00");

            assertThat(amt(invoice.subtotal())).isEqualByComparingTo("100.00");
            assertThat(amt(invoice.total())).isEqualByComparingTo("110.00");
            assertThat(invoice.lineItems()).hasSize(1);
        }

        @Test
        @DisplayName("a subtotal that disagrees with its lines is refused")
        void mismatchedSubtotalRejected() {
            // The constructor re-derives the subtotal, so a document can never claim one total
            // while its lines say another.
            var draft = draftWithOneLine("100.00");
            assertThatThrownBy(() -> new Invoice(draft.invoiceId(), draft.tenantId(), draft.customerId(),
                draft.planCode(), draft.currency(), draft.status(), draft.invoiceNumber(),
                draft.periodStart(), draft.periodEnd(), draft.issuedAt(), draft.lineItems(),
                Money.of("999.00", USD), draft.taxTotal(), draft.total(), draft.amountPaid(), draft.metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not equal the sum");
        }

        @Test
        @DisplayName("a mixed-currency invoice is refused")
        void mixedCurrencyRejected() {
            var usdLine = InvoiceLineItem.of("A", "A", BigDecimal.ONE, Money.of("10.00", USD), "TX");

            assertThatThrownBy(() -> Invoice.draft("inv-x", TENANT, CUSTOMER, PLAN,
                    CurrencyUnit.EUR, PERIOD_START, PERIOD_END, CREATED)
                .addLine(usdLine)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixed-currency");
        }

        @Test
        @DisplayName("line amount is computed, not trusted")
        void lineAmountIsComputed() {
            var line = InvoiceLineItem.of("TOK", "Tokens", new BigDecimal("1000000"),
                Money.of("0.000003", USD), "TX");

            assertThat(amt(line.amount())).isEqualByComparingTo("3.00");
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("finalizing assigns the number and freezes the terms")
        void finalizeAssignsNumber() {
            var draft = draftWithOneLine("100.00");
            assertThat(draft.invoiceNumber()).isEmpty();
            assertThat(draft.status().isImmutable()).isFalse();

            var issued = draft.finalizeInvoice("INV-2026-0001", Instant.parse("2026-10-01T00:05:00Z"));

            assertThat(issued.status()).isEqualTo(InvoiceStatus.OPEN);
            assertThat(issued.invoiceNumber()).contains("INV-2026-0001");
            assertThat(issued.status().isImmutable()).isTrue();
        }

        @Test
        @DisplayName("a finalized invoice cannot be re-finalized")
        void cannotFinalizeTwice() {
            var issued = draftWithOneLine("100.00")
                .finalizeInvoice("INV-2026-0001", Instant.parse("2026-10-01T00:05:00Z"));

            assertThatThrownBy(() -> issued.finalizeInvoice("INV-2026-0002", Instant.parse("2026-10-02T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credit note");
        }

        @Test
        @DisplayName("payment settles the invoice and then refuses more")
        void paymentSettlesAndThenRefusesMore() {
            var issued = draftWithOneLine("100.00")
                .finalizeInvoice("INV-2026-0001", Instant.parse("2026-10-01T00:05:00Z"));

            var partial = issued.recordPayment(Money.of("40.00", USD));
            assertThat(partial.status()).isEqualTo(InvoiceStatus.OPEN);
            assertThat(amt(partial.balanceDue())).isEqualByComparingTo("70.00");

            var paid = partial.recordPayment(Money.of("70.00", USD));
            assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(paid.isSettled()).isTrue();

            assertThatThrownBy(() -> paid.recordPayment(Money.of("0.01", USD)))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a payment larger than the balance is refused")
        void overpaymentRefused() {
            var issued = draftWithOneLine("100.00")
                .finalizeInvoice("INV-2026-0001", Instant.parse("2026-10-01T00:05:00Z"));

            assertThatThrownBy(() -> issued.recordPayment(Money.of("500.00", USD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds");
        }

        @Test
        @DisplayName("a draft cannot take a payment, and a void invoice cannot be paid")
        void paymentRequiresFinalizedInvoice() {
            var draft = draftWithOneLine("100.00");
            assertThatThrownBy(() -> draft.recordPayment(Money.of("10.00", USD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalize");

            var voided = draftWithOneLine("100.00")
                .finalizeInvoice("INV-1", CREATED).voidInvoice(CREATED);
            assertThatThrownBy(() -> voided.recordPayment(Money.of("10.00", USD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voided");
        }

        @Test
        @DisplayName("uncollectible is reachable from OPEN and can still be paid")
        void uncollectiblePath() {
            var issued = draftWithOneLine("100.00")
                .finalizeInvoice("INV-2026-0001", CREATED);

            var writtenOff = issued.markUncollectible();
            assertThat(writtenOff.status()).isEqualTo(InvoiceStatus.UNCOLLECTIBLE);
            assertThat(writtenOff.status().isCollectible()).isTrue();
            assertThat(writtenOff.recordPayment(Money.of("110.00", USD)).status())
                .isEqualTo(InvoiceStatus.PAID);
        }

        @Test
        @DisplayName("voiding twice is refused")
        void cannotVoidTwice() {
            var voided = draftWithOneLine("100.00").finalizeInvoice("INV-1", CREATED).voidInvoice(CREATED);
            assertThatThrownBy(() -> voided.voidInvoice(CREATED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already void");
        }
    }

    @Nested
    @DisplayName("Credit notes")
    class Credits {

        private Invoice issuedInvoice() {
            return draftWithOneLine("100.00").finalizeInvoice("INV-2026-0001", CREATED);
        }

        @Test
        @DisplayName("a full credit is a separate document referencing the original")
        void fullCreditReferencesInvoice() {
            var invoice = issuedInvoice();
            var credit = CreditNote.forFullInvoice("cn-1", invoice, "Service not delivered", CREATED);

            assertThat(credit.invoiceId()).isEqualTo(invoice.invoiceId());
            assertThat(credit.invoiceNumber()).isEqualTo("INV-2026-0001");
            assertThat(amt(credit.total())).isEqualByComparingTo("-110.00");
            assertThat(credit.total().isPositive())
                .as("a credit reduces an amount; it is not a positive charge")
                .isFalse();
        }

        @Test
        @DisplayName("total credits cannot exceed the invoice total")
        void creditCapEnforced() {
            // Without this, a retry or a duplicated credit refunds a customer more than they paid -
            // and unlike most billing defects that one reaches a bank account.
            var invoice = issuedInvoice();
            var ledger = new CreditNoteLedger(invoice);

            ledger.issue(CreditNote.forLines("cn-1", invoice, List.of(invoice.lineItems().getFirst()),
                CreditNote.Disposition.REFUND, "partial refund", CREATED));
            assertThat(amt(ledger.totalCredited())).isEqualByComparingTo("100.00");

            assertThatThrownBy(() -> ledger.issue(CreditNote.forLines("cn-2", invoice,
                    List.of(invoice.lineItems().getFirst()),
                    CreditNote.Disposition.REFUND, "duplicate", CREATED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot exceed the invoice");
        }

        @Test
        @DisplayName("a voided credit stops counting")
        void voidedCreditStopsCounting() {
            var invoice = issuedInvoice();
            var ledger = new CreditNoteLedger(invoice);

            var credit = ledger.issue(CreditNote.forFullInvoice("cn-1", invoice, "service cancelled", CREATED));
            assertThat(amt(ledger.totalCredited())).isEqualByComparingTo("110.00");

            var voided = ledger.voidCreditNote("cn-1", CREATED);
            assertThat(voided.status()).isEqualTo(CreditNote.CreditNoteStatus.VOID);
            assertThat(amt(ledger.totalCredited()))
                .as("a voided credit no longer reduces what is owed")
                .isEqualByComparingTo("0.00");

            // The note itself is retained, so an audit shows a credit was raised then cancelled.
            assertThat(ledger.creditNotes()).hasSize(1);
            assertThat(ledger.creditNotes().getFirst().status())
                .isEqualTo(CreditNote.CreditNoteStatus.VOID);
        }

        @Test
        @DisplayName("a credit against a draft is refused")
        void cannotCreditDraft() {
            assertThatThrownBy(() -> CreditNote.forFullInvoice("cn-1", draftWithOneLine("100.00"), "x", CREATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft");
        }

        @Test
        @DisplayName("a credit must state why it was issued")
        void reasonRequired() {
            var invoice = issuedInvoice();
            assertThatThrownBy(() -> CreditNote.forFullInvoice("cn-1", invoice, "  ", CREATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("why");
        }

        @Test
        @DisplayName("balance due combines payments and credits")
        void balanceCombinesPaymentsAndCredits() {
            var invoice = issuedInvoice();
            var paid = invoice.recordPayment(Money.of("60.00", USD));
            var ledger = new CreditNoteLedger(paid);

            assertThat(amt(ledger.balanceDue())).isEqualByComparingTo("50.00");

            // Crediting the whole 100.00 line against a 50.00 outstanding balance leaves the
            // customer with a credit ON ACCOUNT, not a negative invoice. That is the correct
            // behaviour: credits are capped at the invoice total, not at the balance due, so a
            // later period can draw on the remainder.
            ledger.issue(CreditNote.forLines("cn-1", paid, List.of(paid.lineItems().getFirst()),
                CreditNote.Disposition.CREDIT_BALANCE, "goodwill", CREATED));

            assertThat(amt(ledger.balanceDue()))
                .as("a credit exceeding the balance due becomes a credit balance")
                .isEqualByComparingTo("-50.00");
        }

        @Test
        @DisplayName("a credit for another invoice is refused")
        void crossInvoiceCreditRefused() {
            var invoice = issuedInvoice();
            var other = Invoice.draft("inv-2", TENANT, CUSTOMER, PLAN, USD, PERIOD_START, PERIOD_END, CREATED)
                .addLine(InvoiceLineItem.of("X", "X", BigDecimal.ONE, Money.of("5.00", USD), ""))
                .build().finalizeInvoice("INV-2", CREATED);

            var ledger = new CreditNoteLedger(invoice);
            assertThatThrownBy(() -> ledger.issue(CreditNote.forFullInvoice("cn-x", other, "wrong invoice", CREATED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not inv-1");
        }
    }

    @Nested
    @DisplayName("Invoice from a rating result")
    class FromPricingResult {

        @Test
        @DisplayName("a rated request becomes a finalizable invoice")
        void producesDraftInvoice() {
            var now = Instant.parse("2026-10-08T12:00:00Z");
            var item = com.saas.pricing.core.model.RatePlanItem.of("SEATS", "seats",
                com.saas.pricing.core.model.PricingModel.PerUnitModel.of(new BigDecimal("25.00")), USD);
            var repo = new com.saas.pricing.core.spi.impl.InMemoryRateCardRepository();
            repo.save(com.saas.pricing.core.model.RateCard.of("rc", TENANT, PLAN, 1, now, List.of(item)));

            var engine = new com.saas.pricing.core.engine.DefaultPricingEngine(repo,
                (f, t, ts) -> BigDecimal.ONE, (t, c, at) -> List.of(), r -> { }, null);

            var result = engine.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId(TENANT.value()).planCode(PLAN.value())
                .evaluationTime(now).targetCurrency(USD)
                .customerId(CUSTOMER.value())
                .item("SEATS", 4)
                .build());

            var invoice = InvoiceFactory.draftFrom(result, "inv-100", CUSTOMER,
                PERIOD_START, PERIOD_END, now, "TX_STANDARD");

            assertThat(invoice.status()).isEqualTo(InvoiceStatus.DRAFT);
            assertThat(amt(invoice.subtotal()))
                .as("the invoice subtotal equals what the engine rated")
                .isEqualByComparingTo(amt(result.totalGross()));
            assertThat(amt(invoice.total()))
                .isEqualByComparingTo(amt(result.finalTotal()));

            var issued = invoice.finalizeInvoice("INV-2026-0100", now);
            assertThat(issued.invoiceNumber()).contains("INV-2026-0100");
            assertThat(issued.status().isImmutable()).isTrue();
        }
    }
}
