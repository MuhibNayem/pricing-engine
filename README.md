# Aequitas (æ·kwi·tahs) ⚖️

> **The Sovereign, Zero-Drift SaaS Pricing, Metering & Rating Engine**  
> *Built natively with Java 25 & Spring Boot 4*

[![Java 25](https://img.shields.io/badge/Java-25-orange.svg?logo=openjdk)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Maven Central](https://img.shields.io/badge/Maven-Multi--Module-blue.svg?logo=apachemaven)](pom.xml)
[![Tests](https://img.shields.io/badge/Tests-713%20Passing%20(100%25)-success.svg)](#-test-verification)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 🏛 Why "Aequitas"?

In classical Roman philosophy and law, **Aequitas** embodies the principle of **equity, exact mathematical fairness, and balance in exchange**. Traditionally depicted holding the scales of measurement, Aequitas represents the standard where every exchange is measured with uncompromising precision.

In modern SaaS monetization, every fractional cent matters. External billing SaaS platforms charge a **0.5%–2.0% gross revenue tax**, force black-box lock-in, and introduce network latency. **Aequitas** gives engineering and finance teams total sovereignty: an embeddable, in-process engine delivering **zero financial drift**, **bi-temporal auditability**, and **universal monetization model support** with sub-millisecond execution.

---

## 🚀 Key Features

* **Universal Monetization Models**: Evaluates any SaaS pricing structure: Flat Subscriptions, Linear Usage, Graduated Tiered Slabs (brackets), Volume Cliffs (tiers), Stair-Step Packages, Multi-Dimensional Hypercubes, Sandboxed Algebraic Dynamic Formulas, Composites, and Hybrid Base+Allowance+Overage.
* **Zero Financial Drift**: Exact `BigDecimal` arithmetic, Banker's Rounding (`HALF_EVEN`), and **Hamilton-Hare Remainder Allocation** (`RemainderAllocator`), mathematically guaranteeing zero penny-rounding loss across distributed line items and invoices.
* **Bi-Temporal Audit Ledger**: Every rate card and contract override tracks **Valid Time** (`effectiveFrom`/`To`) and **System Time** (`recordedAt`/`supersededAt`). Billing cycles are 100% reproducible as of any historical timestamp (ASC 606 & SOX compliant).
* **Real-Time Usage Metering**: Stream ingestion supporting `SUM`, `COUNT`, `MAX` (high-water mark), `LAST` (gauge state with deterministic tie-breaking), and `DISTINCT_COUNT` (cardinality of active devices/users) with idempotency deduplication and watermark lateness handling.
* **Prepaid Credits & Wallets**: Multi-grant wallets (`PREPAID`, `PROMOTIONAL`, `COMMITTED`) with FIFO expiration and priority burndown (promotional grants burn before cash credits).
* **Spend Commitments & True-Ups**: Contractual minimum spend contracts with automated shortfall true-up line items.
* **Java 25 Native**: High-throughput concurrent rating over **Project Loom Virtual Threads** and request-bound audit metadata using **Scoped Values** (`ScopedPricingContext`).
* **Production Persistence & Migrations**: Production-grade PostgreSQL JDBC repositories with B-Tree indexes, JSONB serialization, and Flyway DDL migration schemas (`V1` through `V21`).
* **Spring Boot 4 Starter**: Plug-and-play auto-configuration (`@AutoConfiguration`) with zero mandatory external dependencies.

---

## 📦 Multi-Module Architecture

Aequitas is partitioned into six decoupled modules:

```
pricing-engine/
├── pricing-engine-core/               # Pure Java 25 domain, financial arithmetic, rating models, Loom batching
├── pricing-engine-evaluator/          # Sandboxed SpEL dynamic mathematical expression evaluator
├── pricing-engine-metering/           # Real-time event metering, aggregations, stream converters, async rating
├── pricing-engine-persistence/        # PostgreSQL JDBC repositories, JSONB mappings, Flyway DDL migrations
└── pricing-engine-spring-boot-starter/# Spring Boot 4 starter, Auto-Configuration, REST controllers, metrics
```

| Module | Responsibility |
| :--- | :--- |
| `pricing-engine-core` | Core rating pipeline, `Money`, sealed `PricingModel`, `RemainderAllocator`, `WalletDrawdownEngine`, `HierarchicalRateCardResolver`, `BatchPricingEngine`. |
| `pricing-engine-evaluator` | Dynamic formula evaluation with strict security sandboxing (blocking reflection, classloaders, and unauthorized methods). |
| `pricing-engine-metering` | Meter event ingestion, idempotency deduplication, time windowing, out-of-order event invalidation, and `AsyncRatingTriggerService`. |
| `pricing-engine-persistence` | Production PostgreSQL JDBC repositories with bi-temporal queries, JSONB object mappers, and Flyway migration scripts. |
| `pricing-engine-spring-boot-starter` | Spring Boot 4 auto-configuration, REST controllers (`/api/v1/pricing` & `/api/v1/pricing/meter`), Micrometer metrics, and Scoped Values context. |

---

## 📐 Universal Monetization Models

All models implement the sealed Java 25 interface `PricingModel`:

| Model | Formula / Logic | Industry Use Case |
| :--- | :--- | :--- |
| `FlatFeeModel` | $Gross = Amount$ per cadence | Base SaaS subscriptions (\$499/month) |
| `PerUnitModel` | $Gross = \max(Q, Q_{min}) \times Rate$ | Linear API calls, storage per GB |
| `GraduatedTierModel` | $\sum_{i} \min(\max(Q - u_i, 0), u_{i+1} - u_i) \times R_i$ | Bandwidth slabs, tier-bracket pricing |
| `VolumeTierModel` | $Gross = Q \times R_{bracket}$ | Volume cliffs, large data ingestion |
| `StairStepModel` | $Gross = Price(Band)$ | Package bundles (1-10 seats = \$100, 11-25 = \$220) |
| `DimensionalMatrixModel` | $SubModel(Dimensions \cap MatrixEntry)$ | Cloud VM hypercubes (`region`, `instance`, `os`) |
| `DynamicFormulaModel` | Sandboxed expression (e.g. `(in * 0.001) + (out * 0.003)`) | Generative AI prompt/completion token billing |
| `HybridModel` | $Base + \max(0, Q - Allowance) \times Overage$ | B2B SaaS (Base plan + included seats + overage) |
| `CompositePricingModel` | $\sum SubModels$ | Bundled offerings, multi-part charges |

---

## 🛠 Quick Start Guide

### 1. Add Maven Dependency

```xml
<dependency>
    <groupId>com.saas.pricing</groupId>
    <artifactId>pricing-engine-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure `application.yml`

```yaml
pricing:
  engine:
    enabled: true
    persistence-type: JDBC            # Options: IN_MEMORY (default), JDBC
    default-currency: USD             # Default ISO-4217 Currency
    enable-audit: true                # Records full EvaluationTrace to AuditSink
    rounding-mode: HALF_EVEN          # Banker's Rounding standard
    web-enabled: true                 # Exposes REST endpoints
    streaming:
      enabled: true
      async-rating-enabled: true      # Project Loom async rating dispatcher
```

### 3. Usage Example

```java
@Service
public class InvoicingService {

    private final EnterprisePricingService pricingService;

    public InvoicingService(EnterprisePricingService pricingService) {
        this.pricingService = pricingService;
    }

    public PricingResult computeInvoice(String tenantId, String customerId, int seats, long apiRequests) {
        PricingRequest request = PricingRequest.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .planCode("ENTERPRISE_TIER")
            .item("SEATS", seats)
            .item("API_REQUESTS", apiRequests)
            .discount(Discount.percentage("SUMMER_PROMO", BigDecimal.valueOf(10)))
            .build();

        return pricingService.evaluate(request);
    }
}
```

---

## 💡 Scenario Walkthroughs

### 1. Dynamic LLM Inference Token Billing
```java
// The domain model is a set of records with static `of(...)` factories; there are no builders.
RatePlanItem llmItem = RatePlanItem.of(
    "LLM_INFERENCE",
    "llm_tokens",
    PricingModel.DynamicFormulaModel.of(
        // Variables resolve bare or with a leading '#'. Division MUST use #divide(a, b):
        // SpEL's own '/' divides BigDecimals to the operands' scale, so 7 / 2 returns 4.
        "(promptTokens * 0.000003) + (cachedTokens * 0.0000015) + (completionTokens * 0.000015)",
        "promptTokens", "cachedTokens", "completionTokens"
    ),
    CurrencyUnit.USD
);

PricingRequest request = PricingRequest.builder()
    .tenantId("ai-corp")
    .planCode("PAY_AS_YOU_GO")
    .item("LLM_INFERENCE", BigDecimal.ONE, Map.of(
        "promptTokens", 1_000_000,
        "cachedTokens", 500_000,
        "completionTokens", 200_000
    ))
    .build();

PricingResult result = pricingEngine.evaluate(request);
// result.finalTotal() -> $6.75 USD
```

### 2. Zero-Drift Discount Apportionment (Hamilton-Hare)
```java
// Distribute a $10.00 total invoice discount across 3 line items ($100, $200, $300):
Money totalDiscount = Money.of(10, CurrencyUnit.USD);
List<BigDecimal> weights = List.of(BigDecimal.valueOf(100), BigDecimal.valueOf(200), BigDecimal.valueOf(300));

List<Money> allocated = RemainderAllocator.allocate(totalDiscount, weights);
// Item 1: $1.67 USD
// Item 2: $3.33 USD
// Item 3: $5.00 USD
// Sum: $1.67 + $3.33 + $5.00 = EXACTLY $10.00 USD (Zero Penny Drift)
```

### 3. Real-Time Meter Event Ingestion & Wallet Drawdown
```java
MeterEvent event = MeterEvent.builder()
    .eventId(UUID.randomUUID().toString())
    .idempotencyKey("req_tx_99812")
    .tenantId(TenantId.of("tenant-01"))
    .customerId(CustomerId.of("cust-42"))
    .meterCode("API_CALLS")
    .value(BigDecimal.valueOf(100))        // not eventValue(...)
    .timestamp(Instant.now())              // not eventTimestamp(...)
    .build();

// Ingest with deduplication
IngestionResult ingestion = meteringEngine.ingest(event);

// Asynchronously rate and draw down from customer's prepaid wallet
asyncRatingTriggerService.aggregateRateAndDrawdownAsync(
    TenantId.of("tenant-01"),
    Optional.of(CustomerId.of("cust-42")),
    PlanCode.of("PRO_PLAN"),
    currentBillingWindow
).thenAccept(drawdownOpt -> {
    drawdownOpt.ifPresent(drawdown -> 
        log.info("Credits deducted: {}. New Balance: {}", drawdown.totalDrawnDown(), drawdown.updatedWallet().totalBalance())
    );
});
```

---

## 🌐 REST API Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/pricing/evaluate` | Evaluates a single `PricingRequest` synchronously. |
| `POST` | `/api/v1/pricing/evaluate-batch` | High-throughput batch evaluation using virtual threads. |
| `GET` | `/api/v1/pricing/health` | Health check and engine configuration status. |
| `POST` | `/api/v1/pricing/meter/events` | Ingests a single usage meter event with idempotency checking. |
| `POST` | `/api/v1/pricing/meter/events/batch` | Batch ingests raw meter telemetry events. |
| `GET` | `/api/v1/pricing/meter/aggregations` | Queries materialized time-window aggregations (`SUM`, `MAX`, etc.). |
| `POST` | `/api/v1/pricing/meter/rate-and-drawdown` | Triggers immediate rating and prepaid credit deduction for a window. |
| `POST` | `/api/v1/pricing/entitlements/verify` | Verifies a real-time entitlement / quota check. |
| `GET` | `/api/v1/pricing/entitlements/reconcile` | Reconciles stored customer entitlements against event stream projection. |
| `POST` | `/api/v1/pricing/wallets/drawdown` | Rates a request and atomically draws down a prepaid credit wallet. |
| `POST` | `/api/v1/pricing/invoices` | Creates a draft invoice from a rating calculation (`Idempotency-Key` required). |
| `GET` | `/api/v1/pricing/invoices/{invoiceId}` | Retrieves an invoice by ID with subtotal, tax, and line items. |
| `GET` | `/api/v1/pricing/invoices` | Paginated listing of tenant invoices. |
| `POST` | `/api/v1/pricing/invoices/{invoiceId}/finalize` | Transitions invoice from `DRAFT` to `ISSUED` status. |
| `POST` | `/api/v1/pricing/invoices/{invoiceId}/pay` | Records a payment against an issued invoice. |
| `POST` | `/api/v1/pricing/invoices/{invoiceId}/credit-notes` | Issues a credit note / refund against an invoice. |
| `POST` | `/api/v1/pricing/invoices/{invoiceId}/void` | Voids an unpaid issued invoice. |
| `POST` | `/api/v1/pricing/invoices/{invoiceId}/collect` | Executes payment collection against customer's payment method. |
| `GET` | `/api/v1/pricing/invoices/{invoiceId}/payment-attempts` | Retrieves the immutable audit ledger of collection attempts. |
| `GET` | `/api/v1/pricing/subscriptions/{subscriptionId}` | Retrieves subscription state, period boundaries, and trial dates. |
| `GET` | `/api/v1/pricing/subscriptions` | Lists all subscriptions for a tenant and customer. |
| `POST` | `/api/v1/pricing/subscriptions/{subscriptionId}/pause` | Transitions an active subscription to `PAUSED`. |
| `POST` | `/api/v1/pricing/subscriptions/{subscriptionId}/resume` | Resumes a paused subscription to `ACTIVE`. |
| `POST` | `/api/v1/pricing/subscriptions/{subscriptionId}/cancel` | Cancels subscription with automated prorated credit calculations. |
| `POST` | `/api/v1/pricing/subscriptions/renewals/run` | Triggers the tenant-wide automated billing cycle renewal sweep. |

### ⚠️ Tenant isolation is mandatory

The REST API **will not start** unless your application supplies a `TenantResolver` bean. The
tenant is taken from the authenticated caller and never from the request body; a body `tenantId`
that disagrees with the authenticated tenant is rejected with `403`.

```java
@Bean
TenantResolver tenantResolver() {
    // e.g. SecurityContextHolder.getContext().getAuthentication().getName()
    return () -> currentTenantFromSecurityContext();
}
```

To run without the REST API, set `pricing.engine.web-enabled=false`.

Likewise, `pricing.engine.persistence-type=JDBC` **fails at startup** if no `JdbcTemplate` is
available, rather than silently degrading to in-memory repositories.

---

## 🗄 Database & Flyway Schema

Aequitas includes 21 production-ready PostgreSQL Flyway migrations in `pricing-engine-persistence` (`src/main/resources/db/migration`):

* **`V1` – `V3`**: Core bi-temporal pricing schemas (`rate_cards`, `contract_overrides`, `wallets`, `customer_entitlements`, `pricing_audits`), usage metering telemetry tables (`meter_events`, `meter_aggregations`), and integrity / idempotency constraints.
* **`V4` – `V5`**: Append-only prepaid credit wallet ledger with PostgreSQL append-only immutability trigger rules.
* **`V6` – `V7`**: Invoices and credit notes aggregate tables with strict immutability rules.
* **`V8` – `V9`**: Append-only customer entitlement event stream with ledger immutability guards.
* **`V10` – `V11`**: Payment attempt audit ledger with append-only triggers.
* **`V12` – `V13`**: Transactional outbox table (`outbox_events`), written in the same transaction as the state change it describes, so a committed event cannot be lost from the database. Includes immutability guards. **Delivery is the host's dispatcher**: the library supplies `findDue` / `recordDelivery` / `findUndelivered` and the durable record, but does not run the poll-and-publish loop — an undelivered event is therefore recorded and replayable, not silently dropped.
* **`V14` – `V15`**: Subscriptions persistence table and valid status transition database constraints.
* **`V16` – `V17`**: Tenant-scoped idempotency key store and replay fingerprint verification guards.
* **`V18`**: Subscriptions optimistic locking version column.
* **`V19`**: Gapless invoice number sequences (`invoice_number_sequences`).
* **`V20`**: Asynchronous rating claim coordination table (`rating_claims`).
* **`V21`**: Meter idempotency payload SHA-256 fingerprinting.

---

## 📊 Observability & Metrics

Aequitas exposes production **Micrometer Metrics** under `pricing.engine.*`:

* `pricing.engine.evaluations`: Total evaluations tagged by `planCode`, `tenantId`, and `status`.
* `pricing.engine.evaluation.duration`: Latency distribution timer for rating calculations.
* `pricing.engine.meter.events.ingested`: Meter event counter tagged by `status` (`ACCEPTED`, `DUPLICATE`, `LATE_REJECTED`).
* `pricing.engine.meter.aggregations`: Window aggregation counter tagged by `aggregationType`.
* `pricing.engine.entitlement.checks`: Real-time entitlement verification counter (`allowed=true/false`).

---

## 🧪 Test Verification

To compile and execute the complete test suite under **Java 25**:

```bash
# Requires JDK 25+ (maven-enforcer-plugin enforces this).
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
mvn -B -ntp -o clean test
```

> **Careful with `java_home`.** On a machine with several JDKs installed, `/usr/libexec/java_home -v 25`
> can resolve to a *different* major version without error. Verify what you got before trusting it:
>
> ```bash
> $JAVA_HOME/bin/java -version   # must report 25
> ```

The default build excludes the `load` and `soak` profiles; run them deliberately with
`-Dsurefire.excludedGroups=`. See [docs/RESILIENCE_TESTING.md](docs/RESILIENCE_TESTING.md).

### Test Results Summary:
* `pricing-engine-parent`: SUCCESS
* `pricing-engine-core`: 366 tests passed, 0 failures, 0 errors
* `pricing-engine-evaluator`: 50 tests passed, 0 failures, 0 errors
* `pricing-engine-metering`: 71 tests passed, 0 failures, 0 errors
* `pricing-engine-persistence`: 119 tests passed, 0 failures, 0 errors
* `pricing-engine-spring-boot-starter`: 169 tests passed, 0 failures, 0 errors
* **Total: 775 tests run, 0 failures, 0 errors, 0 skipped.**

---

## 📚 Deep Dive Documentation

For detailed architectural diagrams, operational runbooks, and sales whitepapers:
* 🧭 [**System Architect Brief**](docs/SYSTEM_ARCHITECT_BRIEF.md) — *start here if you are deciding whether to adopt this*
* 📘 [Developer Runbook](docs/DEVELOPER_RUNBOOK.md)
* 📐 [System Architecture Specification](docs/SYSTEM_ARCHITECTURE_SPECIFICATION.md)
* 💼 [Executive Business Sales Whitepaper](docs/BUSINESS_SALES_WHITEPAPER.md)
* 🌍 [Real-World Industry Use Cases](docs/REAL_WORLD_INDUSTRY_USE_CASES.md)
* ⚖️ [Enterprise Readiness Evaluation](docs/ENTERPRISE_READINESS_EVALUATION.md)

---

## 📄 License

Licensed under the Apache License, Version 2.0.
