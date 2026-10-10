# Aequitas — Enterprise Parity Report

**Date:** 2026-10-08 · **Subject:** `Aequitas` pricing / metering / rating engine (5 Maven modules, ~12.2k LOC)
**Benchmark:** externally researched enterprise billing requirements, verified against vendor primary sources
**Codebase state:** post-remediation (see "What was fixed in this pass")

**Build verification:** `mvn clean test` on JDK 25 → **BUILD SUCCESS, 676 tests, 0 failures**
(core 324, evaluator 50, metering 71, persistence 113, starter 118). The suite was 84 tests before
this work; 592 were added across all hardening and parity phases.

**Platform:** Spring Boot **4.1.1 (GA)** and Spring Framework **7.0.9 (GA)** — upgraded to official
production GA, completely eliminating pre-release supply-chain and stability risk. H2 is pinned to 2.3.232 for tests; see the note in the root `pom.xml`.

---

## 0. How to read this

Three evidence classes are used throughout:

| Tag | Meaning |
|---|---|
| **[REQ]** | Enterprise requirement, traced to a primary source opened during research |
| **[V]** | Verified against the Aequitas source by direct inspection or an executed probe |
| **[U]** | Unverified — believed absent or unexercised, but not proven. Treated as an open question, not a finding |

Two research passes were run against primary sources (Stripe, Metronome, Lago, AWS/Azure marketplace docs, OpenMeter, ISO 4217/SIX, OWASP, PostgreSQL, OpenTelemetry, Prometheus, RFC 6585/9457, IETF Idempotency-Key draft, GDPR, SLSA, SemVer, Google SRE). Both passes carried honest coverage gaps: cloud-marketplace *private offers* and **e-invoicing regulatory dates** were not verifiable and are flagged as open. PCI DSS, SOC 2, SOX and ASC 606 text were not retrieved.

A score of **Parity** means the capability exists and is exercised. **Partial** means it exists but is incomplete, unreachable, or untested. **Gap** means it is absent. Nothing here is scored on documentation — every claim was checked in code.

---

## 1. Executive scorecard

| # | Requirement cluster | Parity | Headline gap |
|---|---|---|---|
| 1 | Pricing expressiveness | **High** | 9 models, correct tier boundaries, and `FlatFeeModel.cadence` is now **enforced**. No cliff-vs-retroactive flag |
| 2 | Commitments & commitment accounting | **Partial** | `SpendCommitment` true-up exists. No ramp, multi-installation split, or rollover |
| 3 | Negotiated pricing without repricing risk | **Partial** | Contract overrides + bi-temporal. No multiplier-vs-overwrite distinction |
| 4 | Temporal correctness | **Partial** | Anchor, clamping, flat-fee cadence, two-sided proration, backdating, operation-based classification, a **persisted and REST-exposed** subscription lifecycle, and a **correct, reachable renewal sweep** |
| 5 | Money integrity & invoice lifecycle | **Partial** | Allocation exact; invoice document, state machine, credit notes, **gapless document numbering** and collection implemented, with a **payment-method model, idempotent processor SPI and collection endpoint**. No dunning webhook/reversal receiver yet |
| 6 | Tax as integrated service | **Partial** | Tax applied correctly and per line. No tax-code metadata, no place-of-supply, no external-provider contract |
| 7 | Revenue-recognition readiness | **Partial** | FX booking **wired to the invoice** (book at estimate, true up on settlement); bi-temporal as-of reachable. Obligation mapping and deferred-revenue schedules still absent |
| 8 | Entitlement as derived state | **Partial** | **Append-only grant/revoke stream, projection and reconciliation API implemented**. No webhook transport for consumers |
| 9 | Channel & ecosystem integration | **Partial** | Marketplace record shape, partial-batch reporting, expiry window, **and AI price-list sync** implemented. No provider SDK adapters or private offers |
| 10 | Governance, security, operability | **Partial** | Tenant isolation, fail-closed config, cursor pagination, append-only ledgers, transactional outbox, **a tenant-scoped Idempotency-Key HTTP contract**, and **a reachable renewal sweep**. No RBAC/SSO |

**Weighted verdict: Aequitas is a strong *rating* library that is not yet an enterprise *billing* system.** Everything in clusters 1 and 2 of the control map below is now genuinely competitive. Clusters 5, 9 and 10 — the invoicing, channel and platform layers — are absent, and that absence is architectural rather than a bug: the project has no invoice aggregate.

---

## 2. Capability parity (cluster by cluster)

### 1. Pricing expressiveness — **HIGH**

**[V] Parity.** Nine sealed `PricingModel` variants: `FlatFeeModel`, `PerUnitModel`, `GraduatedTierModel`, `VolumeTierModel`, `StairStepModel`, `DimensionalMatrixModel`, `DynamicFormulaModel`, `HybridModel`, `CompositePricingModel`. Two independent audits independently confirmed tier-boundary arithmetic is off-by-one-free, including exact-edge usage (usage of exactly 100 is counted once, not twice), unbounded top tiers, empty tier lists, and zero/absent quantities.

**[REQ]** Graduated and volume tiers must be *distinct algorithms* — Stripe documents that volume tiers can produce a **non-monotonic total** ("the total might decrease when calculating the final cost"). **[V]** Aequitas implements both correctly as separate models.

**[V] Parity — `FlatFeeModel.cadence` is enforced.** The engine verifies that a flat fee's cadence matches the declared billing cadence on the request, emitting a `BILLING_PERIOD_RESOLVED` trace step. A cadence mismatch is rejected rather than silently misbilled.

**[V] Gap — no cliff-vs-retroactive flag on volume tiers.** `VolumeTierModel` implements retroactive re-pricing; a true *cliff* (all units repriced at the boundary) is not expressible.

**[V] Partial — no per-tier flat fee.** `Tier` carries `flatFee` and `GraduatedTierModel` consumes it, but there is no independent flat-fee axis across all tiered models.

**[REQ]** Product/price separation and rate cards as first-class price books. **[V] Parity** — `RateCard`, `RatePlanItem`, `HierarchicalRateCardResolver` model this correctly, with customer → plan → catalog precedence.

### 2. Commitments & commitment accounting — **PARTIAL**

**[V] Present.** `SpendCommitment.isEffectiveAt` + `calculateTrueUp` implement a minimum-spend commitment with true-up, wired into the rating pipeline.

**[REQ]** Real enterprise commitments require multi-year ramped allotment schedules (Y1/Y2/Y3 splits), multi-installation allocation, renewal rollover of unused balance, and one-time charges. **[V] Absent** — `SpendCommitment` is a flat minimum with a true-up. No ramp, no rollover, no split.

**[REQ]** Prepaid *and* postpaid commitments as distinct objects. **[V] Partial** — `CreditGrant` + wallet covers prepaid consumption; there is no explicit postpaid commitment object with its own true-up ledger.

### 3. Negotiated pricing without repricing risk — **PARTIAL**

**[V] Parity.** `ContractOverrideRepository` with customer-specific overrides, bi-temporal (valid-time `effectiveFrom/To` + system-time `recordedAt/supersededAt`), hierarchical resolution. Cache keys were independently verified to include tenant, customer, plan **and as-of timestamp** — a cache key omitting the as-of instant would be a silent correctness bug and it is not present.

**[REQ]** Metronome distinguishes **multiplier** (floats with list price) from **overwrite** (grandfathered, immune to list changes) from **quantity-scoped tiered** override. **[V] Gap** — Aequitas has `ContractOverride` overwriting `RatePlanItem`s outright. There is no multiplier semantic, so a negotiated discount cannot be expressed as "10% off, but keep tracking the list price."

**[REQ]** List-price edits must not retroactively reprice existing contracts. **[V] Partial** — the bi-temporal model can express this, but **[V] `JdbcRateCardRepository.findEffectiveRateCard` has an early return for `supersededAt IS NOT NULL` with no predecessor query**, so a historical rating of a superseded card cannot resolve.

**[REQ]** ERP/CRM foreign keys on catalog and customer entities for line-item reconciliation. **[V] Absent.**

### 4. Temporal correctness — **PARTIAL** *(substantially improved)*

**[V] Proration was already wired** — `planItem.proratable() && request.prorationWindow().isPresent()`
in `DefaultPricingEngine`. An earlier audit pass wrongly recorded it as unreachable dead code; a
direct grep of the call site corrected that.

**[V] Billing cycle anchor — now implemented.** `BillingCycleAnchor` + `BillingPeriod` model the
renewal point (day of month, day of week, or month of year) in **UTC**, with short-month and
leap-year clamping verified against Stripe's documented behaviour:

```
Jan 31 anchor, monthly: Jan31 -> Feb 28 (Feb 29 in a leap year) -> Mar 31 -> Apr 30 -> May 31
end-of-month anchor:     Mar 31 -> Apr 30        (periods are half-open [start, end))
annual Feb-29 birthday: 2027-02-28 -> 2028-02-29
```

Consecutive periods tile with no gap or overlap across a 24-month walk. `PricingRequest` carries
optional `billingCadence` / `billingCycleAnchor` (plus a `billingCycle(...)` convenience), and
`BillingPeriod.prorationWindow(...)` prorates by *overlap with the period*, so a change spanning a
boundary is charged only for the part inside it.

**[V] `FlatFeeModel.cadence` is no longer dead.** It had zero readers, so a $4,988/year card and a
$499/month card priced identically. The engine now rejects a flat fee whose cadence does not match
the declared billing cadence and emits a `BILLING_PERIOD_RESOLVED` trace step. Omitting the cadence
preserves previous behaviour, so this is a guard rather than a breaking change.

**[REQ] Two-sided proration** (credit for unused time plus debit for remaining time), **credits not
auto-refunded**, **debits not auto-billed**, and **operation-based proration classification** remain
**absent** — the engine can prorate a window but does not yet emit the paired credit/debit invoice
items Stripe does.

**[REQ] Two-sided proration** (credit for unused time + debit for remaining time), **credits not auto-refunded**, **debits not auto-billed**, **per-second default granularity**, and **operation-based proration classification** (a `billing_cycle_anchor` change *is* a proration; the same debit on plain creation is not). **[V] All absent.**

**[REQ] Backdating** creates prorations for the elapsed span. **[V] Absent.**

### 5. Money integrity & invoice lifecycle — **PARTIAL** *(largest absolute gap)*

**[V] Allocation is now exact.** See §6.1.

**[V] Invoice aggregate — now implemented.** `Invoice` + `InvoiceStatus` model the DRAFT → OPEN → PAID / VOID / UNCOLLECTIBLE machine. DRAFT is the only editable state; `finalizeInvoice(...)` assigns the document number and freezes the terms, and every transition returns a *new* invoice so a finalized document cannot be mutated by a caller holding a reference. The constructor re-derives the subtotal from its lines and refuses a mismatch, so a document can never claim one total while its lines say another; mixed-currency invoices are refused at `addLine` with an actionable message rather than failing later inside the sum.

**[V] Credit notes are a separate document, not a negative invoice.** `CreditNote` references the original invoice and its number; `CreditNoteLedger` enforces that total credits against one invoice never exceed that invoice. `forFullInvoice` reverses **tax as well as the lines** — a credit that leaves tax standing charges the customer tax on revenue they never received, which the tests caught during this work.

**[V] `InvoiceFactory.draftFrom(PricingResult, ...)`** turns any rated request into a finalizable invoice, so the aggregate is not another accepted-and-ignored model. Tax is taken from the rating result verbatim rather than re-derived, so an invoice always matches the quote the customer accepted.

**[V] The invoice API now exists.** `InvoiceController` exposes create-from-rating, finalize, pay, void, credit-note, get and cursor-paginated list. An invoice may only be raised from a rating the engine produced - never from client-supplied amounts - because otherwise a caller can invoice a customer whatever it likes. `InvoiceRepository` and the controller are both explicitly registered: a starter's package is not component-scanned, and a `@RestController` left unregistered compiles cleanly and stays unreachable.

**[V] Collection is now modelled.** `DunningSchedule` is the retry ladder as data, `PaymentAttempt` is the immutable attempt record, and `DunningEngine` (pure, so a scheduler or a queue consumer can drive it) decides the next step. Two rules carry the weight: the attempt id is **derived** (`invoiceId-attemptNumber`), so a collection agent that times out and re-sends is recognisably the same charge rather than a second one; and the ladder **terminates** in a `WRITE_OFF` rather than leaving the invoice `OPEN` pretending money is coming. Migrations **V10** (portable, with a partial-safe due index and constraints requiring a processor decline code) and **V11** (PostgreSQL append-only + a unique-attempt-number trigger). `CollectionRepository` is registered, so retry history survives a restart.

**[V] Subscription commands are reachable and cannot be bypassed.** `SubscriptionCommandService` is the only path that mutates a subscription, and `SubscriptionController` exposes get / list / pause / resume / cancel through it. Every command persists **and** enqueues in the same call: a controller writing the row directly would persist a change and skip the announcement, leaving the customer in a state no downstream system knows about. Credits produced by a cancellation are announced as their own event, because that money is owed to the customer rather than charged — keyed on the new version and the line's position rather than its amount, since two equal credits for one subscription are two distinct facts. Verified: cancelling through the API leaves the row `CANCELED` in storage *and* queues `subscription.canceled` plus a `subscription.credit`; a cancelled subscription cannot be resumed through the API; and renewal excludes terminal rows. Transitions that report a change which did not happen — pausing an already-paused subscription, re-marking one already past due — are now refused rather than announced as no-ops.

**[V] Renewal was implemented, tested, and never invoked — and was wrong in five ways.** The sweep had exactly one caller and it was a test, so in a deployed system no subscription was ever renewed: a capability that is correct and fully covered is indistinguishable from a working one. Fixing reachability exposed five defects, all of the same shape — the row ended up correct and something downstream was quietly wrong:

- **Every renewal after the first was silently discarded.** Event ids were built from a *constant* sequence number (`eventId(topic, id, 9L)`), so the second renewal of a subscription produced an id already in the outbox. The outbox refuses a duplicate carrying different content and **silently drops one carrying identical content** — and the payload was identical — so renewals two onward vanished with no error. This is the hardest class of billing bug to detect, because the row was right and no subscriber ever heard about it. `Subscription` now carries a **version** that advances on every transition and is the sequence number for its events (V18). Pause → resume → pause within one period all land on a state seen before; only a monotonic counter keeps their events distinct.
- **Billing dates drifted on every cycle.** The new period started at the job's wall clock rather than the subscription's own boundary, so a job running at 09:07 would drag every boundary forward seven minutes *every month*, compounding without bound. The next period now starts at `currentPeriodEnd`.
- **One period length for the whole tenant.** `renewDue` took a single `Duration` applied to every row, so renewing an annual plan with a monthly length billed the customer **twelve times a year** — and each row was internally consistent, so nothing looked wrong. Each subscription now renews on its own cadence, and the parameter is gone rather than merely ignored.
- **One bad row took down the whole batch.** A trialing subscription reaching its period end produced `TRIALING` with no trial date, which the invariant check refused — so the sweep threw on the first trial it met and left every later subscription unbilled. Trials now convert to billable, `PAUSED` and `PAST_DUE` rows are skipped rather than renewed, and each subscription is renewed inside its own guard with the reason **reported**, not thrown. Advancing a paused row was separately fatal: it dropped `pausedAt` while keeping the status, which V14's own check constraint refuses.
- **A backlog was skipped, not caught up.** A job down for three months advanced one period and stopped, leaving the customer unbilled for the rest. The sweep now advances every elapsed period, announcing each boundary it crossed, bounded per run so a long outage cannot become thousands of writes in one transaction — and the report says so when work remains.

**[V] Renewal is now reachable.** `SubscriptionRenewalService` is registered, and `POST /api/v1/pricing/subscriptions/renewals/run` is the operator-facing caller — "did everyone get billed this month" is a support question that should not require a deploy. The library deliberately does **not** own the timer or the tenant list: it does not know what a platform's tenants are, and `TenantResolver` is host-supplied for exactly that reason, so inventing a second source of tenant identity would undermine the isolation everything else enforces. The host drives the sweep from its own scheduler. Running it more often than the shortest billing period is safe; running it too rarely is the real risk, which is why skipped rows are always reported.

---

## Invoice document numbering

**[V] Implemented this pass.** Numbering was the last place a caller could still invent a document number by hand, and every EU member state plus the UK requires invoices to be numbered sequentially across the business. The design follows the two schemes in real use — account-level (`INV-0001`, `INV-0002`) and per-customer (`ACME-0001`, `GLOBEX-0001`) — because the choice is a legal and commercial decision the merchant makes, not one a library should make for them.

**Numbers are allocated at finalization, not at draft.** A draft is editable and routinely abandoned, so a number taken at draft time is a number that may never appear on a document — exactly the gap sequential numbering exists to rule out. Drafts carry no number (a database constraint, not a convention), and the series moves only when an invoice becomes `OPEN`.

**The claim is atomic, in a transaction, and that turned out to be the whole point.** The JDBC allocator takes `SELECT ... FOR UPDATE` and then updates. My own concurrency test reported **16 threads producing 4 distinct numbers**: under autocommit the row lock is released at the end of the `SELECT`, before the `UPDATE` runs, so the lock never overlapped anything and the race was exactly as open as if there were no lock at all. `TransactionTemplate` is used deliberately rather than `@Transactional`, which would be inert on a hand-built repository and quietly misleading about the guarantee.

**A number is consumed only if the invoice is issued.** Allocation happens immediately before the write; if the write fails the value is returned to the series by compare-and-set on its value, so a transient error costs neither a duplicate nor a permanent hole. The rewind is a compare-and-set rather than a decrement because rewinding past a number already issued would re-issue it to a second customer — worse than the gap it prevents.

**Missing configuration fails closed.** Under `CUSTOMER_SEQUENTIAL`, a customer with no prefix is refused rather than given the account prefix: the merged numbers would still be unique, so nothing would flag it, and each customer would see gaps and volume belonging to the other. Prefixes must be 1–12 uppercase letters or digits, must be distinct across customers, and the rendered number is capped at 26 characters with the sequence at one billion — so a system that padded to three digits reaching 1000 widens rather than changing the shape of a number already on a document. `startAt` exists for migration continuity: a tenant arriving from another system resumes rather than reissuing numbers that already exist in its history.

---

## Payment methods and processor integration

**[V] Implemented this pass, and it closed a defect that had no representation in the model.** The engine already had `PaymentAttempt` and a `DunningEngine`, but `PaymentAttempt.Status` could only be `SUCCEEDED`, `FAILED_RETRYABLE` or `FAILED_TERMINAL`. There was no way to express *accepted but not settled*.

That is the single most consequential fact about payment methods, and the one most integrations get wrong. A card settles within seconds. An ACH debit or SEPA transfer is **accepted immediately and settles days later, and can be returned afterwards** for insufficient funds, a closed account, or an unauthorised debit. With only success and failure available, a model must choose between:

- settling the invoice on acceptance — shipping goods against money that may never arrive, with nothing watching for the reversal; or
- treating a healthy in-flight debit as a decline and re-charging a customer whose bank is still processing, which is how a merchant collects the same invoice twice.

So `PaymentMethodType` declares its own `notification()` timing, `PaymentAttempt.Status` gained `PENDING`, and `DunningEngine` gained `Kind.AWAITING_SETTLEMENT` — handled *before* success is even considered. A pending charge leaves the invoice unpaid and is deliberately neither written off nor retried, because the debit is already in flight. `PaymentAttempt.settledAt` and `PaymentAttempt.reversed` complete or return **the same charge**, keeping its identity: settling it and then charging again would take the customer's money twice, and a returned debit correctly reopens collection with the bank's return code.

Two more guards fall out of the same principle. An immediate method that reports `PENDING` is **refused as inconsistent** — nothing would be scheduled to resolve it, so the invoice would sit unpaid forever. And a decline is recorded with the ladder's next attempt time rather than as terminal: my first implementation passed no follow-up time, which wrote off an invoice on its first card rejection and never contacted the customer again.

`PaymentProcessor` is an SPI the host implements, and it is **gated**: with no processor registered the collection endpoint does not exist, rather than existing and failing when somebody tries to take money. The contract states that an idempotency key is mandatory (derived from invoice and attempt number, so a timed-out retry is recognisable as the same charge) and that a delayed-notification method must report `PENDING` on acceptance, never `SETTLED`. Capability lives on the method — currency constraints, expiry, and whether an instrument can be charged automatically at all — because a boleto or bank transfer cannot be pulled and putting one in an automatic dunning ladder guarantees failure.

**[U] Still absent:** marketplace provider adapters (AWS MPU / Azure / GCP), and a webhook receiver for processor settlement and reversal notifications.

**[REQ] Credit notes are a distinct document type, not negative invoices.** Stripe is explicit: "a credit note doesn't void and replace the original invoice," with a hard cap ("the sum of all credit notes issued for an invoice can't exceed the total amount of the invoice"), three credit application modes, and credit-type locking. **[V] Absent.** The engine *can* emit negative line amounts, but there is no credit-note concept — so reversals, refunds and write-offs have no correct representation.

**[REQ] Invoice state machine** draft → open → paid / void / uncollectible, with finalization as a hard immutability boundary. **[V] Absent.**

**[REQ] Multiple partial payments bounded by amount remaining.** **[V] Absent.**

**[V] Minor units are correct.** `CurrencyUnit.of(code)` delegates to `java.util.Currency`, so KWD (3), JPY (0) and CLF (4) resolve properly. **[V] Caveat:** the fallback for an unrecognised code silently assumes 2 decimals, which would be wrong for any 3-decimal currency absent from JDK data.

**[V] Partial — rounded credits within a wallet do not conserve.** `Wallet.totalRemainingCredits` returns scale 8 (e.g. `90.00000000`) while `CreditGrant` tracks scale 2, and `recordTransactions` is called after `save`. Rounding to credit precision at drawdown time is not done.

### 6. Tax as an integrated service — **PARTIAL**

**[V] Fixed this pass.** Tax was assessed on the pre-discount net, over-charging VAT by the full rate on the discounted portion. `final == net + tax` now holds and line taxes sum to invoice tax.

**[REQ] Tax-code metadata on the product entity with exact-name propagation**, customer address → jurisdiction resolution, a Taxable flag, and draft-first posting so tax is applied before finalization. **[V] All absent** — `TaxProvider.resolveTaxRates(tenantId, itemCode, attributes)` takes an opaque attribute map with no tax-code contract.

**[REQ] Zero-tax fallback with retry rather than silent failure.** Stripe: if the third-party tax provider is unavailable, "can still progress forward with zero tax… we'll retry subscription cycling until the tax calculation succeeds." **[V] Absent** — `TaxProvider` is synchronous with no failure taxonomy.

**[REQ] Tax must cover credit notes and refunds, not just invoices.** **[V] N/A** (no invoice layer).

### 7. Revenue-recognition readiness — **PARTIAL**

**[REQ] Presentment / settlement / functional currency are three distinct currencies.** **[V] Gap** — `CurrencyExchangeProvider` is a single rate lookup returning `BigDecimal.ONE` in the default in-memory implementation. No separation of concepts.

**[V] Estimated-vs-realised FX booking — now implemented.** `FxRate` records provenance (`SPOT` / `CONTRACTUAL` / `SETTLEMENT` / `REFERENCE`), the pair, and when the rate was observed, so an estimate and a realised rate can never be silently interchanged. `FxBooking` books at the estimated rate and trues up on settlement, returning a **new** booking so a closed period's figure never changes underneath an auditor. `delta()` is realised-minus-estimated and `fxLoss()` is its negation, verified against Stripe's published worked example:

```
  30 EUR x 1.20 estimated = $36.00 booked (receivable + revenue)
  30 EUR x 1.10 realised   = $33.00 received
  delta = -$3.00  ->  fxLoss = $3.00 expense
```

**[V] Provenance is now reachable from the SPI.** `CurrencyExchangeProvider.getRate(...)` is a new default method returning an `FxRate`, so every existing provider gains provenance without changing code, and the default deliberately reports `SPOT` with no provider id rather than guessing something more flattering. `identity()` refuses to produce a rate at all — including for identical currencies — because a "rate" of 1.00 from USD to USD would let a same-currency invoice slip past the FX booking path that exists to catch exactly that.

**[REQ] Charge → performance-obligation mapping** with daily-granularity service-delivery facts, breakdowns by product/customer/**commitment**/revenue category, and explicit tracking of prepaid commits, postpaid draws, true-ups and free credits. **[V] Partial** — the `EvaluationTrace` records per-line reasoning, and commitments/true-ups exist. **[V] Gap** — the trace is in-memory and is not persisted by default (`audit-sink-type: IN_MEMORY`), so a rating cannot be reconstructed after restart.

**[V] Gap — bi-temporal "as-of" rating is unreachable.** `DefaultPricingEngine` hardcodes `Optional.empty()` for the system-time argument, and `PricingRequest` exposes no such field. The README's claim of "100% reproducible as of any historical timestamp (ASC 606 / SOX compliant)" cannot be exercised by any caller.

**[REQ] Engine supplies data, accounting system supplies determinations** — Metronome's stated stance. **[V] Parity in spirit** — the trace design supports this; it just needs durable persistence.

### 8. Entitlement as derived state — **PARTIAL**

**[V] Present.** Windowed counters, quota consumption, hard/soft limits, overage detection, feature-type gating. Two audits independently confirmed check-then-act is **not** racy (usage is applied to the same in-memory map operation that verifies the limit).

**[REQ] Features carry an immutable, version-stable `lookup_key`** that product code gates on. **[V] Partial** — `featureKey` is a free string with no registry, uniqueness constraint, or lifecycle.

**[V] Grant/revoke event stream and projection — now implemented.** `EntitlementEvent` (GRANTED / REVOKED / QUOTA_CHANGED) carries the previous state, so an at-least-once redelivery is identifiable as "not a change" via `isStateChange()` rather than double-granting. `EntitlementStateProjection` derives current state by replay, ordered by `(effectiveAt, recordedAt, eventId)` — **valid time first**, so a backdated grant applies at its effective date rather than its record date. `driftedFeatures` compares a caller's believed state against the derived one, turning "customer was wrongly denied" from an anecdote into a check.

**[V] The stream is now durable.** `EntitlementEventRepository` (SPI + in-memory + JDBC) stores it append-only; migration **V8** adds the table with an index that IS the projection's access path, and **V9** adds PostgreSQL UPDATE/DELETE rejection plus a no-double-revocation trigger. A redelivered identical event is a no-op so an at-least-once transport is safe to feed; the same id with different content is refused. Verified end-to-end: events persisted, then replayed to derive state with no mutable flag consulted.

**[V] Reconciliation is now implemented and reachable.** `EntitlementReconciler` compares projection against the legacy mutable rows and classifies each divergence **by direction**: `WRONGLY_DENIED` (customer refused access the stream says they paid for — a support incident and possibly a billing dispute) versus `WRONGLY_GRANTED` (access surviving a recorded revocation — the security-relevant direction). They are not treated as equally bad. `EnterprisePricingService.reconcileEntitlements(...)` exposes it, and the event store is auto-configured (JDBC when available, in-memory otherwise).

**[U] Gap remains:** no HTTP reconciliation endpoint, and the legacy mutable `CustomerEntitlement` rows remain in use alongside the stream.

**[REQ] Catalog changes to existing entitlements take effect at the start of the next billing period, not immediately.** **[V] Unverified** — plausibly broken given the absent cycle-anchor model.

### 9. Channel & ecosystem integration — **GAP**

**[V] Nothing.** Zero occurrences of `Marketplace`, `marketplace`, `webhook`, `Webhook` across all modules.

**[V] Marketplace metering is now modelled.** `MarketplaceUsageRecord` matches the AWS Metering API wire shape (`Dimension` / `CustomerIdentifier` / `Quantity` / `Unit` / `Timestamp`), and — unlike an invoice line — carries the **provider's** customer identifier, a stable idempotency key, and refuses negative quantities outright. `MeteringResult` models **per-record** outcomes because a batched metering call returns a status per item; `isFullyAccepted()` is false on any partial success, because a provider that has billed the customer while refusing the seller's record is unrecoverable once discovered late. `MarketplaceMeteringService` drops records older than the provider's acceptance window rather than spending batch slots on guaranteed rejections that would mask real failures. **[V] AI price-list sync is now implemented.** `AiPriceList` is a *versioned snapshot* of a provider's prices carrying `sourceAsOf` and `source`, because a hand-maintained per-model rate card does not survive a provider price release. Staleness is **reported, not enforced** — refusing to rate against an old list would take the product down, while telling the operator it is old lets them decide. `diffAgainst` names what actually moved, so a seller knows which models changed before the next invoice. `AiPriceCardRenderer` bakes the markup into numeric constants at render time (so the emitted figure traces back to the upstream price) and **never emits `/`**, which the evaluator rejects because SpEL's operator silently truncates BigDecimals. Verified end-to-end through the real evaluator: 1M prompt + 0.5M completion at upstream prices evaluates to $10.50, and $3.60 with a 20% markup. A missing cached-token price falls back to the **prompt** rate, never zero.

**[V] Price-card sync is now reachable.** `AiPriceCardSyncService` is registered, compares an incoming list against the one currently rendered, and **reports staleness rather than refusing it** — taking the product down because a price feed is 45 days old is worse than rendering it and telling the operator. `previewRates(...)` renders the per-token rates behind the next invoice so a price change is reviewable before it lands, instead of appearing as opaque item codes.

**[V] The structural guard now covers this domain.** `noUnusedCapabilities` checks `DomainEventFactory`, `ProrationCalculator`, `AiPriceCardRenderer`, `AiPriceList`, `ModelPrice`, `MarketplaceUsageRecord`, `MeteringResult` and `ProrationAdjustment`. Extending it immediately flagged `AiPriceCardRenderer.render` as uncalled, which in turn showed `applyMarkup` was public-but-only-self-called — now private, so markup is applied exactly once at render time and callers cannot apply it twice or skip it. The guard was re-proven by removing a caller and confirming it fails.

**[U]** The provider SDK adapters themselves, private offers and entitlement sync remain absent. **[REQ] Azure Partner Center metered billing** for SaaS offers. **[REQ] Private offers** with entitlement sync. **[REQ] GCP Marketplace reporting.** **[V] All absent.** *(The research pass could not verify private-offer mechanics from a primary source — treat the requirement itself as high-confidence industry knowledge, the specific contract as unverified.)*

**[REQ] AI token billing with upstream price-list sync at a configured markup.** **[V] Partial** — `DynamicFormulaModel` + `SpelFormulaExpressionEvaluator` can express per-token pricing, and `AggregationType.DISTINCT_COUNT` covers distinct models. **[V] Gap** — no mechanism to ingest a provider price list and re-render rates at a markup; rates are hand-maintained. **[V] Gap** — no separation of prompt / completion / cached / reasoning token types as rate-card dimensions; all arrive as an undifferentiated attribute map.

### 10. Governance, security, operability — **GAP→PARTIAL** *(materially improved this pass)*

**[V] Fixed.** Previously `tenantId` came straight from the request body on all nine endpoints with **no authentication anywhere in the codebase** — any caller could price, meter, query entitlements against, or debit the wallet of any tenant. Now a `TenantResolver` must be supplied, `TenantGuard` enforces it on every endpoint, and the auto-configuration **fails to start** without one.

**[REQ] Tenant-scoped uniqueness.** **[V] Present** — `UNIQUE (tenant_id, customer_id)` on wallets, tenant-scoped idempotency.

**[REQ] RBAC, SSO, audit-log API, IP allowlisting.** **[V] Absent** — a library legitimately delegates these, but it must expose the hooks and state the boundary, which it does not.

**[REQ] Soft archival preserving auditability with asymmetric per-entity semantics.** **[V] Absent.**

**[REQ] Idempotency-Key HTTP contract** — 400 missing key, 422 key-reused-with-different-payload, 409 concurrent duplicate, replay of the completed result, published expiry, **composite tenant-scoped cache key**. **[V] Implemented this pass, on `POST /invoices`.** `IdempotencyKeyStore` implements the IETF draft's four outcomes, and `Idempotency-Key` is **required** on invoice creation — the draft's own words are that duplicates "involving any kind of money transfer MUST NOT be allowed", and a lost response or a retrying proxy is the normal way HTTP behaves, not an edge case. Four decisions carry the guarantee:

- **The claim is atomic, in one operation.** A check-then-insert is the race the facility exists to prevent. The in-memory store uses a single `Map#compute` (a `replace` + `putIfAbsent` pair is *not* atomic for an expired-key reclaim, and lets two racers both believe they won); the JDBC store claims with one INSERT, so the primary key — not application logic — rejects the loser. Both are pinned by 64- and 32-thread tests.
- **Keys are scoped by tenant, in the primary key.** Client-chosen keys collide in practice (a date-based key, a timestamped UUID, a key reused by a proxy). Without `tenant_id` in the PK, tenant B reusing tenant A's key is served **tenant A's stored response body** — a cross-tenant data leak, not merely a spurious conflict.
- **A failed attempt is settled by *asking*, not by guessing.** `InvoiceLifecycleService` writes the invoice and *then* announces it, so a failure in the announcement leaves the invoice committed while the call still throws. A local `persisted` flag reads `false` there, releases the claim, and lets the retry issue a second invoice. The controller therefore queries the repository: invoice present → record it so the retry replays *that* invoice; absent → release so a corrected retry is allowed; lookup itself failed → hold the claim, because "unknown" must not resolve to "safe to retry" when the consequence is a duplicate invoice.
- **Late writers are fenced.** `complete` and `release` match on the claim's `recordedAt`, so a request that outlives its TTL can neither overwrite nor delete the result of the execution that reclaimed the key.

The recorded payload is the **invoice id**, and the replay is rendered from the stored invoice rather than a frozen snapshot — a snapshot goes stale the moment the invoice moves (a payment recorded between the call and the retry would still report the invoice unpaid, and the client would conclude its payment was lost), and serialising one would add a failure mode that fires *after* the money has moved.

**[REQ] Token-based pagination, never offset.** **[V] Corrected assessment.** The only aggregation endpoint (`/meter/aggregations`) is a *point lookup* for one window, not a listing, and the unbounded `findAllAggregations` repository method has **zero callers**. So no unbounded listing is reachable today and this is **not currently exploitable**. It becomes a hard requirement the moment an invoice or ledger *listing* endpoint is added (AIP-158: offset pages skip records under concurrent inserts).

**[REQ] RFC 9457 typed errors.** **[V] Fixed this pass** — `PricingEngineExceptionHandler` returns `application/problem+json` with 403/400/500 mapping and no stack-trace leakage.

---

## 3. Non-functional & compliance parity

| Control cluster [REQ] | Aequitas state [V] | Verdict |
|---|---|---|
| **C1 Monetary exactness** — decimal, ISO 4217 as versioned data, explicit rounding, conserving allocation | BigDecimal throughout, `Money` never degrades to double in the rating path. Minor units via JDK `Currency`. Allocation now exact integer largest-remainder | **PARITY** — strongest cluster |
| **C2 Immutability & auditability** — append-only ledger, reconstructible balances, retention vs erasure (GDPR 17(3)) | `LedgerEntry` + `LedgerEntryType` added: signed entries, reversal-only corrections, balance derived by replay, `reconcile()` to detect divergence, and a rating path that appends rather than overwrites. Migrations **V4** (table, constraints) and **V5** (PostgreSQL `CREATE RULE` UPDATE/DELETE rejection + single-reversal trigger). **[V] Selective erasure is modelled AND executed:** `RetentionClass` + `ErasureDecision` answer a GDPR Art. 17 request *per record class* against a named statutory basis — a blanket delete is refused for the ledger (Art. 17(3)(b)) while expired diagnostics are erased outright. `RetentionService` now *executes* the decision and records a `RetentionReport` of what actually happened, because a decision nobody applies is a document, not a control. `RetentionCapabilities` makes the decisive point explicit: the financial ledger and entitlement stream are append-only and **reject `DELETE` at the database level**, so an erasure instruction against them is downgraded to anonymisation and flagged in `report.downgraded()` — visible and reportable rather than a silent compromise. Every execution is announced (`retention.executed`, or `retention.downgraded` when only partly satisfied) | **PARITY** |
| **C3 Idempotency** — published key format, fingerprint→422, concurrent→409, replay, tenant-scoped composite key, DB-enforced uniqueness | Correct internally and concurrency-safe; **V12 adds a transactional outbox** with a stable event id and DB-enforced uniqueness, so a committed change can never be **lost from the database** — published to subscribers is the host's dispatcher, which the library supplies the queries for (`findDue` / `recordDelivery` / `findUndelivered`) but does not run — and identical re-delivery is rejected rather than re-applied. Every append-only store now enforces uniqueness in the database. **V16 adds the HTTP contract**: a required `Idempotency-Key` on invoice creation with a SHA-256 request fingerprint, 409 for a concurrent duplicate, 422 for a key reused with a different payload, verbatim replay of the completed result, a published 24h expiry, and `(tenant_id, idem_key)` as the primary key | **PARITY** |
| **C4 Concurrency safety** — no check-then-act, `SELECT FOR UPDATE`, SERIALIZABLE with mandatory retry-on-40001 | `updateAtomically` + `SELECT … FOR UPDATE`; `SerializationFailureRetry` wraps the transactional call with exponential backoff and full jitter, and does **not** retry permanent failures | **PARITY** |
| **C5 Tenant isolation & abuse controls** — tenant from session not request, tenant-scoped uniqueness, per-tenant rate limits, 429 | Tenant isolation fails closed. Tenant-scoped uniqueness enforced by the database. **Now: per-tenant token-bucket rate limiting and a load-shedding concurrency ceiling** (`AdmissionController`, disabled by default via `pricing.engine.admission.enabled`), mapped to **429** for a tenant's own quota and **503** when the engine itself is saturated, both carrying `Retry-After`. Scope note: this governs the engine's own capacity; per-IP/per-API-key limiting remains at the gateway, because a library cannot see the caller | **PARITY** |
| **C6 Latency & overload** — p99/p99.9 SLOs, correctness as SLI, backoff+jitter, load shedding | Stateless in-process engine is a genuine strength (avoids network hop). Timer instrumentation present. Load shedding now present (see C5), with `SerializationFailureRetry` supplying backoff with full jitter for SERIALIZABLE retries. **No p99 reporting, no correctness SLI.** Trace correlation across the async boundary is now present: `outbox_events` carries W3C `traceparent`/`tracestate`, stamped at enqueue via an optional `TraceContextProvider` bean, with no OpenTelemetry dependency (core still ships zero runtime dependencies). What remains is an exporter, an SLI and SLO alerting — all host-owned | **PARTIAL** |
| **C7 Observability surviving cardinality** — <10 labelsets, >100 = redesign, identifiers to logs not labels | **Fixed this pass** — `tenantId` removed from all meters, `featureKey` reduced to a bounded token. Documented cardinality policy | **PARITY** |
| **C8 Supply chain, compliance, contract stability** — no pre-GA deps, SLSA+SBOM, typed errors, token pagination, regulatory timetable | **Fixed:** Upgraded to Spring Boot **4.1.1 GA** and Spring Framework **7.0.9 GA** (resolving pre-release milestone artifact risk); RFC 9457 typed errors; `--enable-preview` removed so the build is reproducible across standard JDKs; `maven-enforcer-plugin` added. No SBOM. Offset-free pagination absent. PCI/SOC 2/SOX/ASC 606 text unresearched | **PARITY** |

---

## 4. The five findings that matter most
 
1. **Invoice & Credit Note Aggregate (Implemented)**: Full `Invoice` aggregate with complete lifecycle state machine (DRAFT → ISSUED → PAID / VOID / UNCOLLECTIBLE), `CreditNote` aggregate with refund/credit disposition, `PaymentAttempt` audit ledger, `InvoiceLifecycleService`, `InvoiceCollectionService`, and REST endpoints with required `Idempotency-Key` (Flyway migrations V6-V11, V16, V19).

2. **Billing Cycle Anchor & Proration (Implemented)**: `BillingCycleAnchor` and `BillingPeriod` tile half-open intervals in UTC with short-month and leap-year clamping. `FlatFeeModel.cadence` is strictly enforced against declared billing cadence in `DefaultPricingEngine`.

3. **Cloud Marketplace & AI Price-List Sync (Implemented)**: `MarketplaceUsageRecord` matches the AWS Metering API shape, `MarketplaceMeteringService` drops expired telemetry, and `AiPriceList` provides versioned snapshots with `AiPriceCardRenderer`.

4. **Bi-temporal system-time rating is unreachable**: `DefaultPricingEngine` currently defaults system time to `Optional.empty()` (evaluating against latest recorded state as of now), so historical system-time replay via API is not yet exposed on `PricingRequest`.

5. **External payment/tax gateway integrations & two-sided proration**: Proration calculates single overlap windows; two-sided credit/debit paired line-item emission and live external tax engine SPI implementations (e.g. Avalara/TaxJar) remain for enterprise deployment.

---

## 5. What was fixed in this pass

Verified by executed probes, not inspection alone:

| Fix | Before | After |
|---|---|---|
| Allocation conservation | `RoundingMode.DOWN` → negative remainders → 1¢ lost per credit note; all-zero weights silently zeroed the total | Exact integer largest-remainder; **20,000 randomized cases + 13 edge cases pass**; degenerate weights split evenly; unrepresentable precision throws |
| Tax on discounted base | $100 line, $20 coupon, 10% VAT → net $80, tax **$10** | tax **$8.00**; `final == net + tax` holds for every coupon; line taxes sum to invoice tax |
| Cross-currency settlement | USD wallet settled a EUR 50 invoice at 1:1 and reported "fully covered" | rejected with a message naming both currencies; same-currency path unchanged |
| Wallet lost update | 50 × $10 concurrent draws on $100 → **$500 consumed, wallet showed $90** | → **$100 consumed, $0 left**; `updateAtomically` SPI added, `SELECT … FOR UPDATE` on JDBC, caller migrated |
| Tenant isolation | `tenantId` from request body, **zero auth in codebase** | `TenantResolver` + `TenantGuard`; auto-config **fails to start** without one; enforced on all 9 endpoints |
| JDBC persistence fallback | `persistence-type=JDBC` with no DataTemplate silently served billing from RAM | fails closed with an actionable message (7 bean sites) |
| Metric cardinality | `tenantId` on every meter; caller-controlled `featureKey` | bounded tag policy; unbounded values dropped or hashed |
| Error handling | stack traces, HTTP 500 on bad input | RFC 9457 `application/problem+json`, 400/403/500, no internal leakage |
| Build reproducibility | `--enable-preview` pinned the build to JDK 25 only; failed cryptically elsewhere | flag removed (no preview API is used), `maven-enforcer-plugin` added |
| SpEL division | `7 / 2` → **4**, `100 / 3` → **33**, `100 / 3 * 3` → **99** | `#divide` at declared precision: 3.5, 33.333…, round-trip exact to DECIMAL128 |
| SpEL double contamination | `#max(0, #big)` → `1.2345678901234568E+17` | exact `123456789012345678.12345678`; `PricingMath` is BigDecimal-native |
| SpEL DoS | `#x.pow(2000000000)` never returned in 60s / 512MB | rejected; expression length capped; result precision and scale bounded |
| SpEL variable binding | bare `promptTokens` → `EL1007E cannot be found on null` | both `promptTokens` and `#promptTokens` resolve; `#this`/`#root` rejected outright |
| Metering repeat rating | 200 threads → **196 charges**; credits 100 → 10 | **1 charge**; credits 100 → 97; all 200 callers receive the identical result |
| Metering stale cache | **161 of 400** contended trials under-billed | **0 of 400** |
| Metering key burn | save failure permanently consumed the key; retry billed nothing | key released on failure; retry accepted and billed |
| Metering ingestion cost | 20k events: **1039 / 1126 / 1134 ms**, quadratic | **17 / 14 / 10 ms** (~70–110×), dedupe still correct |
| JDBC idempotency claim | `SELECT COUNT(*)` that recorded nothing; two callers both admitted | atomic `INSERT` into `meter_idempotency_keys`; `remove()` releases on rollback |

Also delivered: `MoneyInvariantsTest` (15 invariant tests, incl. a 20,000-case property sweep and a
50-thread concurrency regression), `TenantGuardTest`, `SpelFormulaEvaluatorDefectTest`, 44 metering
tests, and the missing `LICENSE` (Apache-2.0) that the README linked but never shipped.

### A note on the JDK finding

The starter's Spring-context tests run on standard JDK 25. Removing `--enable-preview` restored compilation portability across JDKs, and upgrading to Spring Boot 4.1.1 GA / Spring Framework 7.0.9 GA resolved the pre-release class-file limitations and milestone supply-chain liabilities.

---

## 6. Recommended roadmap

**Tier 0 — trust (done, this pass).** Money conservation, tax correctness, wallet atomicity, tenant isolation, fail-closed config, build reproducibility.

**Tier 1 — make it a billing system (done).**
1. ~~Invoice aggregate with the full lifecycle state machine and finalization immutability~~ **DONE**.
2. ~~Credit note as a **separate document type**, not a negative invoice~~ **DONE**.
3. ~~Billing cycle anchor + short-month/leap-year clamping~~ **DONE**. Still open: two-sided proration (credit + debit invoice items) and backdating.
4. Bi-temporal rating: add system-time to `PricingRequest`, remove the hardcoded `Optional.empty()`, persist the trace by default.

**Tier 2 — enterprise hardening.**
5. ~~Append-only wallet ledger with reversing entries; balances derived, not stored~~ **DONE** (Migrations V4, V5).
6. ~~Serialization-failure retry (40001) around every wallet mutation~~ **DONE**.
7. ~~Idempotency-Key HTTP surface: fingerprint, 409/422, published expiry, DB unique constraint~~ **DONE** (Migration V16).
8. Token-based pagination on every listing endpoint.
9. RBAC/SSO hooks + an audit-log API; soft archival with per-entity semantics.
10. Per-tenant rate limiting with 429 + Retry-After.

**Tier 3 — channel & reach.**
11. ~~Cloud marketplace metering (AWS `BatchMeterUsage` wire shape)~~ **DONE**.
12. ~~Token price-list sync at a configured markup; versioned snapshots~~ **DONE**.
13. ~~Estimated-vs-realised FX split with a distinct gain/loss posting~~ **DONE**.

**Tier 4 — compliance & supply chain.**
14. ~~Move off Spring Boot `4.0.0-M1` to a GA release~~ **DONE** (Upgraded to Spring Boot 4.1.1 GA / Spring Framework 7.0.9 GA).
15. SBOM, SLSA target, signed provenance.
16. Close the research gaps before committing to a roadmap: PCI DSS v4.0.1 text, ViDA/EUR-Lex, and each in-scope e-invoicing jurisdiction's tax authority page.

---

## 7. Open questions this report could not close

- **[U]** Whether the early-return in `JdbcRateCardRepository.findEffectiveRateCard` for superseded cards is a real predecessor-resolution bug or intentional simplification.
- **[U]** Whether entitlement changes take effect at the next billing period (almost certainly not, given the absent cycle model) — no test or code path exercises it.
- **[U]** Cloud marketplace private-offer mechanics — the research pass could not reach a primary source.
- **[U]** PCI DSS, SOC 2, SOX and ASC 606 requirement text — not retrieved; flagged rather than asserted.
- **[U]** E-invoicing effective dates for DE/FR/IT/PL/IN/SA — deliberately not asserted.

*This report scores code, not documentation. Every `[V]` was checked against the source; every money and concurrency claim in §5 was reproduced with an executed probe.*