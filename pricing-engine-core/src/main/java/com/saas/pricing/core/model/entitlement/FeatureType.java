package com.saas.pricing.core.model.entitlement;

/**
 * Types of product entitlement features.
 */
public enum FeatureType {
    /**
     * Binary gate / feature flag (e.g. SSO, Custom Domain, Dedicated IP).
     */
    BOOLEAN,

    /**
     * Recurring metered allowance that resets per billing period (e.g. 10,000 API calls/month).
     */
    METERED_RECURRING,

    /**
     * Static total allowance that does not reset (e.g. 5 free onboarding migrations).
     */
    METERED_STATIC
}
