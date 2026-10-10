package com.saas.pricing.redis;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.RatingClaimStore;
import com.saas.pricing.metering.spi.RatingClaimStore.Charged;

import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis-backed {@link RatingClaimStore} for a host that already runs Redis.
 *
 * <p>The reason {@code IdempotencyKeyStore} needed Lua and this needs it more: {@code compareAndSet}
 * is a genuine compare-and-swap — read the stored value, compare it against an expectation, and
 * replace it, without another writer interleaving. {@code SET NX} cannot express it, because it
 * compares against <em>absence</em> rather than against a value. A read-then-write in Java is the
 * classic race: two nodes both read the old claim, both conclude they won, both write, and a window
 * is charged twice.</p>
 *
 * <p>Redis's own documentation is blunt about the alternatives: "Everything you can do with a Redis
 * Transaction, you can also do with a script, and usually the script will be both simpler and
 * faster." {@code WATCH}/{@code MULTI} would work but aborts and retries under contention, and Lua
 * has no retry loop to get wrong.</p>
 *
 * <p>Stored as a hash so {@code PEXPIRE} manages the key and the fields stay individually
 * addressable. The claim's TTL is retention, not the correctness window: correctness comes from the
 * stored value itself, and a reclaimed claim is simply absent, which is the correct answer for a
 * drawdown nobody has recorded.</p>
 *
 * <p>Keys carry a Redis Cluster hash tag ({@code {tenantId}}) so a tenant's claims share a slot and
 * the scripts stay legal under Cluster.</p>
 *
 * <p><strong>Fail-closed.</strong> An unreachable Redis throws rather than reporting "no claim
 * held". Reporting absence here would tell a caller it may charge a window that was already
 * charged — see {@link IdempotencyKeyStoreUnavailableException} for why that is the wrong default
 * for money.</p>
 */
public class RedisRatingClaimStore implements RatingClaimStore {

    private static final String KEY_PREFIX = "aequitas:claim:";

    /**
     * Unconditional write, atomic on the server.
     *
     * <p>{@code KEYS[1]} claim key. {@code ARGV[1]} amount, {@code ARGV[2]} currency,
     * {@code ARGV[3]} chargedAt, {@code ARGV[4]} ttl millis.</p>
     *
     * <p>Exists because the obvious implementation is wrong in a way that only shows up under load:
     * three {@code HSET}s and a {@code PEXPIRE} as four separate round trips. Redis runs each command
     * atomically, so a reader cannot observe a half-written <em>command</em> — but nothing stops it
     * from observing a half-written <em>claim</em>. A {@code compareAndSet} landing between field one
     * and field three reads a claim with the new amount and the old timestamp, concludes it is the
     * claim it expected to replace, and swaps against a mixture that never existed. On a drawdown
     * claim that is money charged against a fabricated figure.</p>
     *
     * <p>The same four commands pipelined would be atomic to other clients on a single connection and
     * still not atomic to a <em>different</em> connection — pipelining orders your commands, not
     * anyone's else. Only a script closes that window.</p>
     */
    private static final String RECORD_LUA = """
        redis.call('HSET', KEYS[1], 'amount', ARGV[1], 'currency', ARGV[2], 'charged_at', ARGV[3])
        -- Relative, not PEXPIREAT: the physical key lifetime is retention only. Correctness comes
        -- from the stored value, and a claim past its TTL is correctly absent rather than wrong.
        redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
        return 1
        """;

    /**
     * Compare-and-set. {@code KEYS[1]} claim key. {@code ARGV[1]} expected amount, or the empty
     * string to mean "expected absent". {@code ARGV[2]} expected currency, {@code ARGV[3]} expected
     * chargedAt, {@code ARGV[4]} updated amount, {@code ARGV[5]} updated currency,
     * {@code ARGV[6]} updated chargedAt, {@code ARGV[7]} ttl millis, {@code ARGV[8]} whether the
     * caller expects a claim to be present.
     *
     * <p>Returns 1 when this caller performed the replacement, 0 otherwise. A caller that loses must
     * re-read and recompute its delta rather than charge.</p>
     */
    private static final String COMPARE_AND_SET_LUA = """
        -- HMGET is a multi-bulk reply, which Redis Lua returns as ONE Lua table. Multiple
        -- assignment would bind the first name to the whole table, so fields are indexed.
        local raw = redis.call('HMGET', KEYS[1], 'amount', 'currency', 'charged_at')
        local currentAmount, currentCurrency, currentChargedAt = raw[1], raw[2], raw[3]
        local expectedPresent = ARGV[8] == '1'

        if expectedPresent then
          -- Not merely "a claim exists" but this exact claim: amount, currency and the
          -- chargedAt that made it. Comparing on amount alone would let a concurrent re-charge
          -- that happens to reach the same total win a swap it did not observe.
          if (not currentAmount) then return 0 end
          if currentAmount ~= ARGV[1] then return 0 end
          if currentCurrency ~= ARGV[2] then return 0 end
          if currentChargedAt ~= ARGV[3] then return 0 end
        else
          if currentAmount then return 0 end
        end

        redis.call('HSET', KEYS[1], 'amount', ARGV[4], 'currency', ARGV[5], 'charged_at', ARGV[6])
        -- Relative, not PEXPIREAT: the physical key lifetime is retention only. Correctness comes
        -- from the stored value, and a claim past its TTL is correctly absent rather than wrong.
        redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[7]))
        return 1
        """;

    private final RedisCommands<String, String> commands;
    private final Clock clock;
    private final Duration ttl;

    public RedisRatingClaimStore(StatefulRedisConnection<String, String> connection) {
        this(connection.sync(), Clock.systemUTC(), Duration.ofDays(90));
    }

    public RedisRatingClaimStore(StatefulRedisConnection<String, String> connection, Clock clock, Duration ttl) {
        this(connection.sync(), clock, ttl);
    }

    public RedisRatingClaimStore(RedisCommands<String, String> commands, Clock clock, Duration ttl) {
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl cannot be null");
    }

    private static String redisKey(TenantId tenantId, String claimKey) {
        return KEY_PREFIX + "{" + tenantId.value() + "}:" + claimKey;
    }

    /** Amounts are normalised to plain strings so the Lua comparison is exact, not float-approximate. */
    private static String normalise(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    @Override
    public Optional<Charged> find(TenantId tenantId, String claimKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        try {
            // HMGET yields one KeyValue per requested field; a field absent from the hash comes
            // back without a value. Look up by field name rather than by position so adding a field
            // cannot silently shift a read.
            Map<String, String> fields = new HashMap<>();
            for (KeyValue<String, String> field
                    : commands.hmget(redisKey(tenantId, claimKey), "amount", "currency", "charged_at")) {
                if (field != null && field.hasValue()) {
                    fields.put(field.getKey(), field.getValue());
                }
            }
            String amount = fields.get("amount");
            if (amount == null) {
                return Optional.empty();
            }
            return Optional.of(new Charged(new BigDecimal(amount),
                fields.get("currency"),
                Instant.parse(fields.get("charged_at"))));
        } catch (RedisException e) {
            throw unavailable("reading a claim", e);
        }
    }

    @Override
    public void record(TenantId tenantId, String claimKey, Charged charged) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(charged, "charged cannot be null");
        String key = redisKey(tenantId, claimKey);
        try {
            // One round trip, one atomic unit: see RECORD_LUA for what four separate commands cost.
            commands.eval(RECORD_LUA, ScriptOutputType.INTEGER, new String[]{key},
                normalise(charged.amount()),
                charged.currency(),
                charged.chargedAt().toString(),
                Long.toString(Math.max(1L, ttl.toMillis())));
        } catch (RedisException e) {
            throw unavailable("recording a claim", e);
        }
    }

    @Override
    public boolean compareAndSet(TenantId tenantId, String claimKey, Optional<Charged> expected, Charged updated) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");
        Objects.requireNonNull(updated, "updated cannot be null");

        boolean expectedPresent = expected.isPresent();
        Charged want = expected.orElse(null);

        Object raw;
        try {
            raw = commands.eval(COMPARE_AND_SET_LUA, ScriptOutputType.INTEGER,
                new String[]{redisKey(tenantId, claimKey)},
                expectedPresent ? normalise(want.amount()) : "",
                expectedPresent ? want.currency() : "",
                expectedPresent ? want.chargedAt().toString() : "",
                normalise(updated.amount()),
                updated.currency(),
                updated.chargedAt().toString(),
                Long.toString(Math.max(1L, ttl.toMillis())),
                expectedPresent ? "1" : "0");
        } catch (RedisException e) {
            throw unavailable("comparing and setting a claim", e);
        }
        return ((Number) raw).longValue() == 1L;
    }

    @Override
    public boolean remove(TenantId tenantId, String claimKey, Charged expected) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");
        try {
            // compare-and-delete, atomic on the server.
            Long removed = commands.eval("""
                local raw = redis.call('HMGET', KEYS[1], 'amount', 'currency', 'charged_at')
                if (not raw[1]) then return 0 end
                if raw[1] ~= ARGV[1] or raw[2] ~= ARGV[2] or raw[3] ~= ARGV[3] then return 0 end
                redis.call('DEL', KEYS[1])
                return 1
                """, ScriptOutputType.INTEGER,
                new String[]{redisKey(tenantId, claimKey)},
                normalise(expected.amount()), expected.currency(), expected.chargedAt().toString());
            return removed != null && removed == 1L;
        } catch (RedisException e) {
            throw unavailable("removing a claim", e);
        }
    }

    private static IdempotencyKeyStoreUnavailableException unavailable(String what, RedisException cause) {
        return new IdempotencyKeyStoreUnavailableException(
            "Redis is unreachable while " + what
                + "; refusing to report an absent claim, which could authorise charging a window twice", cause);
    }
}