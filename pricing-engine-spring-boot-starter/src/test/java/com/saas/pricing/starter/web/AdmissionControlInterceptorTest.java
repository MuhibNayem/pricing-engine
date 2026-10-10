package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Admission;
import com.saas.pricing.core.spi.AdmissionController.Shedding;
import com.saas.pricing.core.spi.LoadShedException;
import com.saas.pricing.starter.tenant.TenantResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.context.request.ServletWebRequest;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Pins where the admission lease lives for the whole request lifecycle.
 *
 * <p>The lease used to sit in a {@link ThreadLocal}, which is the one place that looks right until
 * two requests meet. A thread is not a request: the second request to run on a pooled thread
 * overwrites the first one's lease, and the release that eventually happens closes the wrong one. The
 * counters still balance, nothing throws, and the concurrency ceiling degrades a little on every
 * such collision — silently, and permanently.</p>
 *
 * <p>These tests drive the interceptor directly, in the exact sequence Spring produces, rather than
 * through a MockMvc context. That is deliberate: the interesting failures are about <em>which</em>
 * interceptor callback runs when, and standing up a full async MVC context to observe that would be
 * testing Spring rather than this class.</p>
 */
@DisplayName("AdmissionControlInterceptor holds the lease on the request")
class AdmissionControlInterceptorTest {

    /** Hands out leases and counts how many are open, so a leak is a number rather than a hunch. */
    private static final class CountingController implements AdmissionController {

        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger issued = new AtomicInteger();

        @Override
        public Admission admit(TenantId tenantId) {
            issued.incrementAndGet();
            open.incrementAndGet();
            return Admission.granted(open::decrementAndGet);
        }
    }

    private static HandlerMethod handlerMethod() throws Exception {
        Method method = Endpoint.class.getDeclaredMethod("price");
        return new HandlerMethod(new Endpoint(), method);
    }

    private static final class Endpoint {
        @SuppressWarnings("unused")
        void price() {
        }
    }

    private final CountingController controller = new CountingController();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private AdmissionControlInterceptor interceptor(String tenantId) {
        TenantResolver resolver = () -> tenantId;
        return new AdmissionControlInterceptor(controller, resolver);
    }

    @Test
    @DisplayName("a normal request takes one lease and gives it back")
    void leaseIsReleasedOnCompletion() throws Exception {
        AdmissionControlInterceptor interceptor = interceptor("acme");
        MockHttpServletRequest request = new MockHttpServletRequest();
        HandlerMethod handler = handlerMethod();

        interceptor.preHandle(request, response, handler);
        assertThat(controller.open.get()).as("the slot is held for the duration of the call").isEqualTo(1);

        interceptor.afterCompletion(request, response, handler, null);
        assertThat(controller.open.get()).isZero();
    }

    /**
     * The regression. Two requests on one thread — exactly what a container does with a pooled
     * thread — must each release their own lease.
     */
    @Test
    @DisplayName("two requests on one thread each release their own lease")
    void requestsOnOneThreadDoNotShareALease() throws Exception {
        AdmissionControlInterceptor interceptor = interceptor("acme");
        HandlerMethod handler = handlerMethod();
        MockHttpServletRequest first = new MockHttpServletRequest();
        MockHttpServletRequest second = new MockHttpServletRequest();

        interceptor.preHandle(first, response, handler);
        interceptor.preHandle(second, response, handler);
        assertThat(controller.open.get()).as("both requests are in flight").isEqualTo(2);

        // Deliberately out of order: the container is under no obligation to complete the first
        // request before the second starts on the same thread.
        interceptor.afterCompletion(first, response, handler, null);
        assertThat(controller.open.get()).as("the second request is still in flight").isEqualTo(1);

        interceptor.afterCompletion(second, response, handler, null);
        assertThat(controller.open.get())
            .as("both leases released; a thread-local release would close the wrong lease and "
                + "strand the first one for the life of the process")
            .isZero();
    }

    /**
     * Spring re-runs the whole chain for the dispatch that follows async processing. A second
     * admission there would take a slot that nothing releases.
     */
    @Test
    @DisplayName("the async re-dispatch reuses the lease instead of taking a second one")
    void asyncRedispatchDoesNotTakeASecondLease() throws Exception {
        AdmissionControlInterceptor interceptor = interceptor("acme");
        MockHttpServletRequest request = new MockHttpServletRequest();
        HandlerMethod handler = handlerMethod();

        interceptor.preHandle(request, response, handler);
        interceptor.afterConcurrentHandlingStarted(request, response, handler);
        assertThat(controller.open.get())
            .as("the async work is exactly what the ceiling protects; handing the slot back here "
                + "would stop counting work that is still running")
            .isEqualTo(1);

        interceptor.preHandle(request, response, handler);   // the async dispatch
        assertThat(controller.issued.get())
            .as("the re-dispatch must not consume a second concurrency slot")
            .isEqualTo(1);

        interceptor.afterCompletion(request, response, handler, null);
        assertThat(controller.open.get()).isZero();
    }

    /**
     * An async request that times out or fails with a network error is never dispatched, so
     * {@code afterCompletion} never runs. Spring's documentation routes exactly that case to the
     * {@code WebAsyncManager} callbacks registered here.
     */
    @Test
    @DisplayName("an async timeout or error releases the lease even though nothing is dispatched")
    void asyncTimeoutReleasesTheLeaseWithoutADispatch() throws Exception {
        AdmissionControlInterceptor interceptor = interceptor("acme");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        HandlerMethod handler = handlerMethod();

        interceptor.preHandle(request, servletResponse, handler);

        WebAsyncManager manager = mock(WebAsyncManager.class);
        request.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE, manager);
        interceptor.afterConcurrentHandlingStarted(request, servletResponse, handler);

        ArgumentCaptor<CallableProcessingInterceptor> callableHook =
            ArgumentCaptor.forClass(CallableProcessingInterceptor.class);
        ArgumentCaptor<DeferredResultProcessingInterceptor> deferredHook =
            ArgumentCaptor.forClass(DeferredResultProcessingInterceptor.class);
        verify(manager).registerCallableInterceptors(callableHook.capture());
        verify(manager).registerDeferredResultInterceptors(deferredHook.capture());

        ServletWebRequest webRequest = new ServletWebRequest(request, servletResponse);
        Callable<Object> task = () -> null;

        callableHook.getValue().handleTimeout(webRequest, task);
        assertThat(controller.open.get())
            .as("an async timeout produces no dispatch, so this hook is the only thing that can fire")
            .isZero();

        // The DeferredResult flavour of the same three exits, for handlers that use one.
        interceptor.preHandle(request, servletResponse, handler);
        interceptor.afterConcurrentHandlingStarted(request, servletResponse, handler);
        deferredHook.getValue().handleTimeout(webRequest, new DeferredResult<>());
        assertThat(controller.open.get()).isZero();

        interceptor.preHandle(request, servletResponse, handler);
        interceptor.afterConcurrentHandlingStarted(request, servletResponse, handler);
        RuntimeException failure = new RuntimeException("connection reset");
        assertThatThrownBy(() -> callableHook.getValue().handleError(webRequest, task, failure))
            .as("the hook must not swallow the failure it was handed")
            .isSameAs(failure);
        assertThat(controller.open.get()).isZero();
    }

    @Test
    @DisplayName("a shed request takes no lease at all")
    void shedTakesNoLease() throws Exception {
        AdmissionController refusing = tenantId ->
            Admission.shed(Shedding.RATE_LIMITED, Duration.ofSeconds(1));
        AdmissionControlInterceptor interceptor = new AdmissionControlInterceptor(refusing, () -> "acme");

        MockHttpServletRequest request = new MockHttpServletRequest();
        HandlerMethod handler = handlerMethod();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
            .isInstanceOf(LoadShedException.class);
        assertThat(controller.open.get()).as("nothing was admitted, so nothing was taken").isZero();

        interceptor.afterCompletion(request, response, handler, null);
        assertThat(controller.open.get()).isZero();
    }

    @Test
    @DisplayName("requests the engine does not serve take no lease")
    void unrelatedRequestsTakeNoLease() throws Exception {
        AdmissionControlInterceptor interceptor = interceptor("acme");
        HandlerMethod handler = handlerMethod();

        // Not a HandlerMethod: a static resource or an unmapped path.
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, new Object())).isTrue();

        // A blank tenant is not ours to reject - TenantGuard owns that, with a 403.
        AdmissionControlInterceptor blank = interceptor("  ");
        assertThat(blank.preHandle(new MockHttpServletRequest(), response, handler)).isTrue();

        assertThat(controller.issued.get()).isZero();
    }
}