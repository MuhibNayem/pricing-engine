package com.saas.pricing.core.model.invoice;

/**
 * Invoice lifecycle states.
 *
 * <p>Modelled on the state machine every real billing system converges on, because the transitions
 * carry contractual meaning: a finalized invoice is a demand for payment, a voided one is not, and
 * an uncollectible one is a write-off that must still be reported.
 *
 * <pre>
 *   DRAFT ──finalize──▶ OPEN ──pay──▶ PAID
 *     │                  │
 *     │                  ├──void──▶ VOID
 *     │                  │
 *     │                  └──markUncollectible──▶ UNCOLLECTIBLE ──pay──▶ PAID
 *     │                                                 │
 *     └──delete                                          └──void──▶ VOID
 * </pre>
 *
 * <p>DRAFT is the only editable state. Finalization is a hard boundary: it assigns the invoice
 * number and makes the commercial terms immutable, so a rating correction afterwards must go through
 * a credit note rather than an edit.
 */
public enum InvoiceStatus {

    /** Editable. Not yet a demand for payment, and not yet numbered. */
    DRAFT,

    /** Finalized and awaiting payment. Terms are immutable. */
    OPEN,

    /** Settled in full. */
    PAID,

    /** Cancelled before or after issuance. Retained for audit, never collected. */
    VOID,

    /** Written off. Still reported, but no longer expected to be collected. */
    UNCOLLECTIBLE;

    /** True when the invoice's terms can no longer be edited. */
    public boolean isImmutable() {
        return this != DRAFT;
    }

    /** True when the invoice represents a demand for payment. */
    public boolean isCollectible() {
        return this == OPEN || this == UNCOLLECTIBLE;
    }
}