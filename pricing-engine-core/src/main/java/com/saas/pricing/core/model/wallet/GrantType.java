package com.saas.pricing.core.model.wallet;

/**
 * Categorization of credit grants in customer wallets.
 */
public enum GrantType {
    /**
     * Promotional / bonus credits (typically burn first before cash, have strict expirations).
     */
    PROMOTIONAL,

    /**
     * Purchased prepaid credits paid upfront by the customer.
     */
    PREPAID,

    /**
     * Minimum contract commitment credits.
     */
    COMMITMENT
}
