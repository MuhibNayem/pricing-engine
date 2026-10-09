package com.saas.pricing.core.model.collection;

import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One attempt to collect payment against an invoice.
 *
 * <h2>Why the attempt id is derived, not random</h2>
 * A collection agent that times out must be able to retry without risking a second charge. The
 * {@code attemptId} is therefore {@code invoiceId + "-" + attemptNumber}, which makes a retried
 * request recognisably the <em>same</em> charge: the ledger can recognise the duplicate instead of
 * taking the customer's money twice. A random id per request would make the duplicate undetectable.
 *
 * <p>Attempts are immutable. A failure is corrected by recording the next attempt, never by
 * editing this one - the same append-only discipline as the wallet ledger.
 *
 * @param attemptId     stable, derived identity used for duplicate detection
 * @param invoiceId     invoice being collected
 * @param attemptNumber 1-based
 * @param amount        amount attempted
 * @param status        outcome
 * @param failureCode   processor decline code, when the attempt failed
 * @param failureReason human-readable decline message
 * @param attemptedAt   when the attempt was made
 * @param nextAttemptAt when the next attempt becomes due; empty on success or terminal failure
 */
public record PaymentAttempt(
    String attemptId,
    String invoiceId,
    int attemptNumber,
    Money amount,
    Status status,
    Optional<String> failureCode,
    Optional<String> failureReason,
    Instant attemptedAt,
    Optional<Instant> nextAttemptAt
) implements Serializable {

    /**
     * Outcome of a collection attempt.
     *
     * <p>{@link #PENDING} exists because "the processor accepted it" and "the money arrived" are
     * different claims, and for bank debits they are days apart. A model with only success and
     * failure forces one of two wrong answers: settle the invoice on acceptance and discover the
     * reversal weeks later with nothing watching, or treat a perfectly healthy in-flight debit as a
     * decline and hammer the customer with retries.
     */
    public enum Status {
        /**
         * The processor accepted the charge; the money has not settled yet.
         *
         * <p>The invoice is NOT paid and must not be treated as a failure. The correct action is to
         * wait for the settlement or reversal notification.
         */
        PENDING,
        /** The money arrived. */
        SUCCEEDED,
        /** The processor declined, or a previously pending charge was returned. Retry may follow. */
        FAILED_RETRYABLE,
        /** The processor declined and collection has stopped. */
        FAILED_TERMINAL
    }

    public PaymentAttempt {
        Objects.requireNonNull(attemptId, "attemptId cannot be null");
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(failureCode, "failureCode cannot be null");
        Objects.requireNonNull(failureReason, "failureReason cannot be null");
        Objects.requireNonNull(attemptedAt, "attemptedAt cannot be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt cannot be null");

        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber is 1-based, got " + attemptNumber);
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException("A collection attempt cannot be for a negative amount");
        }
        if (status == Status.SUCCEEDED && !nextAttemptAt.isEmpty()) {
            throw new IllegalArgumentException("A successful attempt cannot schedule another attempt");
        }
        if ((status == Status.FAILED_RETRYABLE || status == Status.FAILED_TERMINAL)
            && failureCode.isEmpty()) {
            throw new IllegalArgumentException(
                "A failed attempt must carry the processor's failure code; without it the decline is unexplainable");
        }
    }

    /** Derives the stable identity for an attempt, so a retry is recognisable as the same charge. */
    public static String attemptIdFor(String invoiceId, int attemptNumber) {
        return invoiceId + "-" + attemptNumber;
    }

    /**
     * Records a charge the processor has accepted but not yet settled.
     *
     * <p>Only correct for a method whose settlement arrives later. For an immediate method this
     * would leave the invoice unpaid with nothing scheduled to resolve it.
     */
    public static PaymentAttempt pending(String invoiceId, int attemptNumber, Money amount, Instant at) {
        return new PaymentAttempt(attemptIdFor(invoiceId, attemptNumber), invoiceId, attemptNumber, amount,
            Status.PENDING, Optional.empty(), Optional.empty(), at, Optional.empty());
    }

    /**
     * The settlement of a pending charge.
     *
     * <p>Same identity, same money: this is the charge completing, not a second charge. Reversing
     * instead would take the customer's money again.
     */
    public PaymentAttempt settledAt(Instant at) {
        requirePending("settle");
        return new PaymentAttempt(attemptId, invoiceId, attemptNumber, amount, Status.SUCCEEDED,
            Optional.empty(), Optional.empty(), at, Optional.empty());
    }

    /**
     * The bank returned a pending debit.
     *
     * <p>Insufficient funds, a closed account, an unauthorised debit. The money never arrived, so the
     * invoice is unpaid again and the dunning ladder resumes from this attempt number.
     */
    public PaymentAttempt reversed(String code, String reason, Instant at, Optional<Instant> nextAttemptAt) {
        requirePending("reverse");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A reversal must carry the bank's return code");
        }
        Status status = nextAttemptAt.isEmpty() ? Status.FAILED_TERMINAL : Status.FAILED_RETRYABLE;
        return new PaymentAttempt(attemptId, invoiceId, attemptNumber, amount, status,
            Optional.of(code), Optional.of(reason), at, nextAttemptAt);
    }

    private void requirePending(String action) {
        if (status != Status.PENDING) {
            throw new IllegalStateException(
                "Cannot " + action + " a " + status + " attempt; only a PENDING charge can change state");
        }
    }

    public static PaymentAttempt succeeded(String invoiceId, int attemptNumber, Money amount, Instant at) {
        return new PaymentAttempt(attemptIdFor(invoiceId, attemptNumber), invoiceId, attemptNumber, amount,
            Status.SUCCEEDED, Optional.empty(), Optional.empty(), at, Optional.empty());
    }

    public static PaymentAttempt failed(String invoiceId, int attemptNumber, Money amount, Instant at,
                                        String code, String reason, Optional<Instant> nextAttemptAt) {
        // Validated here so a missing decline code is a clear refusal rather than the NPE that
        // Optional.of(null) would otherwise raise deep inside the record constructor.
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A failed attempt must carry a non-blank failure code");
        }
        Status status = nextAttemptAt.isEmpty() ? Status.FAILED_TERMINAL : Status.FAILED_RETRYABLE;
        return new PaymentAttempt(attemptIdFor(invoiceId, attemptNumber), invoiceId, attemptNumber, amount,
            status, Optional.of(code), Optional.of(reason), at, nextAttemptAt);
    }

    /** True when this attempt left the invoice collected. A pending charge has NOT. */
    public boolean isSuccessful() {
        return status == Status.SUCCEEDED;
    }

    /** True when the charge is accepted but the money has not moved yet. */
    public boolean isPending() {
        return status == Status.PENDING;
    }
}