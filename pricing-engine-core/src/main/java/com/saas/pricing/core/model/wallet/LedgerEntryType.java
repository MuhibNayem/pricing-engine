package com.saas.pricing.core.model.wallet;

/**
 * The kind of movement a {@link LedgerEntry} records.
 *
 * <p>Every entry carries a signed value: grants and credits add, drawdowns subtract, and a
 * <em>reversal</em> carries exactly the negated value of the entry it reverses. Correcting a
 * mistake therefore means appending, never editing — which is what makes a disputed figure
 * reconstructible.
 */
public enum LedgerEntryType {

    /** Credits granted and available to spend. */
    GRANT_ISSUED,

    /** An ordinary drawdown against available credit. */
    DRAWDOWN,

    /** A compensating entry that negates a previous one. Never mutates the original. */
    REVERSAL,

    /**
     * A manual correction that is not tied to reversing a specific entry, for example a rounding
     * true-up or a goodwill adjustment. Always carries a reason.
     */
    ADJUSTMENT
}