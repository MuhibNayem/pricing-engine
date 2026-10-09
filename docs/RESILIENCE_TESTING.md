# Resilience testing: what we run, and what it does not prove

This document exists because "chaos tested" and "production safe" are not the same claim, and the
gap between them is where incidents live. Read this before quoting the test count as assurance.

## The three suites

| Suite | Where | Runs in CI | What it establishes |
|---|---|---|---|
| Unit + integration | all modules | yes | Behaviour under a single thread, against H2 and Testcontainers |
| **Chaos** | `InvoiceFinalizationChaosTest`, `OutboxChaosTest` | **yes** | Behaviour when a step fails, is duplicated, or arrives late |
| **DB chaos** | `PostgresTransactionChaosTest` | **yes, when Docker is present** | Behaviour when a real transaction dies |
| **Load (engine)** | `PricingEngineLoadTest` | **no** — tagged `load` | Throughput and latency distribution under concurrency |
| **Load (HTTP)** | `HttpLoadTest` + `LoadTestHostApplication` | **no** — tagged `load` | The same, over the real request path a host drives |
| **Soak** | `SoakTest` | **no** — tagged `soak` | Whether the TTL and entry caps are actually honoured |

Load tests are excluded from the ordinary build by default and run deliberately:

```bash
# the whole load profile, on quiet hardware
mvn test -Dtest=PricingEngineLoadTest -Dsurefire.excludedGroups=

# tune it
mvn test -Dtest=PricingEngineLoadTest -Dsurefire.excludedGroups= \
    -Dload.threads=32 -Dload.iterations=20000
```

The exclusion is configured in the root `pom.xml` via `surefire.excludedGroups`. It exists because
a throughput number measured on a shared CI box is noise. The correctness assertions inside those
classes are not withheld — only the timing measurement is.

## Measured on this machine

Core pricing hot path, `DefaultPricingEngine`, in-memory rate card, H2-free:

```
threads=10   evaluations=40,000
throughput   55,390 eval/s
latency      p50=19µs   p95=678µs   p99=1,716µs   max=13,642µs
```

Read these as *a shape*, not an SLO. The p50/p95 gap is the interesting part: median work is fast,
but contention produces a long tail. Before pinning any of this as a target, record the machine,
the JVM flags, and the dataset — a latency number without those three is not reproducible.

Over real HTTP, driven through the minimal host application, against `/api/v1/pricing/evaluate`:

```
threads=16   requests=30,353   window=8.0s
throughput    3,789 req/s
latency       p50=3ms   p95=11ms   p99=20ms   max=127ms
statuses      {200=30,353}
```

`LoadTestHostApplication` is the smallest host that exercises the real wiring — auto-configuration,
tenant resolution, rate card, web layer. It is **test scope only** and is never published. It exists
because a library has no server to drive until somebody writes one, and because the only throughput
number a host team can act on is the one measured through *their* transport. Compare this figure
against your own stack, not against the engine-only figure above; an order of magnitude apart is
normal, and the gap is your Tomcat and your JSON, not the pricing logic.

A first run of this profile reported 19,148 requests while the server had actually served 19,290 —
sixteen threads were appending to one unsynchronised `ArrayList` and losing samples, which quietly
understated every percentile. Fixed with per-thread lists merged after the latch. Worth knowing
because it is the same shape of bug as the invoice-numbering one: a harness that lies is worse than
no harness.

The suite asserts only that throughput does not *collapse* (>200 eval/s) and that money stays
exact at every quantity. It deliberately does not assert a latency SLO, because no SLO has been
agreed and an unagreed SLO asserted in a test is a test that will be "fixed" by weakening itself.

## What chaos testing covers

### Invoice finalization (`InvoiceFinalizationChaosTest`, 4 tests)

Finalization is the one path that consumes a document number and makes an invoice binding. It runs
`allocate → write → announce`, and all three can fail.

- A failed write releases its number. The next invoice gets `INV-0001`, not `INV-0002` — no hole.
- The failed invoice stays `DRAFT` with no number attached; there is no half-finalized document.
- Five consecutive failures still leave `INV-0001` available.
- A caller-supplied (migrated/imported) number is never released back into the series, because
  rewinding it would corrupt a real tenant's sequence.

### Outbox delivery (`OutboxChaosTest`, 7 tests)

Delivery is host-owned and goes wrong routinely. The record must stay trustworthy through it.

- 50 redeliveries of one event produce one record, not fifty.
- The same id with different content is **refused**, not overwritten.
- A success arriving after the retry budget was spent still clears the debt.
- Backoff is honoured: an event is not retried before `nextAttemptAt`.
- An exhausted event stops consuming delivery budget and stays visible to an operator.
- 64 threads concurrently announcing the same change yield exactly one event.

### Real PostgreSQL transactions (`PostgresTransactionChaosTest`, 6 tests)

Runs against a real `postgres:17-alpine` container. This is the layer a repository mock cannot
reach: a mock throws where the *code* decides to throw, a real server throws where the *engine*
decides to.

- An exception thrown after two writes discards both.
- A constraint violation late in a transaction discards the earlier, individually-valid write —
  a reader never sees one invoice sharing a number with another.
- **A backend killed mid-transaction** via `pg_terminate_backend` while the transaction is open
  and dirty leaves nothing behind.
- `SERIALIZABLE` isolation surfaces a genuine conflict (*"could not serialize access due to
  concurrent update"*, SQLSTATE 40001) instead of silently losing an update.
- A deadlock kills **exactly one** transaction; the survivor commits.
- 32 threads allocating from one series under real `SELECT … FOR UPDATE` row locks produce 32
  distinct, gapless numbers.

These skip themselves when Docker is unavailable (`disabledWithoutDocker = true`) rather than
reporting a pass they did not earn. Check the build log: a CI box without Docker runs strictly
fewer assertions than your laptop, and that difference should be visible, not silent.

### Soak (`SoakTest`, 7 tests, metering)

Both in-memory stores promise that the previous unbounded maps "grew for the lifetime of the
process" and are now TTL-bounded and entry-capped. This drives far more operations than the cap
allows and checks the promise is kept. Time is injected through the `Clock` seam both stores
already expose, so **2.7 simulated years pass in about six seconds**:

```
operations=50,000   threads=8   simulated-elapsed=2028-10-29 (from 2026-01-01)
cap=5,000 per store
```

- 4× the cap of idempotency keys: the oldest 5,000 are evicted, the newest 5,000 survive.
- 4× the cap of rating claims: same, measured through `find()` rather than a `size()` the SPI
  does not expose — behaviour, not internals.
- Past a 7-day TTL a key is forgotten, so re-ingesting it is `CLAIMED`, not `DUPLICATE`.
- Past a 90-day TTL a rating claim is unreadable. This one matters most: a surviving stale claim
  would make a retry compute its delta against a stale total and charge the wrong amount.
- A hot key hammered by 8 threads × 500 attempts is classified exactly once per attempt —
  `CLAIMED`/`DUPLICATE`/`CONFLICT` — with no attempt falling into two buckets or none.

### Resolved: every operation paid an O(n) sweep, under a global lock

This section records a finding that has since been **fixed**, because the measurement is the whole
reason to trust the fix. Mean cost of one `claim()` and one `isDuplicate()` against a store
pre-loaded to the given size:

```
                            before                    after
preloaded     claim()      isDuplicate()     claim()      isDuplicate()
    100          5.0 us          1.7 us        0.2 us          0.1 us
  1,000          4.5 us          4.3 us        0.2 us          0.0 us
 10,000         47.5 us         51.0 us        0.2 us          0.0 us
 50,000        234.5 us        226.7 us        0.1 us          0.0 us
100,000        434.2 us        436.6 us        0.1 us          0.0 us
```

Before: linear in the number of live entries, on **every** call — `InMemoryIdempotencyStore`
swept inside `synchronized (seenKeys)` on `claim()` and `isDuplicate()`, and
`InMemoryRatingClaimStore` swept on `find()`, `record()`, `compareAndSet()` and `remove()`. The
theoretical ceiling was roughly **2,300 claims per second, globally**, degrading rather than
plateauing.

The fix had two separable halves, and the order mattered:

1. **Expiry moved to the read path.** Both stores had no per-entry check — map membership *was*
   the expiry verdict — so the exact, complete sweep was load-bearing for correctness, not just
   memory. Amortising it alone would have made an expired key answer `DUPLICATE`.
2. **Reclamation became bounded work** on a write-order queue: at most `SWEEP_BUDGET` nodes per
   pass, at most once per `SWEEP_EVERY_OPERATIONS` operations, widening near the entry cap.

Part 2 is only sound because of part 1. The queue cannot assume monotonic record order — record
time is the caller's `eventTime` and metering ingests late events — so the sweep examines its
budget and takes whatever is due rather than stopping at the first live node.

This is the fixed-expiration structure Caffeine uses for `expireAfterWrite` (a write-order deque;
its timer wheel is reserved for the *variable* `expireAfter(Expiry)` policy a fixed TTL does not
use), and Redis's shape: bounded work per cycle rather than a full pass.

The **background sweeper** — option 3 of the three originally listed — remains deliberately
rejected. It would mean the store starting threads it does not own, which is the same boundary
that keeps auth, scheduling and transport host-side. `purgeExpired()` is public instead, so a host
with its own scheduler can drive it.

`InMemoryStoreExpiryTest` (13 tests) pins the invariant that licenses the bounded sweep: expiry
stays exact past one full sweep budget, backdated events expire on schedule, repeated overwrites
compact rather than leaking the queue, and per-operation cost does not scale with entry count.

## What this does NOT prove

Stated plainly, because this is the part that gets skipped in a status update.

1. **Network chaos covers the transport, but only two fault shapes.** `NetworkFaultChaosTest`
   severs the connection and adds downstream latency. It does **not** corrupt a TCP stream, drop
   packets mid-message, fail a DNS lookup, or test a half-open connection — a proxy cutting cleanly
   is tidier than a network that misbehaves, and a repository that surfaces a socket error is a
   polite subset of what reaches production.

2. **No deadline-enforcement test.** The latency case proves the injected delay is *observable*,
   not that anything *acts* on it. Whether a hung database trips a caller in bounded time depends
   on your pool configuration, the driver's `socketTimeout`, and the gateway's timeouts — none of
   which this library owns or configures. That is a deliberate boundary and it is also a real gap in
   what has been verified.

3. **Clock injection stops at the store.** The soak advances a `Clock` the stores were handed. It
   says nothing about NTP drift, a leap second, or a clock that jumps backwards on the host, and
   nothing about connection-pool exhaustion or file-descriptor leaks, which need hours of real
   elapsed time rather than simulated time.

4. **No clock or randomness injection at the system level.** Tests choose their instants. A real
   cluster has NTP drift, a leap second, and a clock that jumps backwards — untested.

5. **Chaos testing finds the failures you thought of.** These tests encode failure modes someone
   already reasoned about. They are strong evidence against *those* modes and almost no evidence
   about the one nobody has imagined yet. That is the honest ceiling of this technique, and the
   reason production history is still the only real proof.

## Recommended order of work

The three items that used to head this list are now done:

1. ~~Network fault injection~~ — `NetworkFaultChaosTest` routes JDBC through Toxiproxy and severs
   the connection mid-transaction, adds downstream latency, and confirms both recovery and the
   absence of a partial row.
2. ~~Network fault injection~~ — same suite; the duplicate entry is removed.
3. ~~A rate limiter~~ — `AdmissionController` with a per-tenant token bucket and a concurrency
   ceiling, mapped to 429/503 with `Retry-After`.

What remains open:

1. **Distributed rate limiting.** `TokenBucketAdmissionController` is per-process. Behind N load
   balancers the effective per-tenant rate is N times the configured one. The SPI is the seam —
   a Redis- or Postgres-backed implementation is a host decision — but the reference
   implementation is single-node, and the documentation says so.
2. **Adaptive load shedding.** The ceiling is a configured constant, not a function of measured
   latency or queue depth. Netflix's `concurrency-limits` and an adaptive limit would track
   saturation rather than assume it.
3. **SBOM and supply-chain attestation.**

None of the three changes production code. All three change what we can honestly claim.