package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.model.entitlement.EntitlementDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementVerifierTest {

    private final EntitlementVerifier verifier = new EntitlementVerifier();
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Should grant access for boolean feature when enabled")
    void testBooleanFeatureAllowed() {
        var entitlement = CustomerEntitlement.booleanEntitlement(
            "ent_sso", TenantId.of("tenant_1"), CustomerId.of("cust_1"),
            PlanCode.of("PRO"), "SSO_SAML", true, now
        );

        EntitlementDecision decision = verifier.verify(entitlement, BigDecimal.ZERO, now);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Should evaluate quota limits on metered features")
    void testMeteredFeatureQuota() {
        // Quota of 10,000 monthly API calls, current usage 8,000
        var entitlement = CustomerEntitlement.metered(
            "ent_api", TenantId.of("tenant_1"), CustomerId.of("cust_1"),
            PlanCode.of("PRO"), "API_CALLS",
            BigDecimal.valueOf(10000), BigDecimal.valueOf(8000), true, now
        );

        // Request 1,500 calls (8000 + 1500 = 9500 <= 10000) -> allowed
        EntitlementDecision allowed = verifier.verify(entitlement, BigDecimal.valueOf(1500), now);
        assertThat(allowed.allowed()).isTrue();

        // Request 3,000 calls (8000 + 3000 = 11000 > 10000) -> denied (hard limit)
        EntitlementDecision denied = verifier.verify(entitlement, BigDecimal.valueOf(3000), now);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).contains("exceeded by");
    }

    @Test
    @DisplayName("Should deny access when entitlement is disabled")
    void testDisabledFeatureDenied() {
        var entitlement = CustomerEntitlement.booleanEntitlement(
            "ent_branding", TenantId.of("tenant_1"), CustomerId.of("cust_1"),
            PlanCode.of("PRO"), "CUSTOM_BRANDING", false, now
        );

        EntitlementDecision decision = verifier.verify(entitlement, BigDecimal.ZERO, now);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("disabled");
    }
}
