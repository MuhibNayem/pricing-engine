package com.saas.pricing.core.model.collection;

import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.invoice.Invoice;
import com.saas.pricing.core.model.invoice.InvoiceStatus;
import com.saas.pricing.core.spi.CollectionRepository;
import com.saas.pricing.core.spi.PaymentProcessor;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Collects an invoice against a stored payment method.
 *
 * <h2>The rule this exists to enforce</h2>
 * A charge is recorded as <em>paid</em> only when the money moved. For a delayed-notification method
 * — an ACH debit, a SEPA transfer, a boleto — the processor accepting the charge proves nothing;
 * the customer's bank may return it days later. The invoice therefore stays unpaid, the attempt is
 * recorded as pending, and the dunning ladder waits rather than re-charging a debit that is already
 * in flight.
 *
 * <h2>Why the ledger is written before the plan is applied</h2>
 * The attempt is appended first, so a crash between charging and recording leaves an unresolved
 * pending charge an operator can see, rather than a charge the processor made and this system has
 * no record of.
 */
public class InvoiceCollectionService {

    private final PaymentProcessor processor;
    private final CollectionRepository attempts;

    public InvoiceCollectionService(PaymentProcessor processor, CollectionRepository attempts) {
        this.processor = Objects.requireNonNull(processor, "processor cannot be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts cannot be null");
    }

    /**
     * Charges {@code invoice} against {@code method}.
     *
     * @param invoice the invoice to collect; must be OPEN
     * @param method  the stored method to charge
     * @param at      the attempt instant
     * @param schedule the ladder deciding whether to try at all and what follows
     * @return the attempt recorded, and the invoice as it now stands
     */
    public Result collect(Invoice invoice, PaymentMethod method, Instant at,
                          DunningSchedule schedule) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        Objects.requireNonNull(method, "method cannot be null");
        Objects.requireNonNull(at, "at cannot be null");
        Objects.requireNonNull(schedule, "schedule cannot be null");

        if (invoice.status() != InvoiceStatus.OPEN) {
            throw new IllegalStateException(
                "Only an OPEN invoice can be collected; this one is " + invoice.status());
        }

        // The method must belong to the customer being billed. Without this, a caller could direct
        // a charge for one customer's stored instrument against another customer's invoice and rely
        // on the processor - rather than this domain - to refuse it.
        if (!method.customerId().equals(invoice.customerId())) {
            throw new IllegalArgumentException(
                "Payment method " + method.methodId() + " belongs to customer "
                    + method.customerId().value() + " but invoice " + invoice.invoiceId()
                    + " is for " + invoice.customerId().value());
        }

        Money amount = invoice.balanceDue();
        // Refuses a currency mismatch, an expired method, and a method that cannot be charged
        // automatically - each with a message an operator can act on.
        method.requireUsableFor(amount, at);

        var prior = attempts.findAttempts(method.tenantId(), invoice.invoiceId());
        int attemptNumber = prior.size() + 1;

        var request = new PaymentProcessor.ChargeRequest(
            PaymentAttempt.attemptIdFor(invoice.invoiceId(), attemptNumber),
            method.tenantId().value(),
            invoice.invoiceId(),
            method.customerId().value(),
            method.processorRef(),
            method.type(),
            amount,
            "Invoice " + invoice.invoiceNumber().orElse(invoice.invoiceId()),
            at);

        PaymentProcessor.Outcome outcome = processor.charge(request);

        // An immediate method that reports PENDING would leave the invoice unpaid with nothing
        // scheduled to resolve it, so the inconsistency is refused rather than absorbed.
        if (outcome.outcome() == PaymentProcessor.Outcome.Status.PENDING && method.settlesImmediately()) {
            throw new IllegalStateException(
                "Processor reported a pending charge for an immediate method (" + method.type()
                    + "); an invoice would stay unpaid with nothing scheduled to resolve it");
        }

        PaymentAttempt attempt = toAttempt(invoice, attemptNumber, amount, outcome, at,
            prior, schedule);

        // Ledger first: an unresolved charge is recoverable, an unrecorded charge is not.
        boolean newlyRecorded = attempts.record(attempt);
        if (!newlyRecorded) {
            // The attempt is already on file. Return the STORED attempt with the unchanged invoice:
            // returning the freshly derived outcome would let a redelivery report a settlement (or
            // a different status) that was never applied to the document.
            PaymentAttempt stored = attempts.findAttempts(method.tenantId(), invoice.invoiceId()).stream()
                .filter(existing -> existing.attemptId().equals(attempt.attemptId()))
                .findFirst()
                .orElse(attempt);
            return new Result(stored, invoice, false);
        }

        // `prior`, NOT the post-write list: the engine's duplicate guard compares the reported
        // outcome against the attempts already on file, and passing the attempt we have just
        // appended makes every charge look like a re-delivery - so the invoice would never settle.
        var plan = DunningEngine.plan(invoice, schedule, prior, attempt, at);

        return new Result(attempt, plan.updatedInvoice().orElse(invoice), true);
    }

    private PaymentAttempt toAttempt(Invoice invoice, int attemptNumber, Money amount,
                                     PaymentProcessor.Outcome outcome, Instant at,
                                     java.util.List<PaymentAttempt> prior, DunningSchedule schedule) {
        String invoiceId = invoice.invoiceId();
        return switch (outcome.outcome()) {
            case SETTLED -> PaymentAttempt.succeeded(invoiceId, attemptNumber, amount,
                outcome.settledAt().orElse(at));
            case PENDING -> PaymentAttempt.pending(invoiceId, attemptNumber, amount, at);
            case DECLINED -> PaymentAttempt.failed(invoiceId, attemptNumber, amount, at,
                outcome.failureCode().orElseThrow(),
                outcome.failureReason().orElse("Declined by processor"),
                nextAttemptAfter(attemptNumber, at, prior, schedule));
        };
    }

    /**
     * When the ladder should try again after a decline, or never.
     *
     * <p>A decline is almost always retryable - a card is rejected for insufficient funds long
     * before the customer tops up. Recording it as terminal, because no follow-up time was supplied,
     * would write off an invoice on its first rejection and never contact the customer again.
     */
    private Optional<Instant> nextAttemptAfter(int attemptNumber, Instant at,
                                               java.util.List<PaymentAttempt> prior,
                                               DunningSchedule schedule) {
        int nextNumber = attemptNumber + 1;
        if (schedule.terminatesOnFailure(nextNumber)) {
            return Optional.empty();
        }
        Instant firstAttemptAt = prior.isEmpty() ? at : prior.getFirst().attemptedAt();
        return Optional.of(schedule.dueAt(firstAttemptAt, nextNumber));
    }

    /**
     * The result of one collection.
     *
     * @param attempt  the attempt recorded
     * @param invoice  the invoice as it now stands; unchanged while a charge is pending
     * @param charged  false when this was a duplicate the ledger already had
     */
    public record Result(PaymentAttempt attempt, Invoice invoice, boolean charged) {

        public Result {
            Objects.requireNonNull(attempt, "attempt cannot be null");
            Objects.requireNonNull(invoice, "invoice cannot be null");
        }

        /** True when the money actually arrived, as opposed to being accepted and in flight. */
        public boolean isSettled() {
            return attempt.isSuccessful();
        }
    }
}