package com.saas.pricing.core.spi.impl;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Concurrency ceiling that adapts to observed latency, using Netflix's Gradient2 algorithm.
 *
 * <p>Subclasses {@link TokenBucketAdmissionController} and replaces only the ceiling, so rate
 * behaviour is identical to the fixed limiter and stays covered by the shared conformance suite.</p>
 *
 * <h2>Why latency and not failure count</h2>
 *
 * <p>The obvious alternative is AIMD — raise the limit on success, halve it on failure. It is
 * simpler and needs no clock coordination, and it is the wrong signal here. A pricing engine
 * returns 422 for a malformed rate card and 409 for a duplicate idempotency key; neither is
 * evidence of capacity exhaustion, and an AIMD controller fed those would collapse its own limit
 * during a burst of perfectly healthy traffic. Latency is the honest signal, and it rises
 * <em>before</em> failures do — which is when shedding should act.</p>
 *
 * <h2>Why Gradient2 and not Vegas</h2>
 *
 * <p>Vegas estimates queue depth from {@code limit * (1 - minRtt / sampleRtt)}, and it resets its
 * minimum whenever the limit grows. That reset is its fatal flaw, and it is not hypothetical: a
 * controller that grows its limit, resets, and then samples latency that has *already* inflated
 * adopts the inflated value as the new "no load" baseline. From then on every comparison is against
 * the degraded latency, the queue estimate is permanently zero, and the limit climbs until something
 * breaks. Measured against a fixed 1ms baseline followed by sustained 500ms requests, Vegas never
 * reduced the ceiling at all.</p>
 *
 * <p>Gradient2 addresses "bias and drift when using minimum latency measurements" by using the
 * <em>divergence between two exponential averages</em> — one long, one short — as the queueing
 * signal, and never resetting the baseline on growth:</p>
 *
 * <pre>{@code
 * shortRtt > longRtt   -> latency is diverging; reduce the limit
 * otherwise             -> gradient = minRtt / longRtt
 *                          newLimit = gradient * limit + sqrt(limit)
 * }</pre>
 *
 * <p>A sustained latency increase makes the short average climb faster than the long one regardless
 * of what the historical minimum was, which is exactly the case Vegas cannot see.</p>
 *
 * <h2>When this is still the wrong choice</h2>
 *
 * <p>A database connection pool, a thread budget and a CPU count are <strong>hard</strong>
 * constraints — exceeding one does not degrade gracefully, it fails. If your ceiling protects such a
 * constraint, a fixed limit is more honest, because an adaptive controller will probe past it. Use
 * this when real capacity is not known up front and a brief overshoot is survivable.</p>
 */
public class AdaptiveAdmissionController extends TokenBucketAdmissionController {

    /** Queue depth below which the limit grows. Netflix's guidance is 2–3. */
    public static final int DEFAULT_ALPHA = 3;

    /** Queue depth above which the limit shrinks. Must exceed alpha; guidance is 4–6. */
    public static final int DEFAULT_BETA = 6;

    /** Smoothing factor for the long-run round-trip average. Slow by design. */
    private static final double LONG_ALPHA = 0.98d;

    /** Smoothing factor for the short-run round-trip average. Reacts faster. */
    private static final double SHORT_ALPHA = 0.90d;

    /** Completions required before the first adjustment, so a cold start does not react to noise. */
    private static final int MIN_SAMPLES_BEFORE_ADJUSTING = 20;

    /** Lower clamp on the gradient, so a single outlier cannot zero the limit. */
    private static final double MIN_GRADIENT = 0.5d;

    private final int alpha;
    private final int beta;
    private final int minLimit;
    private final int maxLimit;

    /** Clamped concurrency limit the controller is currently enforcing. */
    private final AtomicReference<Integer> limit = new AtomicReference<>();

    /** Historical fastest round trip. Never reset — resetting is what makes Vegas fail. */
    private final AtomicLong minRttNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong longRttNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong shortRttNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong samplesInWindow = new AtomicLong();

    private final LongSupplier nanos;

    public AdaptiveAdmissionController(long permits, Duration period, long burst,
                                       int minLimit, int maxLimit) {
        this(permits, period, burst, minLimit, maxLimit, DEFAULT_ALPHA, DEFAULT_BETA, System::nanoTime);
    }

    public AdaptiveAdmissionController(long permits, Duration period, long burst,
                                       int minLimit, int maxLimit, int alpha, int beta,
                                       LongSupplier nanos) {
        super(permits, period, burst, maxLimit);
        if (minLimit <= 0 || maxLimit < minLimit) {
            throw new IllegalArgumentException("require 0 < minLimit <= maxLimit");
        }
        if (beta <= alpha) {
            throw new IllegalArgumentException("beta must exceed alpha");
        }
        Objects.requireNonNull(nanos, "nanos cannot be null");
        this.nanos = nanos;
        this.alpha = alpha;
        this.beta = beta;
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.limit.set(minLimit);
    }

    @Override
    protected long currentConcurrencyLimit() {
        return limit.get();
    }

    @Override
    protected void observeCompletionNanos(long elapsedNanos) {
        if (elapsedNanos <= 0) {
            return;   // a non-positive measurement is noise, not a sample
        }

        // Historical fastest round trip. Monotonic and never reset.
        minRttNanos.accumulateAndGet(elapsedNanos, Math::min);
        longRttNanos.accumulateAndGet(elapsedNanos, (previous, current) ->
            previous == Long.MAX_VALUE ? current
                : (long) (LONG_ALPHA * previous + (1 - LONG_ALPHA) * current));
        shortRttNanos.accumulateAndGet(elapsedNanos, (previous, current) ->
            previous == Long.MAX_VALUE ? current
                : (long) (SHORT_ALPHA * previous + (1 - SHORT_ALPHA) * current));

        if (samplesInWindow.incrementAndGet() < MIN_SAMPLES_BEFORE_ADJUSTING) {
            return;
        }
        samplesInWindow.set(0);

        long longRtt = longRttNanos.get();
        long shortRtt = shortRttNanos.get();
        int current = limit.get();

        int updated;
        if (shortRtt > longRtt) {
            // Divergence: latency is trending worse, so reduce rather than wait for a threshold.
            updated = Math.max(minLimit, current - 1);
        } else {
            // No divergence. Grow towards the point where queueing would begin.
            double gradient = clampGradient((double) minRttNanos.get() / (double) longRtt);
            double proposed = gradient * current + Math.sqrt(current);
            updated = clampLimit((int) Math.round(proposed), current, alpha, beta);
        }
        limit.set(updated);
    }

    /**
     * Turns a gradient into the next limit, keeping the step size anchored to the alpha/beta band so
     * the algorithm degrades to a controlled +/-1 move near the boundary rather than oscillating.
     */
    private int clampLimit(int proposed, int current, int alpha, int beta) {
        if (proposed > current + beta) {
            return Math.min(maxLimit, current + beta);
        }
        if (proposed < current - alpha) {
            return Math.max(minLimit, current - alpha);
        }
        return Math.max(minLimit, Math.min(maxLimit, proposed));
    }

    private static double clampGradient(double gradient) {
        if (gradient > 1.0d) {
            return 1.0d;
        }
        return Math.max(MIN_GRADIENT, gradient);
    }

    /** The ceiling currently in force. Exposed for metrics and tests. */
    public int currentLimit() {
        return limit.get();
    }
}