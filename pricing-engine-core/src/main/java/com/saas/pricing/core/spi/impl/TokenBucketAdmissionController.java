package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Lock-free token-bucket rate limiting plus an in-flight concurrency ceiling.
 *
 * <p>Not final: {@link AdaptiveAdmissionController} subclasses this to replace the fixed ceiling
 * with a Vegas-adapted one, reusing the bucket rather than duplicating it.</p>
 *
 * <p>Token bucket rather than a fixed window because a fixed window admits twice the intended rate
 * across a window boundary — a burst exactly at the edge passes unthrottled — and because bucket
 * state is O(1) per tenant regardless of request rate. Bucket4j describes token bucket as "the
 * de-facto standard for rate-limiting in the IT industry".</p>
 *
 * <p>Concurrency is a separate global ceiling rather than per-tenant: it models what this node can
 * actually execute, so it must not be multiplied by tenant count. Admission never blocks. Waiting
 * for a slot would queue work that the caller has already decided is urgent, converting a shed into
 * the latency spike the shed was meant to avoid.</p>
 *
 * <p>Rate is evaluated before concurrency so a throttled tenant never occupies a slot. The
 * concurrency slot is held for the duration of the work and returned when the lease closes.</p>
 *
 * <p>Starts no threads and holds no timers: the bucket refills arithmetically against an injected
 * time source, so there is nothing to schedule and nothing to shut down.</p>
 */
public class TokenBucketAdmissionController implements AdmissionController {

    /** Default ceiling on distinct tenants tracked before idle buckets are reclaimed. */
    public static final int DEFAULT_MAX_TRACKED_TENANTS = 10_000;

    /**
     * Default floor advertised to a client shed by the concurrency ceiling.
     *
     * <p>Not a prediction of when a slot frees — that is unknowable from here. It exists so a
     * shed population does not retry in lockstep and immediately re-saturate. Callers should add
     * jitter on top.</p>
     */
    public static final Duration DEFAULT_OVERLOAD_RETRY_AFTER = Duration.ofMillis(50);

    /** CAS attempts before treating sustained contention as load. */
    private static final int MAX_CAS_ATTEMPTS = 64;

    /** Mutable bucket state: remaining tokens and when that figure was last true. */
    private record Bucket(double tokens, long observedNanos) {
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    private final LongSupplier nanos;
    private final double burstCapacity;
    private final double permitsPerNano;
    private final long maxConcurrency;
    private final int maxTrackedTenants;
    private final Duration overloadRetryAfter;

    public TokenBucketAdmissionController(long permits, Duration period, long burst, int maxConcurrency) {
        this(permits, period, burst, maxConcurrency, DEFAULT_MAX_TRACKED_TENANTS,
            DEFAULT_OVERLOAD_RETRY_AFTER, System::nanoTime);
    }

    public TokenBucketAdmissionController(long permits, Duration period, long burst, int maxConcurrency,
                                          int maxTrackedTenants, Duration overloadRetryAfter,
                                          LongSupplier nanos) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        if (burst <= 0) {
            throw new IllegalArgumentException("burst must be positive");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        if (maxTrackedTenants <= 0) {
            throw new IllegalArgumentException("maxTrackedTenants must be positive");
        }
        Objects.requireNonNull(overloadRetryAfter, "overloadRetryAfter cannot be null");
        Objects.requireNonNull(nanos, "nanos cannot be null");

        this.burstCapacity = burst;
        this.permitsPerNano = permits / (double) period.toNanos();
        this.maxConcurrency = maxConcurrency;
        this.maxTrackedTenants = maxTrackedTenants;
        this.overloadRetryAfter = overloadRetryAfter;
        this.nanos = nanos;
    }

    @Override
    public Admission admit(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        long now = nanos.getAsLong();

        Admission rate = consumeToken(tenantId.value(), now);
        if (rate != null) {
            return rate;
        }

        // Rate passed. Take a concurrency slot, or give the token back and shed.
        return grantLease(tenantId.value(), now);
    }

    /**
     * Takes an in-flight slot and returns the lease that releases it, recording how long the work
     * took so a subclass can adapt.
     *
     * <p>Separated from {@link #admit} so {@link AdaptiveAdmissionController} can reuse the entire
     * rate path and override only this decision, instead of reimplementing — and re-bugging — the
     * token bucket.</p>
     */
    protected Admission grantLease(String tenant, long startNanos) {
        if (inFlight.incrementAndGet() > currentConcurrencyLimit()) {
            inFlight.decrementAndGet();
            refundToken(tenant, startNanos);
            return Admission.shed(Shedding.OVERLOADED, overloadRetryAfter);
        }
        return Admission.granted(() -> {
            observeCompletionNanos(nanos.getAsLong() - startNanos);
            inFlight.decrementAndGet();
        });
    }

    /** The concurrency ceiling in force right now: fixed here, adaptive in the subclass. */
    protected long currentConcurrencyLimit() {
        return maxConcurrency;
    }

    /** How long an admitted unit of work took. A no-op here; the adaptive subclass consumes it. */
    protected void observeCompletionNanos(long elapsedNanos) {
        // Intentionally empty: a fixed ceiling has nothing to learn from a duration.
    }

    /**
     * Consumes one token for {@code tenant}.
     *
     * @return {@code null} when a token was taken, otherwise the shed admission
     */
    private Admission consumeToken(String tenant, long now) {
        Bucket bucket = buckets.computeIfAbsent(tenant, k -> new Bucket(burstCapacity, now));

        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            double refilled = refill(bucket, now);

            if (refilled < 1.0d) {
                double waitNanos = Math.ceil((1.0d - refilled) / permitsPerNano);
                // Bank the refill so the next attempt does not recompute from the same stale point.
                buckets.replace(tenant, bucket, new Bucket(refilled, now));
                return Admission.shed(Shedding.RATE_LIMITED,
                    Duration.ofNanos(Math.max(1L, (long) waitNanos)));
            }

            if (buckets.replace(tenant, bucket, new Bucket(refilled - 1.0d, now))) {
                reclaimIdleBuckets(now);
                return null;
            }
            bucket = buckets.computeIfAbsent(tenant, k -> new Bucket(burstCapacity, now));
        }

        // Sustained CAS contention is itself a load signal, and no token was consumed.
        return Admission.shed(Shedding.OVERLOADED, overloadRetryAfter);
    }

    /** Returns a token taken by {@link #consumeToken} when the concurrency ceiling rejected the work. */
    private void refundToken(String tenant, long now) {
        buckets.computeIfPresent(tenant, (k, bucket) -> {
            double restored = Math.min(burstCapacity, refill(bucket, now) + 1.0d);
            return new Bucket(restored, now);
        });
    }

    private double refill(Bucket bucket, long now) {
        long elapsed = Math.max(0L, now - bucket.observedNanos());
        return Math.min(burstCapacity, bucket.tokens() + elapsed * permitsPerNano);
    }

    /**
     * Drops buckets for tenants that have refilled to full and are holding no state worth keeping.
     *
     * <p>Only full buckets are eligible: a partially drained bucket is an actively throttled tenant
     * and discarding it would hand back its whole burst. Without this, a deployment that has seen
     * many tenants keeps one small object per tenant for the process lifetime.</p>
     */
    private void reclaimIdleBuckets(long now) {
        if (buckets.size() <= maxTrackedTenants) {
            return;
        }
        buckets.entrySet().removeIf(entry -> refill(entry.getValue(), now) >= burstCapacity);
    }

    /** Currently in-flight admitted work. Exposed for metrics and tests. */
    public int inFlight() {
        return inFlight.get();
    }

    /** Distinct tenants with live bucket state. Exposed for metrics and tests. */
    public int trackedTenants() {
        return buckets.size();
    }

    /** Returns the configured concurrency ceiling. */
    public long maxConcurrency() {
        return maxConcurrency;
    }
}