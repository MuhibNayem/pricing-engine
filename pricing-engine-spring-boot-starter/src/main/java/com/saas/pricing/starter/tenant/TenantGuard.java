package com.saas.pricing.starter.tenant;

import java.util.Objects;

/**
 * Enforces that a request's tenant matches the tenant the caller is authenticated as.
 *
 * <p>Two properties matter here, and both are needed:
 *
 * <ol>
 *   <li><strong>The effective tenant always comes from the resolver</strong>, never from the
 *       request body, so a caller cannot select another tenant by editing JSON.</li>
 *   <li><strong>A claimed tenant that disagrees is rejected</strong> rather than ignored. Silently
 *       overriding it would let a bug in a client go unnoticed until it started billing the wrong
 *       customer; failing loudly surfaces the integration mistake immediately.</li>
 * </ol>
 */
public class TenantGuard {

    private final TenantResolver tenantResolver;

    public TenantGuard(TenantResolver tenantResolver) {
        this.tenantResolver = Objects.requireNonNull(tenantResolver, "tenantResolver cannot be null");
    }

    /**
     * Returns the authoritative tenant id for the current caller.
     *
     * @throws TenantAccessDeniedException if the caller has no resolvable tenant
     */
    public String requireTenantId() {
        String tenantId = tenantResolver.resolveTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantAccessDeniedException("No tenant bound to the current request");
        }
        return tenantId;
    }

    /**
     * Verifies that {@code claimedTenantId}, if present, matches the authenticated tenant.
     *
     * @param claimedTenantId the tenant supplied in the request body, or null when absent
     * @return the authenticated tenant id
     * @throws TenantAccessDeniedException if the caller is unauthenticated, or the claimed tenant
     *                                    does not match the authenticated tenant
     */
    public String verify(String claimedTenantId) {
        String authenticated = requireTenantId();
        if (claimedTenantId != null && !claimedTenantId.isBlank()
                && !claimedTenantId.equalsIgnoreCase(authenticated)) {
            throw new TenantAccessDeniedException(
                    "Request tenant does not match the authenticated tenant");
        }
        return authenticated;
    }
}