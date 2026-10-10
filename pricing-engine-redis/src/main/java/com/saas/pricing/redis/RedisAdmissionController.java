package com.saas.pricing.redis;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Admission;
import com.saas.pricing.core.spi.AdmissionController.Shedding;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <h2>One clock, and it is the server's</h2>
 *
 * <p>The refill arithmetic runs against <strong>Redis's own clock</strong>, read inside the script
 * with {@code TIME}. That is not an optimisation, it is the only correct choice for state shared by
 * several processes.</p>
 *
 * <p>The bucket stores a timestamp that other nodes read. Any client-supplied clock puts those nodes
 * into arithmetic on a value they cannot interpret: {@code System.nanoTime()} is a monotonic
 * <em>duration</em> with an arbitrary per-JVM origin, and {@code System.currentTimeMillis()} is a
 * wall clock subject to NTP correction. Two nodes a day apart in uptime disagree by 86,400,000, and a
 * node an hour out by NTP disagrees by 3,600,000 — in both cases enough to rewind the shared bucket
 * to full burst and then starve it again. The rate limit would then be a function of which pod the
 * load balancer happened to pick, which is the precise failure the limiter exists to prevent.</p>
 *
 * <p>Because the bucket is only ever written and read by scripts that source their clock from the
 * same Redis, cross-node skew is not mitigated here, it is <strong>structurally impossible</strong>.
 * A node whose own clock is wrong cannot express that wrongness to the limiter. The cost is that
 * {@code TIME} is only as accurate as that Redis's clock — which is the one clock worth trusting
 * for a distributed quota, and the same one a rate limit is defined against anyway.</p>
 *
 * <p>A {@link Clock} may still be supplied, but only through a constructor a host should not reach
 * for: it exists so {@code AdmissionControllerConformance} can assert refill behaviour at exact
 * instants without sleeping. Passing {@code Clock.systemUTC()} is safe; passing a per-process clock
 * reintroduces exactly the skew described above, so the seam is named for what it is.</p>
 *
 * <p><code>TIME</code> inside a script is safe under Redis's effects replication, which has been the
 * default since 5.0 and the only mode since verbatim replication was removed in 7.0: the master
 * replicates the script's <em>writes</em>, not the script. Only scripts that read {@code TIME} and
 * write nothing are a concern, and that is not this one.</p>
 *
 * <h2>Continuous refill, matching the in-memory implementation</h2>
 *
 * <p>Redis's own Java token-bucket guide refills on discrete intervals
 * ({@code math.floor(elapsed / interval)}). That would make the distributed limiter and the
 * in-memory one behave differently at the margin — 1,000/s becomes 10 bursts of 100 — which is
 * exactly the drift {@code AdmissionControllerConformance} exists to catch. This refills
 * continuously, so both implementations admit the same requests for the same instants.</p>
 *
 * <h2>Shedding for concurrency hands the shared token back</h2>
 *
 * <p>The rate token is a shared resource; the concurrency slot is local. When the local ceiling
 * rejects work that the shared quota <em>did</em> grant, the token is returned by a second script
 * before the caller is shed. Without that, a node that is merely saturated would permanently drain
 * its tenants' cluster-wide quota: repeated 503s would consume permits that no other node ever got
 * to use, and a tenant would be rate limited by a machine that was not the one refusing it. The
 * in-memory {@link TokenBucketAdmissionController} has always done this; the two implementations
 * are interchangeable only if both do.</p>
 *
 * <p>The refund is best-effort on purpose. A failure to return the token costs one permit and makes
 * the limiter marginally stricter, which is the safe direction; surfacing it as an error would turn
 * a clean 503 into an unexplained 500 for a caller who is already being shed.</p>
 *
 * <p><strong>Fail-closed.</strong> If Redis is unreachable this throws. Failing open would mean a
 * tenant whose quota is exhausted is silently served whenever Redis is down, which is precisely when
 * a rate limit matters most.</p>
 */
public class RedisAdmissionController implements AdmissionController {

    private static final String KEY_PREFIX = "aequitas:quota:";

    /**
     * The instant every refill is computed against, in epoch millis.
     *
     * <p>Prefixed to both scripts rather than written twice: the consume and refund paths must
     * agree on the clock exactly, and a shared prelude is what makes that structural instead of a
     * convention.</p>
     */
    private static final String CLOCK_LUA = """
        local now
        if (ARGV[3] == '') then
          local serverTime = redis.call('TIME')
          now = tonumber(serverTime[1]) * 1000 + math.floor(tonumber(serverTime[2]) / 1000)
        else
          now = tonumber(ARGV[3])
        end
        """;

    /**
     * Refill-and-consume, atomic on the server.
     *
     * <p>{@code KEYS[1]} bucket key. {@code ARGV[1]} capacity, {@code ARGV[2]} refill <em>per
     * millisecond</em>, {@code ARGV[3]} caller's epoch millis or the empty string to defer to the
     * server clock, {@code ARGV[4]} key ttl millis.</p>
     *
     * <p>Returns {@code {allowed, waitMillis}}.</p>
     */
    private static final String CONSUME_LUA = CLOCK_LUA + """
        local raw = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
        local tokens = tonumber(raw[1])
        local updated = tonumber(raw[2])
        local capacity = tonumber(ARGV[1])
        local permitsPerMilli = tonumber(ARGV[2])

        if (not tokens) then
          tokens = capacity
          updated = now
        end

        -- Clamped: a stored instant ahead of now must not mint tokens by subtraction. With a single
        -- server clock this only happens if the hash was restored from a lagging replica, but the
        -- bucket is still wrong by a burst in that case and must not amplify it.
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

    /**
     * Returns a token the concurrency ceiling took, atomic on the server.
     *
     * <p>Same ARGV layout as {@link #CONSUME_LUA}, so both paths read the bucket with identical
     * arithmetic.</p>
     */
    private static final String REFUND_LUA = CLOCK_LUA + """
        local raw = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
        local tokens = tonumber(raw[1])
        local updated = tonumber(raw[2])
        if (not tokens) or (not updated) then
          -- The bucket was reclaimed between the consume and the refund. Returning a token into a
          -- bucket the cluster has already forgotten would resurrect a full one, which is the exact
          -- over-admission this refund exists to prevent.
          return 0
        end

        local capacity = tonumber(ARGV[1])
        local permitsPerMilli = tonumber(ARGV[2])
        local elapsed = now - updated
        if elapsed < 0 then elapsed = 0 end

        local restored = math.min(capacity, tokens + (elapsed * permitsPerMilli) + 1)
        redis.call('HSET', KEYS[1], 'tokens', tostring(restored), 'updated', tostring(now))
        redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
        return 1
        """;

    private final RedisCommands<String, String> commands;
    private final AtomicInteger inFlight = new AtomicInteger();

    private final double burstCapacity;
    private final double permitsPerMilli;
    private final long maxConcurrency;
    private final Duration overloadRetryAfter;
    private final long bucketTtlMillis;

    /** Null means "ask Redis", which is the only mode a host should ever use. */
    private final Clock testClock;

    public RedisAdmissionController(StatefulRedisConnection<String, String> connection,
                                    long permits, Duration period, long burst, int maxConcurrency) {
        this(connection.sync(), permits, period, burst, maxConcurrency,
            Duration.ofMillis(50), null);
    }

    public RedisAdmissionController(RedisCommands<String, String> commands,
                                    long permits, Duration period, long burst, int maxConcurrency,
                                    Duration overloadRetryAfter) {
        this(commands, permits, period, burst, maxConcurrency, overloadRetryAfter, null);
    }

    /**
     * Fixes the clock instead of using Redis's.
     *
     * <p>A test seam, not a tuning knob: it exists so the shared conformance suite can assert refill
     * at exact instants. Passing a per-process clock here reintroduces the cross-node skew this
     * class exists to be immune to — {@code System.nanoTime()} here would hand every other node a
     * timestamp they cannot read at all.</p>
     */
    public RedisAdmissionController(RedisCommands<String, String> commands,
                                    long permits, Duration period, long burst, int maxConcurrency,
                                    Duration overloadRetryAfter, Clock testClock) {
        if (permits <= 0 || burst <= 0 || maxConcurrency <= 0) {
            throw new IllegalArgumentException("permits, burst and maxConcurrency must be positive");
        }
        Objects.requireNonNull(period, "period cannot be null");
        Objects.requireNonNull(overloadRetryAfter, "overloadRetryAfter cannot be null");
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
        this.testClock = testClock;

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

    /** Empty means "no caller clock": the script falls back to {@code TIME}. */
    private String nowArgument() {
        return testClock == null ? "" : Long.toString(testClock.millis());
    }

    @Override
    public Admission admit(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");

        long waitMillis;
        try {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) commands.eval(CONSUME_LUA, ScriptOutputType.MULTI,
                new String[]{bucketKey(tenantId)},
                Long.toString((long) burstCapacity),
                Double.toString(permitsPerMilli),
                nowArgument(),
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
            // The token came out of a bucket shared with every other node, so this node's local
            // saturation must not spend it. Give it back before shedding.
            refundToken(tenantId);
            return Admission.shed(Shedding.OVERLOADED, overloadRetryAfter);
        }
        return Admission.granted(inFlight::decrementAndGet);
    }

    /**
     * Returns the permit consumed by {@link #admit} when the local ceiling then rejected the work.
     *
     * <p>Deliberately swallowing a Redis failure: the caller is already being shed, so there is no
     * response left to carry an error, and the only consequence is one lost permit — a limiter that
     * is slightly stricter than configured, never one that admits past its quota.</p>
     */
    private void refundToken(TenantId tenantId) {
        try {
            commands.eval(REFUND_LUA, ScriptOutputType.INTEGER,
                new String[]{bucketKey(tenantId)},
                Long.toString((long) burstCapacity),
                Double.toString(permitsPerMilli),
                nowArgument(),
                Long.toString(bucketTtlMillis));
        } catch (RedisException e) {
            // Fail closed on the refund: one permit is forfeit, which is the safe direction.
        }
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