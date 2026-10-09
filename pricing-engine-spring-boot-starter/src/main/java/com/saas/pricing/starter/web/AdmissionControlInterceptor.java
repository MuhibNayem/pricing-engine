package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Lease;
import com.saas.pricing.core.spi.LoadShedException;
import com.saas.pricing.starter.tenant.TenantResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Objects;

/**
 * Applies admission control to the pricing endpoints, holding a concurrency slot for the duration
 * of the request.
 *
 * <p>Registering here rather than inside the engine means the slot covers the whole call — parse,
 * tenant verification, evaluation, persistence — which is what actually consumes a connection from
 * the pool the ceiling is meant to protect.</p>
 *
 * <p>Shedding is done by throwing {@link LoadShedException} from {@code preHandle}, which
 * {@link PricingEngineExceptionHandler} maps to 429 or 503 with a {@code Retry-After}. Returning
 * {@code false} would produce a bare 200 with an empty body, which tells the caller nothing about
 * whether to retry or why.</p>
 *
 * <p>The lease is held in a {@link ThreadLocal} and released in {@code afterCompletion}, which
 * Spring runs exactly once per successfully handled request. A request that never reaches
 * {@code preHandle}, or whose handler throws before completion, still passes through
 * {@code afterCompletion}; the one path that does not — {@code preHandle} itself throwing — is the
 * shed path, where no lease was ever taken.</p>
 */
public class AdmissionControlInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final AdmissionController admissionController;
    private final TenantResolver tenantResolver;

    private static final ThreadLocal<Lease> HELD = new ThreadLocal<>();

    public AdmissionControlInterceptor(AdmissionController admissionController, TenantResolver tenantResolver) {
        this.admissionController = Objects.requireNonNull(admissionController, "admissionController cannot be null");
        this.tenantResolver = Objects.requireNonNull(tenantResolver, "tenantResolver cannot be null");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;   // static resources and unmapped handlers are not engine work
        }

        String tenantId = tenantResolver.resolveTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // Not ours to reject: TenantGuard produces the authorisation failure with a proper 403.
            return true;
        }

        AdmissionController.Admission admission = admissionController.admit(new TenantId(tenantId));
        if (!admission.admitted()) {
            throw LoadShedException.from(admission);
        }
        HELD.set(admission.lease());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Lease lease = HELD.get();
        HELD.remove();       // always clear, even if close() throws
        if (lease != null) {
            lease.close();
        }
    }
}