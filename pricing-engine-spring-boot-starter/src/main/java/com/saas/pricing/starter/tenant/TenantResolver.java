package com.saas.pricing.starter.tenant;

/**
 * Resolves the tenant that the current caller is acting as.
 *
 * <p><strong>Why this exists.</strong> A multi-tenant pricing API must never take the tenant from
 * the request body. If it does, any unauthenticated caller can price another tenant's usage,
 * read their entitlements, and debit their credit wallet simply by editing a JSON field.
 *
 * <p>The pricing engine cannot know how your application authenticates, so it does not guess. A
 * host application supplies this bean - typically reading {@code SecurityContextHolder} in a Spring
 * Security deployment, a validated JWT claim, or a scoped header set by an edge proxy. When
 * {@code pricing.engine.web-enabled} is true and no resolver is configured, the engine
 * <strong>fails to start</strong> rather than falling back to trusting request input.
 *
 * <p>Implementations must be side-effect free and safe to call from many threads at once.
 */
@FunctionalInterface
public interface TenantResolver {

    /**
     * Returns the tenant the current caller is authorised to act as.
     *
     * @throws TenantAccessDeniedException if the caller is unauthenticated or has no tenant
     */
    String resolveTenantId();

    /**
     * Convenience factory for hosts whose tenant is carried in an already-validated thread-scoped
     * value rather than a security context.
     */
    static TenantResolver of(java.util.function.Supplier<String> supplier) {
        return () -> {
            String tenantId = supplier.get();
            if (tenantId == null || tenantId.isBlank()) {
                throw new TenantAccessDeniedException("No tenant bound to the current request");
            }
            return tenantId;
        };
    }
}