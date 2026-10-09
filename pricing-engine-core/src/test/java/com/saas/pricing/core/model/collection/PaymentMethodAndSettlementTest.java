package com.saas.pricing.core.model.collection;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceLineItem;
import com.saas.pricing.core.model.invoice.InvoiceStatus;
import com.saas.pricing.core.spi.PaymentProcessor;
import com.saas.pricing.core.spi.impl.InMemoryCollectionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment methods and the settlement lifecycle.
 *
 * <p>The property under test is the one most payment integrations get wrong: <strong>accepted is not
 * paid</strong>. An ACH debit or SEPA transfer is accepted immediately and settles days later, and can
 * be returned afterwards. A model with only success and failure forces a choice between settling the
 * invoice on acceptance — shipping goods against money that may never arrive, with nothing watching
 * for the reversal — and treating a healthy in-flight debit as a decline and retrying it.
 */
class PaymentMethodAndSettlementTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    private static Invoice openInvoice(String amount, CurrencyUnit currency) {
        Invoice draft = Invoice.draft("inv-1", TENANT, CUSTOMER, PlanCode.of("PRO"), currency,
                T0, T0.plus(Duration.ofDays(30)), T0)
            .addLine(InvoiceLineItem.of("SEATS", "Seats", BigDecimal.ONE, Money.of(amount, currency), "TX"))
            .build();
        return draft.finalizeInvoice("INV-2026-0001", T0);
    }

    private static PaymentMethod method(PaymentMethodType type) {
        return PaymentMethod.of("pm-1", TENANT, CUSTOMER, type, "proc_123", T0);
    }

    /** A processor that reports whatever it is told, so the engine's handling is what is tested. */
    private record ScriptedProcessor(PaymentProcessor.Outcome outcome) implements PaymentProcessor {
        @Override
        public Outcome charge(ChargeRequest request) {
            return outcome;
        }
    }

    private InvoiceCollectionService service(PaymentProcessor.Outcome outcome,
                                             InMemoryCollectionRepository ledger) {
        return new InvoiceCollectionService(new ScriptedProcessor(outcome), ledger);
    }

    @Nested
    @DisplayName("Instrument classification")
    class Classification {

        /**
         * Card settles inside the call; bank debits do not.
         *
         * <p>Getting this backwards is the whole defect, so it is stated as data rather than left
         * implicit in prose.
         */
        @Test
        @DisplayName("only card settles synchronously")
        void onlyCardSettlesImmediately() {
            assertThat(PaymentMethodType.CARD.isDelayedNotification()).isFalse();
            assertThat(PaymentMethodType.CARD.notification())
                .isEqualTo(PaymentMethodType.Notification.IMMEDIATE);

            for (var delayed : new PaymentMethodType[] {
                PaymentMethodType.SEPA_DEBIT, PaymentMethodType.ACH_DEBIT,
                PaymentMethodType.BANK_TRANSFER, PaymentMethodType.BOLETO,
                PaymentMethodType.OFFLINE }) {
                assertThat(delayed.isDelayedNotification())
                    .as(delayed + " settles days later and can be returned")
                    .isTrue();
            }
        }

        @Test
        @DisplayName("currencies are a constraint, not a preference")
        void currencyConstraints() {
            assertThat(PaymentMethodType.ACH_DEBIT.supportsCurrency("USD")).isTrue();
            assertThat(PaymentMethodType.ACH_DEBIT.supportsCurrency("EUR"))
                .as("an ACH debit cannot collect a EUR invoice")
                .isFalse();
            assertThat(PaymentMethodType.SEPA_DEBIT.supportsCurrency("eur"))
                .as("comparison is case-insensitive so a lowercase config does not silently fail")
                .isTrue();
            assertThat(PaymentMethodType.CARD.supportsCurrency("JPY")).isTrue();
        }

        @Test
        @DisplayName("only instruments that can be pulled are eligible for automatic charging")
        void automaticChargingEligibility() {
            assertThat(PaymentMethodType.CARD.supportsAutomaticCharging()).isTrue();
            assertThat(PaymentMethodType.ACH_DEBIT.supportsAutomaticCharging()).isTrue();

            assertThat(PaymentMethodType.BANK_TRANSFER.supportsAutomaticCharging())
                .as("each transfer needs fresh details, so a retry would resend the same reference")
                .isFalse();
            assertThat(PaymentMethodType.BOLETO.supportsAutomaticCharging()).isFalse();
        }
    }

    @Nested
    @DisplayName("Refusals before charging")
    class Refusals {

        @Test
        @DisplayName("a method that cannot collect the invoice currency is refused")
        void refusesCurrencyMismatch() {
            var ledger = new InMemoryCollectionRepository();
            var ach = method(PaymentMethodType.ACH_DEBIT);

            assertThatThrownBy(() -> service(PaymentProcessor.Outcome.settled("ch_1", T0), ledger)
                .collect(openInvoice("100.00", CurrencyUnit.EUR), ach, T0,
                    DunningSchedule.standard()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot collect EUR");
        }

        @Test
        @DisplayName("a method that cannot be charged automatically is refused")
        void refusesNonAutomaticMethod() {
            var ledger = new InMemoryCollectionRepository();

            assertThatThrownBy(() -> service(PaymentProcessor.Outcome.settled("ch_1", T0), ledger)
                .collect(openInvoice("100.00", CurrencyUnit.of("BRL")), method(PaymentMethodType.BOLETO), T0,
                    DunningSchedule.standard()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be charged automatically");
        }

        @Test
        @DisplayName("an expired method is refused")
        void refusesExpiredMethod() {
            var ledger = new InMemoryCollectionRepository();
            var expired = new PaymentMethod("pm-1", TENANT, CUSTOMER, PaymentMethodType.CARD,
                "proc_123", Optional.empty(), false,
                Optional.of(T0.plus(Duration.ofDays(1))), T0);

            assertThatThrownBy(() -> service(PaymentProcessor.Outcome.settled("ch_1", T0), ledger)
                .collect(openInvoice("100.00", CurrencyUnit.USD), expired,
                    T0.plus(Duration.ofDays(2)), DunningSchedule.standard()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("an already-settled invoice is not collected")
        void refusesSettledInvoice() {
            var ledger = new InMemoryCollectionRepository();
            var invoice = openInvoice("100.00", CurrencyUnit.USD).recordPayment(Money.of("100.00", CurrencyUnit.USD));

            assertThatThrownBy(() -> service(PaymentProcessor.Outcome.settled("ch_1", T0), ledger)
                .collect(invoice, method(PaymentMethodType.CARD), T0, DunningSchedule.standard()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only an OPEN invoice");
        }

        /**
         * An immediate method that reports pending would strand the invoice.
         *
         * <p>Nothing would be scheduled to resolve it, so the invoice sits unpaid forever while the
         * caller believes the charge is in progress. Refused rather than absorbed.
         */
        @Test
        @DisplayName("an immediate method reporting pending is refused as inconsistent")
        void refusesPendingForImmediateMethod() {
            var ledger = new InMemoryCollectionRepository();

            assertThatThrownBy(() -> service(PaymentProcessor.Outcome.pending("ch_1"), ledger)
                .collect(openInvoice("100.00", CurrencyUnit.USD), method(PaymentMethodType.CARD), T0,
                    DunningSchedule.standard()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immediate method");
        }
    }

    @Nested
    @DisplayName("Delayed notification")
    class DelayedNotification {

        /**
         * The headline property.
         *
         * <p>Acceptance is not payment. The invoice must remain unpaid and the attempt must be
         * recorded pending, because the customer's bank can return the debit days later.
         */
        @Test
        @DisplayName("an accepted ACH debit leaves the invoice unpaid and the charge pending")
        void acceptedDebitIsNotPaid() {
            var ledger = new InMemoryCollectionRepository();

            var result = service(PaymentProcessor.Outcome.pending("ch_1"), ledger)
                .collect(openInvoice("100.00", CurrencyUnit.USD), method(PaymentMethodType.ACH_DEBIT),
                    T0, DunningSchedule.standard());

            assertThat(result.attempt().status()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(result.attempt().isPending()).isTrue();
            assertThat(result.isSettled())
                .as("accepted is not paid")
                .isFalse();
            assertThat(result.invoice().status())
                .as("the invoice must stay unpaid until settlement")
                .isEqualTo(InvoiceStatus.OPEN);
            assertThat(result.invoice().balanceDue())
                .isEqualTo(Money.of("100.00", CurrencyUnit.USD));
        }

        @Test
        @DisplayName("a pending charge is not treated as a failure")
        void pendingIsNotFailure() {
            var ledger = new InMemoryCollectionRepository();
            var invoice = openInvoice("100.00", CurrencyUnit.USD);
            var attempt = PaymentAttempt.pending("inv-1", 1, Money.of("100.00", CurrencyUnit.USD), T0);

            // No prior attempts: the charge is new, so this is not a re-delivery.
            var plan = DunningEngine.plan(invoice, DunningSchedule.standard(), java.util.List.of(),
                attempt, T0);

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.AWAITING_SETTLEMENT);
            assertThat(plan.updatedInvoice())
                .as("a debit already in flight must not be written off")
                .isEmpty();
        }

        /** The same charge completing is not a second charge, so the identity must not change. */
        @Test
        @DisplayName("settlement completes the same charge rather than creating another")
        void settlementKeepsTheSameIdentity() {
            var pending = PaymentAttempt.pending("inv-1", 1, Money.of("100.00", CurrencyUnit.USD), T0);

            var settled = pending.settledAt(T0.plus(Duration.ofDays(2)));

            assertThat(settled.attemptId()).isEqualTo(pending.attemptId());
            assertThat(settled.attemptNumber()).isEqualTo(pending.attemptNumber());
            assertThat(settled.amount()).isEqualTo(pending.amount());
            assertThat(settled.status()).isEqualTo(PaymentAttempt.Status.SUCCEEDED);
        }

        /**
         * A returned debit means the money never arrived.
         *
         * <p>The invoice is unpaid again and collection resumes. This is the event that would
         * otherwise arrive days after the invoice read as paid, with nothing watching.
         */
        @Test
        @DisplayName("a returned debit reopens collection")
        void returnReopensCollection() {
            var pending = PaymentAttempt.pending("inv-1", 1, Money.of("100.00", CurrencyUnit.USD), T0);

            var returned = pending.reversed("R01", "Insufficient funds", T0.plus(Duration.ofDays(3)),
                Optional.of(T0.plus(Duration.ofDays(4))));

            assertThat(returned.status()).isEqualTo(PaymentAttempt.Status.FAILED_RETRYABLE);
            assertThat(returned.failureCode()).contains("R01");
            assertThat(returned.nextAttemptAt()).contains(T0.plus(Duration.ofDays(4)));
            assertThat(returned.isSuccessful()).isFalse();
        }

        @Test
        @DisplayName("a terminal return stops collection")
        void terminalReturnStopsCollection() {
            var pending = PaymentAttempt.pending("inv-1", 1, Money.of("100.00", CurrencyUnit.USD), T0);

            var returned = pending.reversed("R01", "Account closed", T0.plus(Duration.ofDays(3)),
                Optional.empty());

            assertThat(returned.status()).isEqualTo(PaymentAttempt.Status.FAILED_TERMINAL);
        }

        /** Only a charge still in flight has a future; settling a settled charge would be a rewrite. */
        @Test
        @DisplayName("only a pending charge can change state")
        void onlyPendingChargesTransition() {
            var settled = PaymentAttempt.succeeded("inv-1", 1, Money.of("100.00", CurrencyUnit.USD), T0);

            assertThatThrownBy(() -> settled.settledAt(T0.plus(Duration.ofDays(1))))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> settled.reversed("R01", "x", T0, Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Immediate notification")
    class Immediate {

        @Test
        @DisplayName("a card charge settles the invoice")
        void cardChargeSettles() {
            var ledger = new InMemoryCollectionRepository();

            var result = service(PaymentProcessor.Outcome.settled("ch_1", T0), ledger)
                .collect(openInvoice("100.00", CurrencyUnit.USD), method(PaymentMethodType.CARD),
                    T0, DunningSchedule.standard());

            assertThat(result.attempt().status()).isEqualTo(PaymentAttempt.Status.SUCCEEDED);
            assertThat(result.isSettled()).isTrue();
            assertThat(result.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        }

        @Test
        @DisplayName("a decline is recorded with the processor's code and leaves the invoice unpaid")
        void declineLeavesInvoiceUnpaid() {
            var ledger = new InMemoryCollectionRepository();

            var result = service(PaymentProcessor.Outcome.declined("ch_1", "card_declined", "Insufficient funds"),
                    ledger)
                .collect(openInvoice("100.00", CurrencyUnit.USD), method(PaymentMethodType.CARD),
                    T0, DunningSchedule.standard());

            assertThat(result.attempt().status()).isEqualTo(PaymentAttempt.Status.FAILED_RETRYABLE);
            assertThat(result.attempt().failureCode()).contains("card_declined");
            assertThat(result.invoice().status()).isEqualTo(InvoiceStatus.OPEN);
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        /**
         * The charge key is derived, so a timed-out retry is recognisable as the same charge.
         *
         * <p>A random key per request would make the duplicate undetectable, and a timeout is exactly
         * when the caller cannot tell whether the charge landed.
         */
        @Test
        @DisplayName("the charge key is derived from invoice and attempt number")
        void chargeKeyIsDerived() {
            assertThat(PaymentAttempt.attemptIdFor("inv-1", 1)).isEqualTo("inv-1-1");
            assertThat(PaymentAttempt.attemptIdFor("inv-1", 2)).isEqualTo("inv-1-2");
        }

        @Test
        @DisplayName("a repeat attempt number does not charge twice")
        void repeatAttemptIsNotChargedTwice() {
            var ledger = new InMemoryCollectionRepository();
            var amount = Money.of("100.00", CurrencyUnit.USD);
            ledger.record(PaymentAttempt.pending("inv-1", 1, amount, T0));

            var attempt = PaymentAttempt.pending("inv-1", 1, amount, T0);
            var plan = DunningEngine.plan(openInvoice("100.00", CurrencyUnit.USD), DunningSchedule.standard(),
                ledger.findAttempts(TENANT, "inv-1"), attempt, T0);

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.DUPLICATE_ATTEMPT);
        }

        @Test
        @DisplayName("a processor outcome must carry a decline code")
        void declineRequiresACode() {
            assertThatThrownBy(() -> new PaymentProcessor.Outcome(
                PaymentProcessor.Outcome.Status.DECLINED, "ch_1",
                Optional.empty(), Optional.of("something"), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decline must carry");

            assertThatThrownBy(() -> new PaymentProcessor.Outcome(
                PaymentProcessor.Outcome.Status.PENDING, "ch_1",
                Optional.empty(), Optional.empty(), Optional.of(T0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settledAt");
        }
    }

    @Nested
    @DisplayName("Collection service guards")
    class CollectionServiceGuards {

        @Test
        @DisplayName("a method that belongs to another customer cannot be charged")
        void methodCustomerMustMatchInvoice() {
            var ledger = new InMemoryCollectionRepository();
            var service = service(PaymentProcessor.Outcome.settled("ch_1", T0), ledger);
            var foreignMethod = PaymentMethod.of("pm-foreign", TENANT, CustomerId.of("someone-else"),
                PaymentMethodType.CARD, "proc_999", T0);

            assertThatThrownBy(() -> service.collect(openInvoice("100.00", CurrencyUnit.USD),
                foreignMethod, T0, DunningSchedule.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to customer");
            assertThat(ledger.findAttempts(TENANT, "inv-1"))
                .as("a refused charge must leave no trace in the collection ledger")
                .isEmpty();
        }
    }
}