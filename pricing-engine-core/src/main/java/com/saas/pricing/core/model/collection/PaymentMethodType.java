package com.saas.pricing.core.model.collection;

import java.util.Set;

/**
 * How a payment method tells you whether money moved.
 *
 * <p>This distinction is the single most consequential fact about payment methods, and the one most
 * billing integrations get wrong. A card charge is settled or declined within seconds. An ACH debit
 * or a SEPA transfer is <em>accepted</em> immediately and settles days later — and can be
 * <strong>returned</strong> afterwards for insufficient funds, a closed account, or an unauthorised
 * debit. Treating acceptance as payment hands over goods and marks an invoice settled against money
 * that may never arrive, and nothing in the system is watching for the reversal.
 *
 * <p>So a method declares its own notification timing, and the collection machinery is driven by it
 * rather than assuming everything is instantaneous.
 */
public enum PaymentMethodType {

    /** Card. Immediate, reusable, multi-currency. */
    CARD(Notification.IMMEDIATE, true, true, null),

    /** SEPA direct debit. EUR only; settles in days and can be returned. */
    SEPA_DEBIT(Notification.DELAYED, true, true, "EUR"),

    /** US bank debit. USD only; settles in days and is subject to returns. */
    ACH_DEBIT(Notification.DELAYED, true, true, "USD"),

    /**
     * Bank transfer.
     *
     * <p>Not reusable: each payment needs fresh account details, so it can only be collected by
     * sending the customer instructions and waiting.
     */
    BANK_TRANSFER(Notification.DELAYED, false, false, null),

    /**
     * Boleto. BRL only, single-use, and expires — a boleto presented after its due date is simply
     * never paid, which is why it needs its own expiry rather than an open-ended wait.
     */
    BOLETO(Notification.DELAYED, false, false, "BRL"),

    /**
     * Cash or cheque, settled offline.
     *
     * <p>Never charged automatically; the invoice is marked collected by a human after the money is
     * counted, so it cannot participate in an automatic retry ladder.
     */
    OFFLINE(Notification.DELAYED, false, false, null);

    /** When the customer (or their bank) tells you the money actually moved. */
    public enum Notification {
        /** The processor answers synchronously with the real outcome. */
        IMMEDIATE,
        /** Acceptance is not payment. Settlement — or reversal — arrives later. */
        DELAYED
    }

    private final Notification notification;
    private final boolean reusable;
    private final boolean supportsAutomaticCharging;
    private final String singleCurrency;

    PaymentMethodType(Notification notification, boolean reusable,
                      boolean supportsAutomaticCharging, String singleCurrency) {
        this.notification = notification;
        this.reusable = reusable;
        this.supportsAutomaticCharging = supportsAutomaticCharging;
        this.singleCurrency = singleCurrency;
    }

    public Notification notification() {
        return notification;
    }

    /** True when settlement arrives later, so an attempt must be recorded as pending, not paid. */
    public boolean isDelayedNotification() {
        return notification == Notification.DELAYED;
    }

    /** True when the same stored details can be charged again. */
    public boolean isReusable() {
        return reusable;
    }

    /**
     * False for methods that need per-payment instructions.
     *
     * <p>They cannot be pulled automatically, so putting one in an automatic dunning ladder
     * guarantees failure: the engine would retry the same instruction, which either re-sends the
     * same reference or is impossible.
     */
    public boolean supportsAutomaticCharging() {
        return supportsAutomaticCharging;
    }

    /** The currency this method may charge, or null when it may charge any. */
    public String singleCurrency() {
        return singleCurrency;
    }

    /** True when this method may charge {@code currency}. */
    public boolean supportsCurrency(String currency) {
        return singleCurrency == null
            || singleCurrency.equalsIgnoreCase(currency);
    }

    /** The currencies this method can never charge, for explaining a rejection. */
    public Set<String> supportedCurrencies() {
        return singleCurrency == null ? Set.of() : Set.of(singleCurrency);
    }
}