package com.saas.pricing.core.model.collection;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Drives collection of an unpaid invoice along a {@link DunningSchedule}.
 *
 * <p>Pure and stateless: it takes the invoice, the attempts already made, and the time, and returns
 * what should happen next. The caller persists. That keeps the retry ladder trivially testable and
 * means a host can run it from a scheduled job, a queue consumer, or a manual operator action
 * without any of them re-implementing the rules.
 */
public final class DunningEngine {

    private DunningEngine() {
        // static utility
    }

    /** What the engine says should happen next. */
    public record Plan(
        Kind kind,
        Optional<PaymentAttempt> attempt,
        Optional<Invoice> updatedInvoice,
        String reason
    ) {
        public Plan {
            Objects.requireNonNull(kind, "kind cannot be null");
            Objects.requireNonNull(attempt, "attempt cannot be null");
            Objects.requireNonNull(updatedInvoice, "updatedInvoice cannot be null");
            Objects.requireNonNull(reason, "reason cannot be null");
        }

        /** The attempt to make, empty when there is nothing to do. */
        public Optional<PaymentAttempt> attemptIfPresent() {
            return attempt;
        }
    }

    /** The action to take. */
    public enum Kind {
        /** Nothing to do yet: the next attempt is not due, or the invoice is not collectible. */
        NO_ACTION,
        /** Attempt collection now. */
        ATTEMPT_COLLECTION,
        /**
         * The charge is accepted but the money has not moved yet.
         *
         * <p>Distinct from both success and failure, because treating it as either is wrong: settling
         * hands over goods against money that may be returned, and retrying re-charges a debit that
         * is already in flight.
         */
        AWAITING_SETTLEMENT,

        /** The invoice was paid in full; mark it settled. */
        MARK_SETTLED,
        /** The ladder ran out; write the invoice off. */
        WRITE_OFF,
        /** An attempt with this id was already recorded - do not charge again. */
        DUPLICATE_ATTEMPT
    }

    /**
     * Decides what should happen next for {@code invoice}.
     *
     * @param schedule the retry ladder
     * @param attempts attempts already recorded, in any order
     * @param outcome   the outcome of the attempt the caller just made; {@code null} when no attempt
     *                  has been made yet
     * @param now       current time
     */
    public static Plan plan(Invoice invoice, DunningSchedule schedule, List<PaymentAttempt> attempts,
                            PaymentAttempt outcome, Instant now) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        Objects.requireNonNull(schedule, "schedule cannot be null");
        Objects.requireNonNull(attempts, "attempts must not be null");
        Objects.requireNonNull(now, "now cannot be null");

        // A void or already-settled invoice is not a collection candidate.
        if (invoice.status() == InvoiceStatus.VOID) {
            return new Plan(Kind.NO_ACTION, Optional.empty(), Optional.of(invoice),
                "Invoice is void; nothing to collect");
        }

        List<PaymentAttempt> recorded = attempts.stream()
            .sorted(Comparator.comparingInt(PaymentAttempt::attemptNumber))
            .toList();

        // Duplicate protection comes before anything else, and treats ANY re-delivery of an
        // attempt id we already hold as a no-op - not just a conflicting one. The identical
        // re-delivery is by far the commonest case (an agent that timed out and re-sent), and
        // letting it through would settle the invoice a second time.
        if (outcome != null && recorded.stream()
                .anyMatch(a -> a.attemptId().equals(outcome.attemptId()))) {
            return new Plan(Kind.DUPLICATE_ATTEMPT, Optional.of(outcome), Optional.empty(),
                "Attempt " + outcome.attemptId() + " was already recorded; not charging again");
        }

        int made = recorded.size();

        // A pending charge must be handled before success is even considered.
        if (outcome != null && outcome.isPending()) {
            // Not settled, so the invoice is deliberately untouched - and deliberately not written
            // off or retried, because the debit is already in flight. Re-charging a customer whose
            // bank is still processing is how a merchant collects the same invoice twice.
            return new Plan(Kind.AWAITING_SETTLEMENT, Optional.of(outcome), Optional.empty(),
                "Charge " + outcome.attemptId() + " is accepted and awaiting settlement; the invoice "
                    + "stays unpaid until the processor reports settlement or a return");
        }

        // Apply the attempt the caller just reported.
        if (outcome != null && outcome.isSuccessful()) {
            Invoice settled = invoice.recordPayment(outcome.amount());
            return new Plan(Kind.MARK_SETTLED, Optional.of(outcome), Optional.of(settled),
                "Collected " + outcome.amount() + "; invoice settled");
        }

        if (outcome != null && schedule.terminatesOnFailure(outcome.attemptNumber())) {
            return new Plan(Kind.WRITE_OFF, Optional.of(outcome),
                Optional.of(invoice.markUncollectible()),
                "Collection ladder exhausted after " + outcome.attemptNumber()
                    + " attempts; writing the invoice off");
        }

        if (made >= schedule.maxAttempts()) {
            return new Plan(Kind.WRITE_OFF, Optional.ofNullable(lastAttemptOr(outcome, recorded)),
                Optional.of(invoice.markUncollectible()),
                "Maximum attempts (" + schedule.maxAttempts() + ") reached");
        }

        int nextNumber = made + 1;
        Instant firstAttemptAt = recorded.isEmpty()
            ? now
            : recorded.getFirst().attemptedAt();
        Instant dueAt = schedule.dueAt(firstAttemptAt, nextNumber);

        if (dueAt.isAfter(now)) {
            return new Plan(Kind.NO_ACTION, Optional.of(stub(nextNumber, invoice, now)),
                Optional.empty(),
                "Next attempt not due until " + dueAt);
        }

        Money amount = invoice.balanceDue();
        PaymentAttempt attempt = new PaymentAttempt(
            PaymentAttempt.attemptIdFor(invoice.invoiceId(), nextNumber),
            invoice.invoiceId(), nextNumber, amount,
            PaymentAttempt.Status.FAILED_RETRYABLE,
            Optional.of("PENDING"), Optional.of("not yet attempted"),
            now, Optional.of(dueAt));

        return new Plan(Kind.ATTEMPT_COLLECTION, Optional.of(attempt), Optional.empty(),
            "Attempt " + nextNumber + " of " + schedule.maxAttempts() + " is due");
    }

    /**
     * Convenience for the common case: the first attempt on a freshly issued invoice, due now.
     */
    public static Plan firstAttempt(Invoice invoice, DunningSchedule schedule, Instant now) {
        return plan(invoice, schedule, List.of(), null, now);
    }

    private static PaymentAttempt stub(int attemptNumber, Invoice invoice, Instant now) {
        return new PaymentAttempt(
            PaymentAttempt.attemptIdFor(invoice.invoiceId(), attemptNumber),
            invoice.invoiceId(), attemptNumber, invoice.balanceDue(),
            PaymentAttempt.Status.FAILED_RETRYABLE, Optional.of("SCHEDULED"),
            Optional.of("attempt scheduled"), now, Optional.empty());
    }

    private static PaymentAttempt lastAttemptOr(PaymentAttempt outcome, List<PaymentAttempt> recorded) {
        if (outcome != null) {
            return outcome;
        }
        return recorded.isEmpty() ? null : recorded.getLast();
    }
}