package com.saas.pricing.core.model.collection;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A payment method stored against a customer, and the rules it obeys when charged.
 *
 * <h2>Why capability is on the method and not the caller</h2>
 * Whether an ACH debit has settled is a property of the instrument, not of the charge request. A
 * caller that "knows" a method settles immediately is the caller that ships a bug: it settles the
 * invoice on acceptance, and the customer's bank returns the debit three days later. The
 * {@link PaymentMethodType#notification()} answer is therefore not an optimisation hint the caller
 * may skip — it is what {@link #settlesImmediately()} reports.
 *
 * <h2>Why a single currency is a constraint, not a preference</h2>
 * A SEPA debit denominated in EUR cannot collect a USD invoice. Accepting it anyway produces an
 * invoice whose amount can never be collected, discovered only when the dunning ladder gives up.
 *
 * @param methodId     stable identity
 * @param tenantId     owning tenant
 * @param customerId   the customer this method belongs to
 * @param type         the instrument, which decides settlement timing
 * @param processorRef the processor's own identifier; opaque to the engine
 * @param country      issuing country, where the method declares one
 * @param defaultFor   whether this is the customer's default method
 * @param expiresAt    when the method stops working, e.g. a boleto's due date
 * @param createdAt    when it was added
 */
public record PaymentMethod(
    String methodId,
    TenantId tenantId,
    CustomerId customerId,
    PaymentMethodType type,
    String processorRef,
    Optional<String> country,
    boolean defaultFor,
    Optional<Instant> expiresAt,
    Instant createdAt
) implements Serializable {

    public PaymentMethod {
        Objects.requireNonNull(methodId, "methodId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(country, "country cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        if (processorRef == null || processorRef.isBlank()) {
            throw new IllegalArgumentException("processorRef cannot be blank");
        }
        if (expiresAt.isPresent() && !expiresAt.get().isAfter(createdAt)) {
            throw new IllegalArgumentException(
                "expiresAt must be after createdAt; a method that expires on arrival is never usable");
        }
    }

    public static PaymentMethod of(String methodId, TenantId tenantId, CustomerId customerId,
                                   PaymentMethodType type, String processorRef, Instant createdAt) {
        return new PaymentMethod(methodId, tenantId, customerId, type, processorRef,
            Optional.empty(), false, Optional.empty(), createdAt);
    }

    /**
     * True when a charge against this method settles before the call returns.
     *
     * <p>When false the attempt must be recorded as pending and the invoice left unpaid until the
     * processor reports settlement.
     */
    public boolean settlesImmediately() {
        return type.notification() == PaymentMethodType.Notification.IMMEDIATE;
    }

    public boolean isExpired(Instant at) {
        return expiresAt.filter(expiry -> !at.isBefore(expiry)).isPresent();
    }

    /** Checks this method may be used to collect {@code amount}, explaining any refusal. */
    public void requireUsableFor(Money amount, Instant at) {
        Objects.requireNonNull(amount, "amount cannot be null");
        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException(
                "A payment method cannot be charged for a non-positive amount: " + amount);
        }
        if (!type.supportsCurrency(amount.currency().code())) {
            throw new IllegalStateException(
                "Payment method " + methodId + " (" + type + ") cannot collect "
                    + amount.currency().code() + "; it supports " + type.supportedCurrencies());
        }
        if (!type.supportsAutomaticCharging()) {
            throw new IllegalStateException(
                "Payment method " + methodId + " (" + type + ") cannot be charged automatically. "
                    + "Send the customer instructions and record collection when they confirm.");
        }
        if (isExpired(at)) {
            throw new IllegalStateException(
                "Payment method " + methodId + " expired at " + expiresAt.get());
        }
    }

    /** Currency this method can collect, for an operator-facing error. */
    public CurrencyUnit collectableCurrency() {
        String code = type.singleCurrency();
        return code == null ? null : CurrencyUnit.of(code);
    }
}