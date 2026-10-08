package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.TaxRate;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.TaxProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Enterprise rule-based tax provider.
 * Supports jurisdiction-based rate determination, product category matching, and tax exemption flags.
 */
public class RuleBasedTaxProvider implements TaxProvider {

    public record TaxRule(
        String jurisdiction,
        String itemCategoryPattern,
        String taxCode,
        BigDecimal percentage
    ) {
        public TaxRule {
            Objects.requireNonNull(jurisdiction, "jurisdiction cannot be null");
            Objects.requireNonNull(itemCategoryPattern, "itemCategoryPattern cannot be null");
            Objects.requireNonNull(taxCode, "taxCode cannot be null");
            Objects.requireNonNull(percentage, "percentage cannot be null");
        }

        public boolean matches(String requestJurisdiction, String itemCode, Map<String, Object> attrs) {
            if (!"*".equals(jurisdiction) && !jurisdiction.equalsIgnoreCase(requestJurisdiction)) {
                return false;
            }
            if ("*".equals(itemCategoryPattern)) {
                return true;
            }
            Object categoryObj = attrs.get("category");
            String itemCategory = categoryObj != null ? String.valueOf(categoryObj) : itemCode;
            return itemCategoryPattern.equalsIgnoreCase(itemCategory) || itemCategoryPattern.equalsIgnoreCase(itemCode);
        }
    }

    private final List<TaxRule> rules = new CopyOnWriteArrayList<>();

    public RuleBasedTaxProvider addRule(String jurisdiction, String itemCategoryPattern, String taxCode, BigDecimal percentage) {
        rules.add(new TaxRule(jurisdiction, itemCategoryPattern, taxCode, percentage));
        return this;
    }

    public RuleBasedTaxProvider addRule(String jurisdiction, String taxCode, double percentage) {
        return addRule(jurisdiction, "*", taxCode, BigDecimal.valueOf(percentage));
    }

    @Override
    public List<TaxRate> resolveTaxRates(TenantId tenantId, String itemCode, Map<String, Object> attributes) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(itemCode, "itemCode cannot be null");
        Objects.requireNonNull(attributes, "attributes cannot be null");

        // Check if customer is tax-exempt
        Object exemptObj = attributes.get("taxExempt");
        if (exemptObj instanceof Boolean b && b) {
            return List.of();
        }
        if (exemptObj instanceof String s && "true".equalsIgnoreCase(s)) {
            return List.of();
        }

        Object jurisdictionObj = attributes.get("jurisdiction");
        String jurisdiction = jurisdictionObj != null ? String.valueOf(jurisdictionObj) : "";

        List<TaxRate> matchedRates = new ArrayList<>();
        for (TaxRule rule : rules) {
            if (rule.matches(jurisdiction, itemCode, attributes)) {
                matchedRates.add(TaxRate.of(rule.taxCode(), rule.percentage(), rule.jurisdiction()));
            }
        }
        return matchedRates;
    }
}
