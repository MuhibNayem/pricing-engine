# System Architect Brief — Aequitas Pricing Engine

**Purpose.** This is a *decision brief*, not reference material. It is written to answer one
question: **what does adopting this cost us, and what does being wrong about it cost us?** Reference
documentation is linked at the end; nothing here duplicates it.

---

## The ask

1. Read this brief.
2. Run the verification commands in [§8](#8-verify-it-yourself) — do not take our numbers on trust.
3. Tell us what would have to be true for you to reject it.

---

## Summary

> Aequitas is a pricing, metering and rating engine with **zero mandatory infrastructure**. It runs on
> the PostgreSQL database you already operate. It starts no threads, schedules no jobs, and opens no
> connections of its own. Every integration point — identity, timers, message transport, payment
> execution — is an interface we define and you implement. We own the money arithmetic. You own
> everything that touches your network.

Everything below is evidence for that paragraph.

---

## 1. It adds no infrastructure to your stack

This is the first question because it eliminates most of the others.

| Question | Answer |
|---|---|
| New datastore? | **No.** PostgreSQL only, and only if you use the persistence module. |
| New cache tier? | **No.** Zero references to Redis, Caffeine, Lettuce, Jedis, Hazelcast or EhCache in any POM. |
| New broker? | **No.** Message transport is your SPI. |
| New runtime process? | **No.** |
| Forced Spring dependency? | **No.** `pricing-engine-core` has **zero runtime dependencies**; `pricing-engine-metering` adds only an *optional* Jackson. Spring arrives only in `pricing-engine-spring-boot-starter`, which you may omit. |

A library that forces a datastore into your architecture is a decision you have to live with for the
life of the dependency. This one has nothing to live with.

---

## 2. Architectural boundaries — what we deliberately do not do

These are not gaps. Each is a boundary drawn so the engine does not fight your topology.

| Concern | Owner | Why not ours |
|---|---|---|
| Identity / tenant resolution | **Host** (`TenantResolver`) | An auth filter chain inside a library bypasses your security context and cannot be audited. |
| Timers (3) | **Host** | Outbox dispatcher, FX-rate refresh, retention. A library that schedules its own tasks fights your autoscaling and your shutdown sequence. |
| Message transport | **Host** | Topic naming and delivery semantics are deployment decisions. |
| Payment execution | **Host** (`PaymentProcessor`) | Moving money is never a library's call. |
| Retention actions, CoA mapping, AP email, geolocation | **Host** | Data you own, mapped to systems you own. |

**26 SPI seams** (20 in core, 6 in metering) carry these. We never learned your Redis.

The engine is also deliberately **not a service**. The ~25 REST endpoints exist only because a Spring
Boot starter needs something to wire; underneath, it is plain Java objects you can call directly.

---

## 3. Where money correctness lives

Not in a cache, and not in an application-level lock.

Correctness is enforced by the **database**, which is the strongest evidence available:

- `idempotency_keys` — `PRIMARY KEY (tenant_id, idem_key)`. The atomic claim *is* the constraint.
- PostgreSQL-only trigger guards (`V17`) — forbid mutating `(tenant_id, idem_key)` on update, and
  forbid changing a fingerprint while the claim is unexpired.
- `rating_claims` — `PRIMARY KEY (tenant_id, claim_key)`, `CHECK (amount >= 0)`.
- Content fingerprints (`V21`) — a reused key with *different* content is refused as a conflict rather
  than silently swallowed as a duplicate.

Every Flyway migration is additive and readable SQL you can inspect, keep, or drop independently.

This is verified against a real database, not mocked: `PostgresTransactionChaosTest` runs against
`postgres:17-alpine` and kills backends mid-transaction, forces deadlocks, drives SERIALIZABLE
isolation until SQLSTATE 40001 surfaces, and asserts that 32 concurrent threads produce 32 gapless
invoice numbers under real `SELECT … FOR UPDATE`.

---

## 4. Evidence

**Measured on a developer laptop. Treat these as floors, not ceilings.**

| Property | Result |
|---|---|
| Test suite | 775 tests, 0 failures, 0 errors |
| Mutation testing (PIT 1.30.0) | 89% of 130 mutants killed; `Money` 84%; line coverage 94% |
| Chaos — real PostgreSQL 17 | `pg_terminate_backend` mid-transaction, deadlock, SERIALIZABLE 40001, 32→32 gapless numbering |
| Chaos — outbox | 50 redeliveries produce 1 event; same-id/different-content refused; 64-thread enqueue produces 1 event |
| Chaos — invoice finalization | number released on failed write; no half-finalized invoice; migrated numbers never rewind |
| Property-based (jqwik) | 23 properties over generated money — conservation, rounding idempotence, comparison totality |
| Engine throughput | 55,390 evaluations/s, p50 19 µs, p99 1,716 µs |
| HTTP (full host harness) | 30,353 requests, all 200, p99 20 ms, max 127 ms |
| Soak | 50,000 operations, 8 threads, 2.7 simulated years |

Mutation testing is the load-bearing one. It does not ask whether the tests pass — it asks whether
the tests would *notice* if the code were wrong. A passing suite proves little on its own; 89% of
deliberately injected defects were caught.

---

## 5. Known gaps

Stated before you find them.

| Gap | Actual impact | Status |
|---|---|---|
| Two in-memory stores sweep expired entries in O(n) per operation | ~2,300 claims/sec ceiling at the 100k default cap, degrading rather than plateauing. **Single-node only** — the JDBC stores have no such limit. | Documented. Fix scoped: read-time expiry + bounded sweep. |
| No network fault injection | Database faults are tested against real PostgreSQL; we do not yet sever a live TCP connection mid-request (Toxiproxy is the intended tool). | Not started. |
| No rate limiting or load shedding | Deliberately a gateway concern. The parity report marks this `PARTIAL`. | By design. |
| Outbox delivery | The outbox guarantees a **committed event cannot be lost from the database**. Publishing it is your dispatcher's job. | By design — but hear it now rather than assume "transactional outbox" implies we run the loop. |

The O(n) sweep is the only one that is a genuine performance ceiling rather than a boundary. It is
documented rather than hidden, and it does not affect clustered deployments.

---

## 6. What we need from your side

Nothing below is a hidden requirement. If it is not in this table, it is ours.

| Provided by you | Detail |
|---|---|
| `TenantResolver` | Maps the current request to a tenant. We do not read security contexts. |
| Three scheduled tasks | Outbox dispatcher, FX-rate refresh, retention. Your scheduler, your cadence. |
| Message transport | Publish/subscribe abstraction; we never open a connection. |
| `PaymentProcessor` | Money movement. |
| PostgreSQL | Connection and credentials, if you use the persistence module. |
| CoA mapping, AP email, geolocation | Your mapping, your providers. |

---

## 7. Exit strategy

**The question that should decide this: if we are wrong, what does unwinding cost you?**

- Delete the Maven dependency.
- **Keep your schema.** We do not own your tables. The migrations are additive SQL you can keep
  running or drop on your own schedule.
- **Keep your adapters.** Every SPI implementation is code you wrote. Removing us does not remove it.
- No background threads to stop, no ports to close, no jobs to deregister.

There is no migration to reverse because we never wrote into a system you depend on. This is a
reversible bet, not a commitment.

---

## 8. Verify it yourself

Do not trust §4. Run these.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home

# Full suite, offline. Excludes load/soak by design (see pom.xml comment).
mvn -B -ntp -o clean test

# Mutation testing — asks whether the tests would catch injected defects.
# Not bound to a phase; must be invoked deliberately.
mvn -pl pricing-engine-core pitest:mutationCoverage

# Chaos against a real PostgreSQL 17 container. Requires Docker.
mvn -pl pricing-engine-persistence test -Dtest=PostgresTransactionChaosTest

# Throughput and HTTP load profiles (excluded from the default build).
mvn -pl pricing-engine-core test -Dtest=PricingEngineLoadTest -Dsurefire.excludedGroups=
mvn -pl pricing-engine-metering test -Dtest=SoakTest -Dsurefire.excludedGroups=

# The claim in §1 — no forced datastore anywhere in the dependency graph.
grep -ri 'redis\|caffeine\|lettuce\|jedis' --include=pom.xml .
```

That last one returns nothing, and it should.

---

## Where to read more

| Document | For |
|---|---|
| [System Architecture Specification](SYSTEM_ARCHITECTURE_SPECIFICATION.md) | Component topology, evaluation DAG, ER schema, concurrency model |
| [Developer Runbook](DEVELOPER_RUNBOOK.md) | Operating it: configuration, deployment, troubleshooting |
| [Resilience Testing](RESILIENCE_TESTING.md) | What the chaos, property, load and soak suites prove — and what they cannot |
| [Enterprise Readiness Evaluation](ENTERPRISE_READINESS_EVALUATION.md) | Readiness scorecard and high-scale production checklist |
| [Enterprise Parity Report](ENTERPRISE_PARITY_REPORT.md) | Item-by-item comparison against commercial billing platforms |
| [architecture-overview.html](architecture-overview.html) | Visual walkthrough |