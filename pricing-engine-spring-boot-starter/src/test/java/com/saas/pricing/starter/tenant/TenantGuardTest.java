package com.saas.pricing.starter.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant isolation contract.
 *
 * <p>Before this guard existed, the tenant was taken from the request body on every endpoint and
 * the codebase contained no authentication at all. Any caller could price another tenant's usage,
 * read their entitlements, inject telemetry into their meter, or debit their credit wallet by
 * editing one JSON field.
 */
class TenantGuardTest {

    private static TenantGuard guardFor(String authenticatedTenant) {
        return new TenantGuard(() -> authenticatedTenant);
    }

    @Test
    @DisplayName("returns the authenticated tenant when the request omits one")
    void omittedTenantIsFilledFromAuthentication() {
        assertThat(guardFor("tenant_acme").verify(null)).isEqualTo("tenant_acme");
        assertThat(guardFor("tenant_acme").verify("")).isEqualTo("tenant_acme");
        assertThat(guardFor("tenant_acme").verify("   ")).isEqualTo("tenant_acme");
    }

    @Test
    @DisplayName("accepts a matching claim, case-insensitively")
    void matchingClaimAccepted() {
        assertThat(guardFor("tenant_acme").verify("tenant_acme")).isEqualTo("tenant_acme");
        assertThat(guardFor("tenant_acme").verify("TENANT_ACME")).isEqualTo("tenant_acme");
    }

    @Test
    @DisplayName("rejects a claim for a different tenant instead of silently overriding it")
    void crossTenantClaimRejected() {
        // Silently overriding would hide a client integration bug until it billed the wrong
        // customer; failing loudly surfaces it immediately.
        assertThatThrownBy(() -> guardFor("tenant_acme").verify("tenant_victim"))
            .isInstanceOf(TenantAccessDeniedException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("rejects an unauthenticated caller rather than defaulting to a tenant")
    void unauthenticatedCallerRejected() {
        assertThatThrownBy(() -> guardFor(null).verify("tenant_acme"))
            .isInstanceOf(TenantAccessDeniedException.class);
        assertThatThrownBy(() -> guardFor("  ").verify("tenant_acme"))
            .isInstanceOf(TenantAccessDeniedException.class);
        assertThat(guardFor("tenant_acme").requireTenantId()).isEqualTo("tenant_acme");
    }

    @Test
    @DisplayName("the rejection message does not echo the requested tenant")
    void rejectionDoesNotLeakTargetTenant() {
        // Otherwise the API can be used to probe which tenants exist.
        assertThatThrownBy(() -> guardFor("tenant_acme").verify("tenant_does_not_exist"))
            .isInstanceOf(TenantAccessDeniedException.class)
            .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("tenant_does_not_exist"));
    }

    @Test
    @DisplayName("TenantResolver.of rejects a blank supplier result")
    void supplierFactoryRejectsBlank() {
        assertThatThrownBy(() -> TenantResolver.of(() -> " ").resolveTenantId())
            .isInstanceOf(TenantAccessDeniedException.class);
        assertThat(TenantResolver.of(() -> "tenant_x").resolveTenantId()).isEqualTo("tenant_x");
    }
}