package com.saas.pricing.redis;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.idempotency.IdempotencyDecision;
import com.saas.pricing.core.model.idempotency.IdempotencyRecord;
import com.saas.pricing.core.spi.IdempotencyKeyStore;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Redis-backed {@link IdempotencyKeyStore} for a host that already runs Redis and wants
 * sub-millisecond deduplication in front of PostgreSQL.
 *
 * <p><strong>Optional by construction.</strong> This lives in its own module so nothing else in the
 * library depends on it. PostgreSQL remains the authoritative store: Redis is a fast path in front
 * of it, never the system of record. That ordering is not negotiable — Redis replication is
 * asynchronous, so an acknowledged write can be lost on failover, and a deduplication layer that can
 * lose an acknowledgement cannot be the thing that decides whether money moves twice.</p>
 *
 * <h2>Why Lua and not {@code SET NX}</h2>
 *
 * <p>{@code decide} has four outcomes, and choosing between them depends on state that a single
 * {@code SET NX} cannot see: whether the stored record is still live, whether it is completed, and
 * whether its fingerprint matches. Read-then-write is a race — two callers both see an absent key and
 * both proceed, which is the exact failure idempotency exists to prevent. A Lua script runs to
 * completion atomically on the server, so the read and the claim cannot interleave.</p>
 *
 * <h2>Fail-closed by default</h2>
 *
 * <p>If Redis is unreachable this throws {@link IdempotencyKeyStoreUnavailableException} rather than
 * proceeding. Proceeding is the tempting choice — it keeps the endpoint available — and it is exactly
 * the wrong one: a deduplication layer that fails open silently re-enables the double charge it
 * exists to prevent. A host that would rather trade that risk for availability must opt into it
 * explicitly.</p>
 *
 * <h2>Fencing</h2>
 *
 * <p>{@code complete} and {@code release} match on {@code recordedAt}, not on the key. A request that
 * outlives its own TTL may find the key already reclaimed and re-executed by someone else; without
 * fencing, the slow original would overwrite the newer execution's result or delete its claim.</p>
 *
 * <h2>Slotting</h2>
 *
 * <p>Keys use a Redis Cluster hash tag ({@code {tenantId}}), so a tenant's records always land in one
 * slot and a multi-key Lua script stays legal under Cluster.</p>
 */
public class RedisIdempotencyKeyStore implements IdempotencyKeyStore {

    private static final String KEY_PREFIX = "aequitas:idem:";

    /**
     * Claims, or explains why not. Atomic on the server.
     *
     * <p>{@code KEYS[1]} record key. {@code ARGV[1]} fingerprint, {@code ARGV[2]} ttl seconds,
     * {@code ARGV[3]} now in epoch millis.</p>
     *
     * <p>Returns {@code {action, httpStatus, fingerprint, recordedAt, expiresAt, status}}.</p>
     */
    private static final String DECIDE_LUA = """
        -- HMGET is a multi-bulk reply, which Redis Lua hands back as ONE Lua table. Multiple
        -- assignment would bind the first name to the whole table - always truthy, never equal to
        -- a fingerprint - so the fields are indexed explicitly.
        local raw = redis.call('HMGET', KEYS[1], 'fp','st','rs','rb','ra','ea')
        local fp, st, rs, ra, ea = raw[1], raw[2], raw[3], raw[5], raw[6]
        local now = tonumber(ARGV[3])

        if fp then
          local expiry = tonumber(ea)
          -- A record past its expiry may be reclaimed; treat an absent expiry as live, so a
          -- malformed record is never silently overwritten.
          if (not expiry) or expiry > now then
            if fp == ARGV[1] then
              if st == 'COMPLETED' then
                return {'REPLAY', rs or '0', fp, ra, ea, st}
              end
              return {'IN_FLIGHT', '409', fp, ra, ea, st}
            end
            return {'CONFLICT', '422', fp, ra, ea, st}
          end
        end

        local expiresAt = now + tonumber(ARGV[2]) * 1000
        redis.call('HSET', KEYS[1], 'fp', ARGV[1], 'st', 'IN_FLIGHT', 'rs', '0', 'rb', '', 'ra', now, 'ea', expiresAt)
        -- Relative, not PEXPIREAT. The stored 'ea' lives in the caller's clock domain and is the
        -- correctness authority; PEXPIREAT would interpret it against *Redis's* wall clock, so any
        -- disagreement between the two - or a caller whose now is simply not close to Redis's -
        -- would evict the record while the caller still believes it is live. The two domains are
        -- allowed to disagree here because physical eviction is memory hygiene; the logical check
        -- above is what decides the answer, exactly as the in-memory store's read-time expiry is.
        redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) * 1000)
        return {'PROCEED', '0', ARGV[1], tostring(now), tostring(expiresAt), 'IN_FLIGHT'}
        """;

    /** Completes only if the claim is still held. {@code ARGV[1]} status, {@code ARGV[2]} body, {@code ARGV[3]} recordedAt. */
    private static final String COMPLETE_LUA = """
        local ra = redis.call('HGET', KEYS[1], 'ra')
        if (not ra) or ra ~= ARGV[3] then return 0 end
        redis.call('HSET', KEYS[1], 'st', 'COMPLETED', 'rs', ARGV[1], 'rb', ARGV[2])
        return 1
        """;

    /** Releases only if the claim is still held. {@code ARGV[1]} recordedAt. */
    private static final String RELEASE_LUA = """
        local ra = redis.call('HGET', KEYS[1], 'ra')
        if (not ra) or ra ~= ARGV[1] then return 0 end
        redis.call('DEL', KEYS[1])
        return 1
        """;

    private final RedisCommands<String, String> commands;

    public RedisIdempotencyKeyStore(StatefulRedisConnection<String, String> connection) {
        this(connection.sync());
    }

    public RedisIdempotencyKeyStore(RedisCommands<String, String> commands) {
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
    }

    private static String redisKey(TenantId tenantId, String key) {
        // Hash tag braces pin the record to one cluster slot, which a multi-key Lua script requires.
        return KEY_PREFIX + "{" + tenantId.value() + "}:" + key;
    }

    @Override
    public IdempotencyDecision decide(TenantId tenantId, String key, String fingerprint,
                                      Duration ttl, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fingerprint, "fingerprint cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");
        Objects.requireNonNull(now, "now cannot be null");

        String redisKey = redisKey(tenantId, key);
        Object raw;
        try {
            raw = commands.eval(DECIDE_LUA, ScriptOutputType.MULTI, new String[]{redisKey},
                fingerprint, Long.toString(Math.max(1L, ttl.toSeconds())), Long.toString(now.toEpochMilli()));
        } catch (RedisException e) {
            throw new IdempotencyKeyStoreUnavailableException(
                "Redis is unreachable; refusing to proceed without deduplication rather than risk a duplicate charge", e);
        }

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) raw;
        if (result == null || result.size() < 6) {
            throw new IdempotencyKeyStoreUnavailableException(
                "Redis returned an unrecognised response to an idempotency decision: " + raw);
        }

        String action = result.get(0);
        if ("PROCEED".equals(action)) {
            return IdempotencyDecision.proceed(new IdempotencyRecord(tenantId, key, fingerprint,
                IdempotencyRecord.Status.IN_FLIGHT, 0, "",
                now, now.plus(ttl)));
        }

        IdempotencyRecord existing = new IdempotencyRecord(
            tenantId, key, result.get(2),
            "COMPLETED".equals(result.get(5)) ? IdempotencyRecord.Status.COMPLETED : IdempotencyRecord.Status.IN_FLIGHT,
            Integer.parseInt(result.get(1)),
            "REPLAY".equals(action) ? readBody(redisKey) : "",
            Instant.ofEpochMilli(Long.parseLong(result.get(3))),
            Instant.ofEpochMilli(Long.parseLong(result.get(4))));

        return switch (action) {
            case "REPLAY" -> IdempotencyDecision.replay(existing);
            case "IN_FLIGHT" -> IdempotencyDecision.inFlight(existing);
            case "CONFLICT" -> IdempotencyDecision.conflict(existing);
            default -> throw new IdempotencyKeyStoreUnavailableException("Unknown action: " + action);
        };
    }

    /** The stored response body, read outside the script because it is unbounded and rarely needed. */
    private String readBody(String redisKey) {
        try {
            return commands.hget(redisKey, "rb");
        } catch (RedisException e) {
            throw new IdempotencyKeyStoreUnavailableException("Redis became unreachable reading a replay", e);
        }
    }

    @Override
    public void complete(TenantId tenantId, IdempotencyRecord claim, int httpStatus, String responseBody) {
        Objects.requireNonNull(claim, "claim cannot be null");
        try {
            commands.eval(COMPLETE_LUA, ScriptOutputType.INTEGER,
                new String[]{redisKey(tenantId, claim.key())},
                Integer.toString(httpStatus), responseBody == null ? "" : responseBody,
                Long.toString(claim.recordedAt().toEpochMilli()));
        } catch (RedisException e) {
            // Failing to store a replay is a real failure: a retry would re-execute the side effect.
            throw new IdempotencyKeyStoreUnavailableException("Redis is unreachable; the outcome could not be stored", e);
        }
    }

    @Override
    public void release(TenantId tenantId, IdempotencyRecord claim) {
        Objects.requireNonNull(claim, "claim cannot be null");
        try {
            commands.eval(RELEASE_LUA, ScriptOutputType.INTEGER,
                new String[]{redisKey(tenantId, claim.key())},
                Long.toString(claim.recordedAt().toEpochMilli()));
        } catch (RedisException e) {
            throw new IdempotencyKeyStoreUnavailableException("Redis is unreachable; the claim could not be released", e);
        }
    }
}