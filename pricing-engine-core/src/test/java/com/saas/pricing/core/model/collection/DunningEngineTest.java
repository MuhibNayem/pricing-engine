package com.saas.pricing.core.model.collection;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceLineItem;
import com.saas.pricing.core.model.invoice.InvoiceStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Collection behaviour.
 *
 * <p>Two properties carry the weight here: an invoice is never collected twice for the same attempt,
 * and the ladder actually terminates rather than leaving money pending forever.
 */
class DunningEngineTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");

    private static Invoice openInvoice(String amount) {
        Invoice draft = Invoice.draft("inv-1", TenantId.of("t1"), CustomerId.of("c1"),
                PlanCode.of("PRO"), USD, T0, T0.plus(Duration.ofDays(30)), T0)
            .addLine(InvoiceLineItem.of("SEATS", "Seats", BigDecimal.ONE, Money.of(amount, USD), "TX"))
            .build();
        return draft.finalizeInvoice("INV-2026-0001", T0);
    }

    @Nested
    @DisplayName("Schedule")
    class Schedule {

        @Test
        @DisplayName("attempt due dates follow the ladder")
        void dueDatesFollowLadder() {
            var schedule = DunningSchedule.standard();

            assertThat(schedule.dueAt(T0, 1)).isEqualTo(T0);
            assertThat(schedule.dueAt(T0, 2)).isEqualTo(T0.plus(Duration.ofDays(1)));
            assertThat(schedule.dueAt(T0, 3)).isEqualTo(T0.plus(Duration.ofDays(3)));
        }

        @Test
        @DisplayName("more attempts than rungs is a configuration error, not a runtime surprise")
        void attemptsCannotExceedSteps() {
            assertThatThrownBy(() -> new DunningSchedule(
                List.of(new DunningSchedule.DunningStep(Duration.ofDays(1),
                    DunningSchedule.FailureAction.RETRY)), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot retry more times than it has rungs");
        }

        @Test
        @DisplayName("the standard ladder terminates")
        void standardLadderTerminates() {
            var schedule = DunningSchedule.standard();

            assertThat(schedule.isExhausted(schedule.maxAttempts())).isTrue();
            assertThat(schedule.terminatesOnFailure(schedule.maxAttempts())).isTrue();
            assertThat(schedule.terminatesOnFailure(1))
                .as("the first failure retries rather than writing off")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("Attempts")
    class Attempts {

        @Test
        @DisplayName("the attempt id is derived, so a retry is recognisable as the same charge")
        void attemptIdIsDerived() {
            assertThat(PaymentAttempt.attemptIdFor("inv-1", 2)).isEqualTo("inv-1-2");
        }

        @Test
        @DisplayName("a failure without a processor code is refused")
        void failureNeedsCode() {
            assertThatThrownBy(() -> PaymentAttempt.failed("inv-1", 1, Money.of("10.00", USD), T0,
                    null, "declined", java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure code");
        }

        @Test
        @DisplayName("a successful attempt cannot schedule a retry")
        void successCannotScheduleRetry() {
            assertThatThrownBy(() -> new PaymentAttempt("inv-1-1", "inv-1", 1, Money.of("10.00", USD),
                    PaymentAttempt.Status.SUCCEEDED, java.util.Optional.empty(), java.util.Optional.empty(),
                    T0, java.util.Optional.of(T0)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Ladder")
    class Ladder {

        @Test
        @DisplayName("a fresh invoice is collected immediately")
        void firstAttemptIsDueImmediately() {
            var plan = DunningEngine.firstAttempt(openInvoice("100.00"), DunningSchedule.standard(), T0);

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.ATTEMPT_COLLECTION);
            assertThat(plan.attemptIfPresent().orElseThrow().attemptNumber()).isEqualTo(1);
            assertThat(plan.attemptIfPresent().orElseThrow().amount().amount()).isEqualByComparingTo("100.00");
            assertThat(plan.attemptIfPresent().orElseThrow().attemptId()).isEqualTo("inv-1-1");
        }

        @Test
        @DisplayName("a failed attempt schedules the next one a day later")
        void failureSchedulesNextAttempt() {
            var invoice = openInvoice("100.00");
            var schedule = DunningSchedule.standard();
            var first = DunningEngine.firstAttempt(invoice, schedule, T0);

            var failed = PaymentAttempt.failed("inv-1", 1, first.attemptIfPresent().orElseThrow().amount(), T0,
                "card_declined", "insufficient funds", java.util.Optional.of(T0.plus(Duration.ofDays(1))));

            var plan = DunningEngine.plan(invoice, schedule, List.of(failed), null, T0.plus(Duration.ofHours(2)));

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.NO_ACTION);
            assertThat(plan.reason()).contains("not due until");

            var dueTomorrow = DunningEngine.plan(invoice, schedule, List.of(failed), null,
                T0.plus(Duration.ofDays(1)));
            assertThat(dueTomorrow.kind()).isEqualTo(DunningEngine.Kind.ATTEMPT_COLLECTION);
            assertThat(dueTomorrow.attemptIfPresent().orElseThrow().attemptNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("a successful attempt settles the invoice")
        void successSettles() {
            var invoice = openInvoice("100.00");
            var schedule = DunningSchedule.standard();
            var first = DunningEngine.firstAttempt(invoice, schedule, T0);
            var paid = PaymentAttempt.succeeded("inv-1", 1, first.attemptIfPresent().orElseThrow().amount(), T0);

            var plan = DunningEngine.plan(invoice, schedule, List.of(), paid, T0);

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.MARK_SETTLED);
            assertThat(plan.updatedInvoice().orElseThrow().status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(plan.updatedInvoice().orElseThrow().isSettled()).isTrue();
        }

        @Test
        @DisplayName("the ladder terminates in a write-off rather than pending forever")
        void ladderTerminatesInWriteOff() {
            var invoice = openInvoice("100.00");
            var schedule = DunningSchedule.standard();
            var attempts = new ArrayList<PaymentAttempt>();
            Instant clock = T0;

            DunningEngine.Kind kind = null;
            for (int i = 1; i <= schedule.maxAttempts() + 1; i++) {
                var next = DunningEngine.plan(invoice, schedule, attempts, null, clock);
                if (next.kind() != DunningEngine.Kind.ATTEMPT_COLLECTION) {
                    kind = next.kind();
                    break;
                }
                var dueAttempt = next.attemptIfPresent().orElseThrow();
                attempts.add(PaymentAttempt.failed("inv-1", dueAttempt.attemptNumber(),
                    dueAttempt.amount(), clock, "card_declined", "declined",
                    java.util.Optional.of(schedule.dueAt(T0, dueAttempt.attemptNumber()))));
                // Jump to the NEXT attempt's due date; a fixed increment would leave the clock
                // behind the growing ladder and the loop would exit on NO_ACTION, never reaching
                // the write-off it is meant to prove. Guarded, because there is no attempt
                // beyond the last rung.
                if (dueAttempt.attemptNumber() < schedule.maxAttempts()) {
                    clock = schedule.dueAt(T0, dueAttempt.attemptNumber() + 1);
                } else {
                    clock = clock.plus(Duration.ofDays(1));
                }
            }

            assertThat(kind)
                .as("an invoice must not sit OPEN pretending money is coming")
                .isEqualTo(DunningEngine.Kind.WRITE_OFF);
        }

        @Test
        @DisplayName("a re-delivered attempt is recognised and never charged twice")
        void duplicateAttemptIsNotChargedTwice() {
            var invoice = openInvoice("100.00");
            var schedule = DunningSchedule.standard();
            var first = DunningEngine.firstAttempt(invoice, schedule, T0);
            var succeeded = PaymentAttempt.succeeded("inv-1", 1, first.attemptIfPresent().orElseThrow().amount(), T0);

            // The agent timed out and re-sent the same success. The ledger already has it.
            var plan = DunningEngine.plan(invoice, schedule, List.of(succeeded), succeeded, T0.plusSeconds(1));

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.DUPLICATE_ATTEMPT);
            assertThat(plan.reason()).contains("not charging again");
        }

        @Test
        @DisplayName("a void invoice is not collected")
        void voidInvoiceIsNotCollected() {
            var voided = openInvoice("100.00").voidInvoice(T0);

            var plan = DunningEngine.firstAttempt(voided, DunningSchedule.standard(), T0);

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.NO_ACTION);
            assertThat(plan.reason()).contains("void");
        }
    }

    @Nested
    @DisplayName("Attempt numbering and partial settlement")
    class AttemptNumbering {

        @Test
        @DisplayName("a declined attempt schedules the NEXT number, not the one just made")
        void declinedAttemptDoesNotRepeatItsOwnNumber() {
            var invoice = openInvoice("100.00");
            var declined = PaymentAttempt.failed("inv-1", 1, Money.of("100.00", USD), T0,
                "insufficient_funds", "no funds", java.util.Optional.of(T0.plus(Duration.ofDays(1))));

            // The caller deliberately passes the attempts recorded BEFORE this one. Deriving the next
            // number from the list size alone used to return 1 again - the id just recorded. The next
            // attempt is due one day after the first, so evaluate at that instant.
            var plan = DunningEngine.plan(invoice, DunningSchedule.standard(), List.of(), declined,
                T0.plus(Duration.ofDays(1)));

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.ATTEMPT_COLLECTION);
            assertThat(plan.attemptIfPresent().orElseThrow().attemptNumber()).isEqualTo(2);
            assertThat(plan.attemptIfPresent().orElseThrow().attemptId()).isEqualTo("inv-1-2");
        }

        @Test
        @DisplayName("a partial payment leaves the invoice open and is not reported as settled")
        void partialPaymentIsNotSettled() {
            var invoice = openInvoice("100.00");
            var partial = PaymentAttempt.succeeded("inv-1", 1, Money.of("40.00", USD), T0);

            var plan = DunningEngine.plan(invoice, DunningSchedule.standard(), List.of(), partial, T0);

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.PARTIAL_PAYMENT);
            assertThat(plan.updatedInvoice().orElseThrow().balanceDue().amount())
                .isEqualByComparingTo("60.00");
            assertThat(plan.updatedInvoice().orElseThrow().isSettled()).isFalse();
        }

        @Test
        @DisplayName("a full payment is reported as settled")
        void fullPaymentSettled() {
            var invoice = openInvoice("100.00");
            var full = PaymentAttempt.succeeded("inv-1", 1, Money.of("100.00", USD), T0);

            var plan = DunningEngine.plan(invoice, DunningSchedule.standard(), List.of(), full, T0);

            assertThat(plan.kind()).isEqualTo(DunningEngine.Kind.MARK_SETTLED);
            assertThat(plan.updatedInvoice().orElseThrow().isSettled()).isTrue();
        }
    }
}