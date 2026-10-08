package com.saas.pricing.core.model.hierarchy;

/**
 * Precedence levels in the enterprise pricing catalog hierarchy.
 */
public enum CatalogHierarchyLevel {
    /**
     * Customer-specific negotiated contract override (highest precedence).
     */
    CONTRACT_OVERRIDE,

    /**
     * Account / tenant level default rate plan.
     */
    ACCOUNT_DEFAULT,

    /**
     * Global fallback product catalog (lowest precedence).
     */
    GLOBAL_CATALOG
}
