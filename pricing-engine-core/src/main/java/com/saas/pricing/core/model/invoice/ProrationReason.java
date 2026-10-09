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
 * <p>Each constant declares which SIDES it can carry. Most operations produce both; a subscription
 * start produces only a debit, and a cancellation only a credit. {@link #TRIAL_ENDED} is the one
 * operation whose debit is not itself a proration (it opens the next full period), which is why the
 * per-operation classification and the side flags are separate properties.
 */
public enum ProrationReason implements Serializable {

    /** A plan was changed mid-period: credit for the old plan, debit for the new. */
    PLAN_CHANGED(true, true, true),

    /** The billing cycle anchor moved, re-cutting a period that had already run. */
    ANCHOR_CHANGED(true, true, true),

    /**
     * The subscription started part-way through a period.
     *
     * <p>The first full-period charge is <em>not</em> a proration, even though it may cover a whole
     * period and even though the subscription did not start at the beginning of it.
     */
    SUBSCRIPTION_STARTED(false, false, true),

    /** The subscription was cancelled early: credit only. */
    CANCELLED_EARLY(true, true, false),

    /** The subscription was paused: credit for the unused remainder. */
    PAUSED(true, true, false),

    /**
     * The subscription resumed.
     *
     * <p>A debit: the remaining period is now chargeable again.
     */
    RESUMED(true, false, true),

    /**
     * A trial ended.
     *
     * <p>The only case that legitimately emits a proration credit (unused trial time) and a
     * non-proration debit (the next complete period) together.
     */
    TRIAL_ENDED(true, true, true),

    /** An off-cycle correction, for example a goodwill credit. */
    MANUAL_ADJUSTMENT(true, true, true);

    private final boolean proration;
    private final boolean credit;
    private final boolean debit;

    ProrationReason(boolean proration, boolean credit, boolean debit) {
        this.proration = proration;
        this.credit = credit;
        this.debit = debit;
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
        return debit;
    }

    /** True when this operation lowers what the customer owes. */
    public boolean producesCredit() {
        return credit;
    }

    /** True when this operation yields both a credit and a debit. */
    public boolean producesBoth() {
        return credit && debit;
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
