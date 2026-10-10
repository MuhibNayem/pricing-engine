# pricing-engine-redis

Optional Redis implementations for a host that already runs Redis: a deduplication fast path for
HTTP idempotency keys, a rating-window claim store, and a cluster-wide rate limiter.

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
@Bean IdempotencyKeyStore idempotencyKeys(RedisCommands<String, String> c) {
    return new RedisIdempotencyKeyStore(c);
}

@Bean RatingClaimStore ratingClaims(StatefulRedisConnection<String, String> c) {
    return new RedisRatingClaimStore(c);
}

@Bean AdmissionController admission(StatefulRedisConnection<String, String> c) {
    // Shared per-tenant quota, local concurrency ceiling.
    return new RedisAdmissionController(c, 1_000, Duration.ofSeconds(1), 1_000, 256);
}
```

## Read this before you adopt it

**PostgreSQL stays the system of record.** Redis is a fast path in front of it, never the
authority. That ordering is not negotiable: Redis replication is asynchronous, so an acknowledged
write can be lost on failover, and a deduplication layer that can lose an acknowledgement cannot be
the thing that decides whether money moves twice.

If you are using the JDBC store today, adding this changes latency, not authority.

## What is here, and what it does not use `SET NX` for

| Class | Why it needs Lua |
|---|---|
| `RedisIdempotencyKeyStore` | `decide` has four outcomes chosen by state `SET NX` cannot see — is the record live, did it complete, does the fingerprint match. |
| `RedisRatingClaimStore` | `compareAndSet` compares against a *value*, not against absence. This is the method that stops a window being charged twice. |
| `RedisAdmissionController` | Refill, check and decrement must be one indivisible step, or two nodes both grant a token that only exists once. |

Keys carry a Redis Cluster hash tag (`{tenantId}`), so a tenant's records stay in one slot and the
multi-key scripts remain legal under Cluster.

## Which limit is shared, and why only that one

The per-tenant **quota** is shared: it is a statement about the tenant's entitlement, and a tenant
cannot hold one per node.

The **concurrency ceiling** stays local. It models what *this node* can execute — cores, a
connection pool, a thread budget — none of which is global. Sharing it would be wrong in both
directions: a remote node cannot do this node's work, and a shared ceiling would let one node's
silently consume another's headroom.

## Fail-closed by default

If Redis is unreachable, these throw `IdempotencyKeyStoreUnavailableException` rather than
reporting an absent claim or an open quota. Reporting absence would tell a caller it may charge a
window that was already charged; serving a request would silently serve a tenant whose quota is
exhausted. Catching that exception and proceeding is possible, and is a business decision about
double-charging rather than a configuration detail.

## Conformance

Every implementation must pass the same assertions as its in-memory counterpart:

| Suite | In-memory | Redis |
|---|---|---|
| `IdempotencyKeyStoreConformance` | 9 | 9 |
| `RatingClaimStoreConformance` | 9 | 12 |
| `AdmissionControllerConformance` | 7 | 12 |

Adding a store means adding a subclass, not writing its own idea of what a claim is.

That approach earned its keep repeatedly. The Redis adapters shipped six defects that a mocked
Redis would never have surfaced:

1. **`redis.call('HMGET', …)` returns ONE nested table**, not six values. Multiple assignment bound
   the first name to the whole table — always truthy, never equal to a fingerprint — so every
   request answered `CONFLICT`/422 and nothing was ever stored.
2. **`PEXPIREAT` is absolute against Redis's clock** while the record's expiry lives in the
   caller's. Any disagreement evicted the record immediately, so every second call saw an absent
   key. Now a relative `PEXPIRE`.
3. **`return {0, …}` in Lua is an integer reply**, which Lettuce decodes as `Long`, not `String`.
   The `"0".equals(...)` check never matched, so the rate limiter read every shed as an admission
   and permitted everything — a limiter that was a silent no-op. Quoted strings fix it.
4. **A per-nanosecond rate multiplied by a millisecond elapsed time** refills a millionfold too
   slowly, which is indistinguishable from "the limiter stopped working after a pause". The
   distributed limiter now refills continuously, matching the in-memory one exactly — Redis's own
   Java guide refills on discrete intervals, which would have made the two silently disagree.
5. **The shared bucket was stamped with `System.nanoTime()`.** A monotonic *duration* with an
   arbitrary per-JVM origin is not something another node can read: two pods a day apart in uptime
   disagree by 86,400,000, and either direction is enough to rewind the bucket to full burst. The
   script now sources its instant from `redis.call('TIME')`, which makes cross-node skew
   structurally impossible rather than clamped. The `Clock` constructor is a test seam for the
   conformance suite — passing a per-process clock there reintroduces exactly this bug.
6. **A concurrency shed spent a token out of the shared quota.** The in-memory controller refunded
   it; the Redis one did not, so a node that was merely saturated drained its tenants' cluster-wide
   quota with 503s it had no right to spend. Found by the shared conformance suite, which is the
   only reason it was ever noticed — `concurrencyShedRefundsTheRateToken` read 3 where the
   in-memory implementation read 4.

### Reads and writes must each be one indivisible step

`RatingClaimStore.record` used to issue three `HSET`s and a `PEXPIRE` as four round trips. Each
command is atomic, so a reader never sees a half-executed *command* — but nothing stopped it
seeing a half-written *claim*, and a `compareAndSet` landing between field one and field three read
one writer's amount with another's timestamp and swapped against a mixture that never existed.
`record` is now a single script. `recordIsASingleAtomicRoundTrip` counts the commands so the fix
cannot silently regress into a round trip again.

## Also read

`PricingEngineProperties.admission` covers the fixed and adaptive ceilings; see
[the runbook](../docs/DEVELOPER_RUNBOOK.md) for the operational view.