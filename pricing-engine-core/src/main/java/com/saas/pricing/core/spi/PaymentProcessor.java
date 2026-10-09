package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.collection.PaymentMethodType;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A payment processor (Stripe, Adyen, a bank gateway, a manual console).
 *
 * <h2>The charge must be idempotent</h2>
 * {@code charge} is keyed on {@link ChargeRequest#idempotencyKey()}, derived from the invoice and
 * attempt number. A collection agent that times out cannot tell whether its charge landed, and the
 * only safe answer is to retry with the same key. A processor that ignores the key turns every
 * network hiccup into a double charge, which is the single most expensive defect in payment
 * integration.
 *
 * <h2>The result may not be final</h2>
 * For a {@link PaymentMethodType#isDelayedNotification()} method a processor MUST report
 * {@link Outcome#PENDING} on acceptance, never {@link Outcome#SETTLED}. Settling on acceptance for
 * an ACH debit is the bug this contract exists to make impossible: the customer's bank can return
 * the debit days later, and by then goods have shipped and the invoice reads as paid.
 */
public interface PaymentProcessor {

    /**
     * Charges a method, idempotently.
     *
     * @return what the processor can honestly report right now - not what will eventually happen
     */
    Outcome charge(ChargeRequest request);

    /**
     * One charge request.
     *
     * @param idempotencyKey stable per (invoice, attempt); a retry MUST reuse it
     * @param tenantId       owning tenant
     * @param invoiceId      invoice being collected
     * @param customerId     customer being charged
     * @param processorRef   the processor's identifier for the payment method
     * @param type           the instrument, which tells the processor what settlement looks like
     * @param amount         amount to collect
     * @param description    appears on the customer's statement; make it recognisable
     * @param requestedAt    when the charge was requested
     */
    record ChargeRequest(
        String idempotencyKey,
        String tenantId,
        String invoiceId,
        String customerId,
        String processorRef,
        PaymentMethodType type,
        Money amount,
        String description,
        Instant requestedAt
    ) {
        public ChargeRequest {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
            Objects.requireNonNull(customerId, "customerId cannot be null");
            Objects.requireNonNull(processorRef, "processorRef cannot be null");
            Objects.requireNonNull(type, "type cannot be null");
            Objects.requireNonNull(amount, "amount cannot be null");
            Objects.requireNonNull(description, "description cannot be null");
            Objects.requireNonNull(requestedAt, "requestedAt cannot be null");
            if (idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey cannot be blank");
            }
        }
    }

    /**
     * What the processor can report now.
     *
     * @param outcome      how far the charge got
     * @param processorRef the processor's charge identifier, needed later for settlement or return
     * @param failureCode  decline code, required whenever the outcome is DECLINED
     * @param failureReason human-readable decline message
     * @param settledAt    when the money moved; empty unless SETTLED
     */
    record Outcome(
        Status outcome,
        String processorRef,
        Optional<String> failureCode,
        Optional<String> failureReason,
        Optional<Instant> settledAt
    ) {
        /** Processor-reported state. */
        public enum Status {
            /** Money moved and the charge is final. */
            SETTLED,
            /** Accepted; settlement or a return comes later. */
            PENDING,
            /** Refused. The customer has not been charged. */
            DECLINED
        }

        public Outcome {
            Objects.requireNonNull(outcome, "outcome cannot be null");
            Objects.requireNonNull(processorRef, "processorRef cannot be null");
            Objects.requireNonNull(failureCode, "failureCode cannot be null");
            Objects.requireNonNull(failureReason, "failureReason cannot be null");
            Objects.requireNonNull(settledAt, "settledAt cannot be null");
            if (outcome == Status.DECLINED && failureCode.isEmpty()) {
                throw new IllegalArgumentException(
                    "A decline must carry the processor's code; without it the customer cannot be told why");
            }
            if (outcome != Status.SETTLED && settledAt.isPresent()) {
                throw new IllegalArgumentException(
                    "settledAt is only meaningful for a settled charge, not " + outcome);
            }
        }

        public static Outcome settled(String ref, Instant at) {
            return new Outcome(Status.SETTLED, ref, Optional.empty(), Optional.empty(), Optional.of(at));
        }

        public static Outcome pending(String ref) {
            return new Outcome(Status.PENDING, ref, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Outcome declined(String ref, String code, String reason) {
            return new Outcome(Status.DECLINED, ref, Optional.of(code), Optional.of(reason),
                Optional.empty());
        }
    }
}