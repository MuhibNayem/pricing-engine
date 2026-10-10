package com.saas.pricing.redis;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Admission;
import com.saas.pricing.core.spi.AdmissionController.Shedding;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Cluster-wide admission control: a shared per-tenant quota, a local in-flight ceiling.
 *
 * <h2>Which limit is shared, and why only that one</h2>
 *
 * <p>{@link TokenBucketAdmissionController} is per-process, so behind N load balancers the effective
 * per-tenant rate is N times the configured one. The quota is what must be shared: it is a statement
 * about the tenant's entitlement, and a tenant cannot hold N of them.</p>
 *
 * <p>The concurrency ceiling deliberately stays <strong>local</strong>. It models what this node can
 * actually execute — cores, a connection pool, a thread budget — none of which is global. Sharing it
 * would be wrong in both directions: a remote node cannot do this node's work, and a shared ceiling
 * would let one node's load silently consume another's headroom.</p>
 *
 * <h2>Continuous refill, matching the in-memory implementation</h2>
 *
 * <p>Redis's own Java token-bucket guide refills on discrete intervals
 * ({@code math.floor(elapsed / interval)}). That would make the distributed limiter and the
 * in-memory one behave differently at the margin — 1,000/s becomes 10 bursts of 100 — which is
 * exactly the drift {@code AdmissionControllerConformance} exists to catch. This refills
 * continuously, so both implementations admit the same requests for the same instants.</p>
 *
 * <h2>Clock domain</h2>
 *
 * <p>Refill arithmetic uses the caller's clock, matching the in-memory controller so both agree
 * under the same conditions. Backwards skew between nodes is clamped to zero rather than allowed to
 * mint tokens. A deployment that needs one clock source for all nodes should use Redis
 * {@code TIME} — but that trades away the ability to reason about, and test, a known instant.</p>
 *
 * <p><strong>Fail-closed.</strong> If Redis is unreachable this throws. Failing open would mean a
 * tenant whose quota is exhausted is silently served whenever Redis is down, which is precisely when
 * a rate limit matters most.</p>
 */
public class RedisAdmissionController implements AdmissionController {

    private static final String KEY_PREFIX = "aequitas:quota:";

    /**
     * Refill-and-consume, atomic on the server.
     *
     * <p>{@code KEYS[1]} bucket key. {@code ARGV[1]} capacity, {@code ARGV[2]} refill <em>per
     * millisecond</em>, {@code ARGV[3]} now in epoch millis, {@code ARGV[4]} key ttl millis.</p>
     *
     * <p>Returns {@code {allowed, waitMillis}}.</p>
     */
    private static final String CONSUME_LUA = """
        local raw = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
        local tokens = tonumber(raw[1])
        local updated = tonumber(raw[2])
        local capacity = tonumber(ARGV[1])
        local permitsPerMilli = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])

        if (not tokens) then
          tokens = capacity
          updated = now
        end

        -- Clamped: a node whose clock has moved backwards must not mint tokens by subtraction.
        local elapsed = now - updated
        if elapsed < 0 then elapsed = 0 end

        local refilled = math.min(capacity, tokens + (elapsed * permitsPerMilli))

        if refilled < 1 then
          redis.call('HSET', KEYS[1], 'tokens', tostring(refilled), 'updated', tostring(now))
          redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
          local wait = math.ceil((1 - refilled) / permitsPerMilli)
          if wait < 1 then wait = 1 end
          -- Quoted deliberately. A bare Lua number becomes an integer reply, which Lettuce decodes
          -- as Long rather than String, so the client's "0" comparison would silently miss the
          -- denial and treat an exhausted bucket as a grant.
          return {'0', tostring(wait)}
        end

        redis.call('HSET', KEYS[1], 'tokens', tostring(refilled - 1), 'updated', tostring(now))
        redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
        return {'1', '0'}
        """;

    private final RedisCommands<String, String> commands;
    private final LongSupplier nanos;
    private final AtomicInteger inFlight = new AtomicInteger();

    private final double burstCapacity;
    private final double permitsPerMilli;
    private final long maxConcurrency;
    private final Duration overloadRetryAfter;
    private final long bucketTtlMillis;

    public RedisAdmissionController(StatefulRedisConnection<String, String> connection,
                                    long permits, Duration period, long burst, int maxConcurrency) {
        this(connection.sync(), permits, period, burst, maxConcurrency,
            Duration.ofMillis(50), System::nanoTime);
    }

    public RedisAdmissionController(RedisCommands<String, String> commands,
                                    long permits, Duration period, long burst, int maxConcurrency,
                                    Duration overloadRetryAfter, LongSupplier nanos) {
        if (permits <= 0 || burst <= 0 || maxConcurrency <= 0) {
            throw new IllegalArgumentException("permits, burst and maxConcurrency must be positive");
        }
        Objects.requireNonNull(period, "period cannot be null");
        Objects.requireNonNull(overloadRetryAfter, "overloadRetryAfter cannot be null");
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
        this.nanos = Objects.requireNonNull(nanos, "nanos cannot be null");

        this.burstCapacity = burst;
        // Milliseconds throughout, because that is the unit the script's clock runs in. Mixing a
        // per-nanosecond rate with a millisecond elapsed time refills a millionfold too slowly,
        // which looks exactly like "the limiter never lets anything through after a pause".
        this.permitsPerMilli = permits / (double) period.toMillis();
        this.maxConcurrency = maxConcurrency;
        this.overloadRetryAfter = overloadRetryAfter;
        // A bucket idle for a whole period at burst capacity is indistinguishable from a new one.
        this.bucketTtlMillis = Math.max(1_000L, period.toMillis());
    }

    private static String bucketKey(TenantId tenantId) {
        return KEY_PREFIX + "{" + tenantId.value() + "}";
    }

    @Override
    public Admission admit(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        long nowMillis = nanos.getAsLong() / 1_000_000L;

        long waitMillis;
        try {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) commands.eval(CONSUME_LUA, ScriptOutputType.MULTI,
                new String[]{bucketKey(tenantId)},
                Long.toString((long) burstCapacity),
                Double.toString(permitsPerMilli),
                Long.toString(nowMillis),
                Long.toString(bucketTtlMillis));
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("Redis returned an unrecognised rate-limit response: " + result);
            }
            if ("0".equals(result.get(0))) {
                return Admission.shed(Shedding.RATE_LIMITED,
                    Duration.ofMillis(Math.max(1L, Long.parseLong(result.get(1)))));
            }
        } catch (RedisException e) {
            // Fail closed. Serving a tenant whose quota is exhausted is exactly the wrong thing to
            // do at the moment the limiter is unavailable.
            throw new IdempotencyKeyStoreUnavailableException(
                "Redis is unreachable; refusing to shed open, which would serve a quota-exceeded tenant", e);
        }

        // Quota granted. The concurrency ceiling stays local to this node; Admission.granted()
        // already wraps the release so a double close cannot hand out capacity past the ceiling.
        if (inFlight.incrementAndGet() > maxConcurrency) {
            inFlight.decrementAndGet();
            return Admission.shed(Shedding.OVERLOADED, overloadRetryAfter);
        }
        return Admission.granted(inFlight::decrementAndGet);
    }

    /** Currently in-flight admitted work on this node. Exposed for metrics and tests. */
    public int inFlight() {
        return inFlight.get();
    }

    /** This node's ceiling, which is deliberately not shared. */
    public long maxConcurrency() {
        return maxConcurrency;
    }
}