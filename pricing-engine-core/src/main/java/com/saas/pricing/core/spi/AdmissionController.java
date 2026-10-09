package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.TenantId;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Admission control for engine work: sheds load before the engine is overwhelmed.
 *
 * <p>Two independent controls, deliberately not conflated:</p>
 * <ul>
 *   <li><strong>Rate</strong> — a token bucket per tenant bounds sustained throughput and permits a
 *       bounded burst. Answers "is this tenant asking for too much?"</li>
 *   <li><strong>Concurrency</strong> — an in-flight cap bounds simultaneous work regardless of
 *       arrival rate. Answers "is this node able to take more right now?"</li>
 * </ul>
 *
 * <p>The distinction matters because they shed for different reasons and mean different things to a
 * client: {@link Shedding#RATE_LIMITED} is that tenant's quota and the process is healthy;
 * {@link Shedding#OVERLOADED} is that the engine itself is at capacity and no tenant's quota is at
 * fault. A gateway maps the first to {@code 429} and the second to {@code 503} — never the reverse,
 * because telling every caller the server is down when one tenant is over its quota is a false
 * outage signal.</p>
 *
 * <p>This is the engine's own capacity, not HTTP edge protection. Per-IP or per-API-key limiting
 * belongs to the gateway in front of the engine; a library cannot see those. What the engine does
 * know is whether it can take more work, and refusing cleanly is materially better than degrading
 * inside the pricing path where a timeout or a partial charge is the alternative.</p>
 *
 * <p>Implementations must be thread-safe. The default implementation starts no threads and holds no
 * timers; it computes against an injected time source.</p>
 */
public interface AdmissionController {

    /**
     * Admits everything and holds nothing.
     *
     * <p>The default when a host configures no shedding, so an unconfigured deployment pays no
     * allocation and no CAS on the hot path.</p>
     */
    AdmissionController UNBOUNDED = tenantId -> Admission.granted(() -> {
    });

    /**
     * Attempts to admit one unit of work for {@code tenantId}.
     *
     * <p>On admission the returned lease holds a concurrency slot and <strong>must be closed</strong>,
     * ideally in a try-with-resources, or the slot leaks until the cap is reached:</p>
     *
     * <pre>{@code
     * Admission admission = admissionController.admit(tenantId);
     * if (!admission.admitted()) {
     *     throw new LoadShedException(admission.reason(), admission.retryAfter());
     * }
     * try (Lease ignored = admission.lease()) {
     *     return engine.evaluate(request);
     * }
     * }</pre>
     *
     * <p>Closing a lease more than once must be harmless.</p>
     */
    Admission admit(TenantId tenantId);

    /** A granted admission and its held concurrency slot. */
    final class Lease implements AutoCloseable {

        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Runnable release) {
            this.release = release;
        }

        /**
         * Releases the slot. Idempotent: a second close is a no-op rather than a second release,
         * which would otherwise hand out capacity nobody is holding.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }

    /** Why work was shed. Never {@code null} on a shed admission. */
    enum Shedding {
        /** This tenant exceeded its sustained rate. The engine is otherwise healthy. */
        RATE_LIMITED,
        /** The engine is at its concurrency ceiling. No tenant's quota is at fault. */
        OVERLOADED
    }

    /**
     * The outcome of one admission attempt.
     *
     * <p>{@code lease} is non-null exactly when {@link #admitted()}. {@code retryAfter} is the
     * caller's floor for how long to wait — never retry sooner, and add jitter so that a throttled
     * population does not resynchronise.</p>
     */
    record Admission(Lease lease, Shedding reason, Duration retryAfter) {

        public Admission {
            if ((lease == null) == (reason == null)) {
                throw new IllegalArgumentException(
                    "exactly one of lease or reason must be set: lease=" + lease + ", reason=" + reason);
            }
            if (lease == null && (retryAfter == null || retryAfter.isNegative())) {
                throw new IllegalArgumentException("a shed admission needs a non-negative retryAfter");
            }
        }

        public static Admission granted(Runnable release) {
            return new Admission(new Lease(release), null, null);
        }

        public static Admission shed(Shedding reason, Duration retryAfter) {
            return new Admission(null, reason, retryAfter);
        }

        public boolean admitted() {
            return lease != null;
        }

        /** HTTP 429 - this client's quota. */
        public boolean rateLimited() {
            return reason == Shedding.RATE_LIMITED;
        }

        /** HTTP 503 - the server is at capacity, for everyone. */
        public boolean overloaded() {
            return reason == Shedding.OVERLOADED;
        }
    }
}