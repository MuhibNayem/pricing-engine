# Resilience testing: what we run, and what it does not prove

This document exists because "chaos tested" and "production safe" are not the same claim, and the
gap between them is where incidents live. Read this before quoting the test count as assurance.

## The three suites

| Suite | Where | Runs in CI | What it establishes |
|---|---|---|---|
| Unit + integration | all modules | yes | Behaviour under a single thread, against H2 and Testcontainers |
| **Chaos** | `InvoiceFinalizationChaosTest`, `OutboxChaosTest` | **yes** | Behaviour when a step fails, is duplicated, or arrives late |
| **Load** | `PricingEngineLoadTest` | **no** — tagged `load` | Throughput and latency distribution under concurrency |

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

## What this does NOT prove

Stated plainly, because this is the part that gets skipped in a status update.

1. **No real-transaction rollback on PostgreSQL.** The chaos suite injects failures at the
   repository interface. It does not kill a live PostgreSQL connection mid-transaction, nor test
   `SERIALIZABLE` retry under genuine contention. `PostgresMigrationTest` covers the schema; the
   rollback *behaviour* is not yet covered. This is the highest-value gap in the list.

2. **No network-level chaos.** Nothing here partitions a connection, adds 500ms of latency,
   corrupts a TCP stream, or makes a DNS lookup fail. Those are the failures that actually reach
   production, and a repository that throws `RuntimeException` is a polite subset of them.

3. **No HTTP load test.** The repository contains no runnable application — it is a library, so
   there is no server to drive. Measuring HTTP would mean building a host harness, and the number
   would describe the host's transport, not the engine. The host needs its own load test against
   *this* library, with its own SLO. If you want one here, it needs a harness module first.

4. **No soak.** Every test here completes in seconds. Memory growth, connection-pool exhaustion,
   cache unboundedness and file-descriptor leaks only appear over hours or days. The idempotency
   stores are explicitly TTL-bounded; that bounding has never been observed over a real TTL.

5. **No clock or randomness injection at the system level.** Tests choose their instants. A real
   cluster has NTP drift, a leap second, and a clock that jumps backwards — untested.

6. **Chaos testing finds the failures you thought of.** These tests encode failure modes someone
   already reasoned about. They are strong evidence against *those* modes and almost no evidence
   about the one nobody has imagined yet. That is the honest ceiling of this technique, and the
   reason production history is still the only real proof.

## Recommended order of work

1. PostgreSQL transaction rollback under Testcontainers — closes gap 1, the largest.
2. A host harness module, then a k6 load test against it — closes gap 3, and gives the host a
   reproducible number.
3. A soak profile: run the idempotency and rating-claim stores past their TTL and assert they
   actually shrink — closes gap 4.

None of the three changes production code. All three change what we can honestly claim.