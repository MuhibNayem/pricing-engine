package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;

/**
 * Primary interface for evaluating SaaS pricing calculations.
 */
public interface PricingEngine {

    /**
     * Evaluates a pricing request deterministically against configured rate cards,
     * tiers, matrix dimensions, formulas, discounts, and proration windows.
     *
     * @param request the pricing evaluation request
     * @return the complete calculation result with financial ledger and audit trace
     */
    PricingResult evaluate(PricingRequest request);
}
