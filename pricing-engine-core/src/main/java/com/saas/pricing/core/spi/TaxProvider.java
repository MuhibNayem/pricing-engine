package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.TaxRate;
import com.saas.pricing.core.model.TenantId;

import java.util.List;
import java.util.Map;

/**
 * SPI for resolving tax rates for line items.
 */
public interface TaxProvider {

    /**
     * Resolves tax rates applicable to an item based on tenant and attributes (e.g. jurisdiction, category).
     */
    List<TaxRate> resolveTaxRates(TenantId tenantId, String itemCode, Map<String, Object> attributes);

    static TaxProvider noOp() {
        return (tenantId, itemCode, attributes) -> List.of();
    }
}
