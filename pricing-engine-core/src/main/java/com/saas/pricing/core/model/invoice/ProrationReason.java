package com.saas.pricing.core.model.invoice;

import java.io.Serializable;

/**
 * Why a proration adjustment exists.
 *
 * <h2>Classification is by operation, never by period length</h2>
 * A full-period debit created because the billing cycle anchor moved <strong>is</strong> a
 * proration. The identical full-period debit created by simply starting a subscription
 * <strong>is not</strong>. Nothing about the amount or the dates distinguishes them - only the
 * operation that caused it.
 *
 * <p>Getting this wrong is not cosmetic. Downstream accounting treats the two differently: a
 * proration adjusts a period that already ran, while a non-proration charge opens a new one. Classify
 * by amount or by date length and every mid-cycle anchor move gets booked as a new subscription,
 * which breaks revenue recognition for the affected period.
 *
 * <p>It is also why {@link #TRIAL_ENDED} produces <em>both</em> kinds in one operation: a proration
 * credit for the unused trial time, plus a non-proration debit for the next complete period.
 */
public enum ProrationReason implements Serializable {

    /** A plan was changed mid-period: credit for the old plan, debit for the new. */
    PLAN_CHANGED(true, true),

    /** The billing cycle anchor moved, re-cutting a period that had already run. */
    ANCHOR_CHANGED(true, true),

    /**
     * The subscription started part-way through a period.
     *
     * <p>The first full-period charge is <em>not</em> a proration, even though it may cover a whole
     * period and even though the subscription did not start at the beginning of it.
     */
    SUBSCRIPTION_STARTED(false, true),

    /** The subscription was cancelled early: credit only. */
    CANCELLED_EARLY(true, false),

    /** The subscription was paused. */
    PAUSED(true, false),

    /**
     * The subscription resumed.
     *
     * <p>A debit: the remaining period is now chargeable again.
     */
    RESUMED(true, true),

    /**
     * A trial ended.
     *
     * <p>The only case that legitimately emits a proration credit (unused trial time) and a
     * non-proration debit (the next complete period) together.
     */
    TRIAL_ENDED(true, false),

    /** An off-cycle correction, for example a goodwill credit. */
    MANUAL_ADJUSTMENT(true, true);

    private final boolean proration;
    private final boolean producesDebit;

    ProrationReason(boolean proration, boolean producesDebit) {
        this.proration = proration;
        this.producesDebit = producesDebit;
    }

    /**
     * True when this adjustment adjusts a period that has already run.
     *
     * <p>This is the accounting distinction, and it is the whole reason the enum exists.
     */
    public boolean isProration() {
        return proration;
    }

    /** True when this operation raises a charge against the customer. */
    public boolean producesDebit() {
        return producesDebit;
    }

    /** True when this operation lowers what the customer owes. */
    public boolean producesCredit() {
        return proration && !producesDebit;
    }

    /** True when this operation yields both a credit and a debit. */
    public boolean producesBoth() {
        return proration && producesDebit;
    }

    /**
     * Classifies a full-period charge by the operation that created it.
     *
     * @param reason the operation
     * @return whether that operation is a proration
     */
    public static boolean isProration(ProrationReason reason) {
        return reason != null && reason.isProration();
    }
}