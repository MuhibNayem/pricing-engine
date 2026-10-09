# pricing-engine-redis

Optional Redis-backed `IdempotencyKeyStore`, for a host that already runs Redis and wants
sub-millisecond deduplication in front of PostgreSQL.

**Nothing else in the library depends on this module.** If you do not declare it, Redis never
appears on your classpath and this code never runs.

```xml
<dependency>
    <groupId>com.saas.pricing</groupId>
    <artifactId>pricing-engine-redis</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
@Bean
IdempotencyKeyStore idempotencyKeyStore(RedisCommands<String, String> commands) {
    return new RedisIdempotencyKeyStore(commands);
}
```

## Read this before you adopt it

**PostgreSQL stays the system of record.** Redis is a fast path in front of it, never the
authority. That ordering is not negotiable: Redis replication is asynchronous, so an acknowledged
write can be lost on failover, and a deduplication layer that can lose an acknowledgement cannot be
the thing that decides whether money moves twice.

If you are using the JDBC store today, adding this changes latency, not authority.

## What it does that `SET NX` does not

`decide` has four outcomes — proceed, replay, in-flight, conflict — and choosing between them
depends on state a single `SET NX` cannot see: whether the record is still live, whether it
completed, and whether its fingerprint matches. Read-then-write is a race: two callers both see an
absent key and both proceed, which is exactly what idempotency exists to prevent. The decision runs
as a Lua script, which executes atomically server-side.

Keys carry a Redis Cluster hash tag (`{tenantId}`), so a tenant's records stay in one slot and the
multi-key script remains legal under Cluster.

## Fail-closed by default

If Redis is unreachable, this throws `IdempotencyKeyStoreUnavailableException` rather than
proceeding. Proceeding is the tempting choice — it keeps the endpoint available — and it is the
wrong one: a deduplication layer that fails open silently re-enables the double charge it exists to
prevent. Catching that exception and proceeding anyway is possible, and is a business decision
about double-charging rather than a configuration detail.

## Conformance

Both implementations run the same nine assertions in `IdempotencyKeyStoreConformance` — the
in-memory one and this one. Adding a store means adding a subclass, not writing its own idea of
what a claim is.

That suite earned its keep immediately. It caught two defects in this adapter that a mocked Redis
would never have surfaced:

1. `redis.call('HMGET', ...)` returns **one nested table**, not six values. Multiple assignment
   bound the first name to the whole table, so every request answered `CONFLICT`/422 and nothing
   was ever stored.
2. `PEXPIREAT` is interpreted against **Redis's** clock while the record's expiry lives in the
   caller's. Any disagreement between the two evicted the record immediately. The script now uses a
   relative `PEXPIRE`, so logical expiry stays in the caller's domain — the correctness authority —
   and physical eviction is memory hygiene.

## Also read

`RedisIdempotencyKeyStore` covers `IdempotencyKeyStore`. `RatingClaimStore` still ships as
in-memory and JDBC implementations only; adding a Redis one means implementing `compareAndSet`
atomically, which `SET NX` cannot express either.