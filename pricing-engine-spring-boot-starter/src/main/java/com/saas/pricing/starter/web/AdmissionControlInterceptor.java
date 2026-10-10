package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Lease;
import com.saas.pricing.core.spi.LoadShedException;
import com.saas.pricing.starter.tenant.TenantResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Objects;
import java.util.concurrent.Callable;

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
 * <h2>The lease lives on the request, never on the thread</h2>
 *
 * <p>A {@link ThreadLocal} is the obvious place to park the lease and the wrong one. A thread is not
 * a request: two requests share a pool thread, and a slot released by one request then hands
 * capacity to whichever request the scheduler ran next. Nothing errors, the counters still balance,
 * and the ceiling quietly stops meaning what it says — which is invisible until the day the
 * concurrency limit is the only thing between a traffic spike and an outage.</p>
 *
 * <p>The request is the unit of work, so the lease is a request attribute: reachable from whichever
 * thread is currently running that request, which is exactly the set of threads allowed to close
 * it.</p>
 *
 * <h2>Async, where the obvious design strands slots</h2>
 *
 * <p>When a handler starts async processing Spring does not call {@code afterCompletion}. It calls
 * {@link AsyncHandlerInterceptor#afterConcurrentHandlingStarted}, then — once the async work
 * finishes — dispatches back through the chain, running {@code preHandle} a <em>second</em> time.
 * Two consequences, both handled here:</p>
 * <ul>
 *   <li>{@code preHandle} takes a lease only when the request does not already carry one, so the
 *       re-dispatch reuses the original instead of taking a second slot that nothing would ever
 *       release.</li>
 *   <li>An async request that times out or fails with a network error is never dispatched, so
 *       {@code afterCompletion} never runs. Spring's documentation points at {@code WebAsyncManager}
 *       callback interceptors for precisely that case, and this registers one to close the lease.
 *       Without it, a handful of timeouts would permanently erode the concurrency ceiling — the one
 *       resource this class exists to protect, and one that degrades without ever logging.</li>
 * </ul>
 *
 * <p>{@link Lease#close()} is idempotent, so all three paths may close the same lease without
 * coordination and without risk of releasing a slot twice.</p>
 */
public class AdmissionControlInterceptor implements AsyncHandlerInterceptor, WebMvcConfigurer {

    private final AdmissionController admissionController;
    private final TenantResolver tenantResolver;

    private static final String LEASE_ATTRIBUTE = AdmissionControlInterceptor.class.getName() + ".lease";

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

        // Spring re-runs preHandle for the dispatch that follows async processing. The first lease
        // is still held, so taking another would strand a slot for the lifetime of the process.
        if (request.getAttribute(LEASE_ATTRIBUTE) != null) {
            return true;
        }

        AdmissionController.Admission admission = admissionController.admit(new TenantId(tenantId));
        if (!admission.admitted()) {
            throw LoadShedException.from(admission);
        }
        request.setAttribute(LEASE_ATTRIBUTE, admission.lease());
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        // Deliberately does NOT release. The lease belongs to the request, not to the thread about to
        // be handed back, and the async work is exactly the work the ceiling exists to cover.
        Lease lease = leaseOf(request);
        if (lease == null) {
            return;   // nothing was admitted: unmapped handler, blank tenant, or an earlier shed
        }

        // Read the attribute rather than WebAsyncUtils.getAsyncManager, which would create a manager
        // on a request that never went async and register a completion hook nothing can ever fire.
        Object existing = request.getAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE);
        if (!(existing instanceof WebAsyncManager asyncManager)) {
            return;   // no manager: afterCompletion remains the only hook, and it will still run
        }

        asyncManager.registerCallableInterceptors(callableRelease(lease));
        asyncManager.registerDeferredResultInterceptors(deferredResultRelease(lease));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Lease lease = leaseOf(request);
        if (lease == null) {
            return;   // never admitted, or already released
        }
        request.removeAttribute(LEASE_ATTRIBUTE);   // removed first: always clear, even if close() throws
        lease.close();
    }

    private static Lease leaseOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(LEASE_ATTRIBUTE);
        return attribute instanceof Lease lease ? lease : null;
    }

    /** Releases on every {@code Callable} outcome that will not produce a further dispatch. */
    private static CallableProcessingInterceptor callableRelease(Lease lease) {
        return new CallableProcessingInterceptor() {

            @Override
            public <T> void afterCompletion(NativeWebRequest request, Callable<T> task) {
                lease.close();
            }

            @Override
            public <T> Object handleTimeout(NativeWebRequest request, Callable<T> task) {
                lease.close();
                return null;   // null: keep Spring's default timeout handling, do not answer for it
            }

            @Override
            public <T> Object handleError(NativeWebRequest request, Callable<T> task, Throwable t)
                throws Exception {
                lease.close();
                // Rethrown rather than swallowed, so the host's error handling is unchanged by a
                // concern that is only about capacity. A raw Error cannot be rethrown through this
                // signature, and letting one escape a capacity hook would be worse still.
                if (t instanceof Exception e) {
                    throw e;
                }
                throw new IllegalStateException(t);
            }
        };
    }

    /** The same three release points for handlers that return a {@code DeferredResult}. */
    private static DeferredResultProcessingInterceptor deferredResultRelease(Lease lease) {
        return new DeferredResultProcessingInterceptor() {

            @Override
            public <T> void afterCompletion(NativeWebRequest request, DeferredResult<T> result) {
                lease.close();
            }

            @Override
            public <T> boolean handleTimeout(NativeWebRequest request, DeferredResult<T> result) {
                lease.close();
                return false;  // false: keep Spring's default timeout handling
            }

            @Override
            public <T> boolean handleError(NativeWebRequest request, DeferredResult<T> result, Throwable t) {
                lease.close();
                return false;  // false: keep Spring's default error handling
            }
        };
    }
}