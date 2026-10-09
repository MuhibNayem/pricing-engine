# Detailed System Architecture Specification

A technical deep-dive into the architectural foundations, component interactions, database schemas, and data pipelines of the **Enterprise SaaS Pricing Engine**.

---

## 1. End-to-End Component Topology

```mermaid
graph TD
    subgraph "External Ecosystem & Clients"
        App["Core SaaS Microservices"]
        Kafka["Kafka Event Stream / Message Bus"]
        Webhooks["Billing & Ingestion Webhooks"]
    end

    subgraph "pricing-engine-spring-boot-starter"
        PriceCtrl["PricingEngineController (/api/v1/pricing)"]
        MeterCtrl["MeteringController (/api/v1/pricing/meter)"]
        Service["EnterprisePricingService"]
        ScopedVal["ScopedPricingContext (Java 25 Scoped Values)"]
        Metrics["PricingEngineMetrics (Micrometer Observation)"]
        AutoConfig["PricingEngineAutoConfiguration"]
    end

    subgraph "pricing-engine-metering"
        Dispatcher["DefaultMeterEventDispatcher"]
        Conv["StreamMessageConverter (JSON, Map, byte[])"]
        UsageEngine["DefaultUsageMeteringEngine"]
        AsyncTrigger["AsyncRatingTriggerService"]
    end

    subgraph "pricing-engine-core"
        PEngine["DefaultPricingEngine"]
        BatchEngine["BatchPricingEngine (Virtual Threads)"]
        Hierarchy["HierarchicalRateCardResolver"]
        Evaluator["ModelEvaluator"]
        DiscountEng["DiscountEngine"]
        Remainder["RemainderAllocator (Hamilton-Hare)"]
        WalletEng["WalletDrawdownEngine"]
        EntitleEng["EntitlementVerifier"]
    end

    subgraph "pricing-engine-evaluator"
        Spel["SpelFormulaExpressionEvaluator (Sandboxed SpEL)"]
    end

    subgraph "pricing-engine-persistence (PostgreSQL JDBC)"
        JdbcRC["JdbcRateCardRepository"]
        JdbcCO["JdbcContractOverrideRepository"]
        JdbcW["JdbcWalletRepository"]
        JdbcE["JdbcEntitlementRepository"]
        JdbcAud["JdbcAuditSink"]
        JdbcEvents["JdbcMeterEventRepository"]
        JdbcAggs["JdbcMeterAggregationRepository"]
    end

    App --> PriceCtrl
    Webhooks --> MeterCtrl
    Kafka --> Dispatcher

    PriceCtrl --> Service
    MeterCtrl --> Service
    MeterCtrl --> UsageEngine
    Dispatcher --> Conv
    Conv --> UsageEngine
    Dispatcher --> AsyncTrigger

    Service --> ScopedVal
    ScopedVal --> PEngine
    Service --> BatchEngine
    BatchEngine --> PEngine

    AsyncTrigger --> UsageEngine
    AsyncTrigger --> PEngine
    AsyncTrigger --> WalletEng

    PEngine --> Hierarchy
    PEngine --> Evaluator
    PEngine --> DiscountEng
    PEngine --> Remainder
    Evaluator --> Spel

    Hierarchy --> JdbcRC
    Hierarchy --> JdbcCO
    Service --> JdbcW
    Service --> JdbcE
    PEngine --> JdbcAud
    UsageEngine --> JdbcEvents
    UsageEngine --> JdbcAggs
```

---

## 2. Evaluation Pipeline Lifecycle (Directed Acyclic Graph)

Every invoice calculation or usage rating executes through a deterministic, idempotent, fully traceable pipeline:

```mermaid
flowchart TD
    Req["Incoming PricingRequest"] --> Resolve["Hierarchical Rate Card Resolution<br/>(Contract Override &gt; Account Default &gt; Global Catalog)"]
    Resolve --> CheckBiTemp["Bi-Temporal Validation<br/>(effectiveTime within [effectiveFrom, effectiveTo]<br/>AND systemTime valid)"]
    CheckBiTemp --> ExtractItems["Iterate BillableItemRequest Items"]
    ExtractItems --> BillableQty["Compute Billable Quantity<br/>(Apply included units / conversion factors)"]
    BillableQty --> EvaluateModel["ModelEvaluator: Evaluate PricingModel<br/>(Slabs, Cliffs, Packages, Matrix, Dynamic Formula, Hybrid)"]
    EvaluateModel --> Prorate["Time-Window Proration<br/>(Exact second/day ratio application)"]
    Prorate --> FXConvert["FX Currency Conversion<br/>(Exchange rate at evaluationTime)"]
    FXConvert --> LineDiscount["Apply Line-Item Discounts<br/>(WATERFALL, COMPOUND, ADDITIVE, EXCLUSIVE)"]
    LineDiscount --> LineTax["Jurisdictional Tax Calculation<br/>(State, City, VAT/GST)"]
    LineTax --> Subtotal["Aggregate Line Items & Compute Gross Subtotal"]
    Subtotal --> InvDiscount["Apply Invoice-Level Discounts"]
    InvDiscount --> RemainderAlloc["Hamilton-Hare Remainder Allocation<br/>(Zero penny-drift apportionment down to line items)"]
    RemainderAlloc --> SpendCommit["Spend Commitment Verification<br/>(Assess shortfall true-up if net &lt; minimum)"]
    SpendCommit --> Result["Generate Immutable PricingResult<br/>(Includes complete EvaluationTrace)"]
    Result --> Audit["Persist to AuditSink & Record Micrometer Metrics"]
```

---

## 3. Asynchronous Stream Ingestion & Rating Sequence Diagram

The interaction model when raw telemetry events arrive over event streams (Kafka, RabbitMQ, SQS):

```mermaid
sequenceDiagram
    autonumber
    participant Producer as Kafka / Event Stream
    participant Consumer as MeterEventConsumer / Dispatcher
    participant Metering as DefaultUsageMeteringEngine
    participant AsyncService as AsyncRatingTriggerService
    participant Rating as DefaultPricingEngine
    participant Wallet as WalletDrawdownEngine
    participant DB as PostgreSQL Persistence

    Producer->>Consumer: Push Raw JSON Event Payload
    Consumer->>Consumer: StreamMessageConverter: Deserialize to MeterEvent
    Consumer->>Metering: ingest(MeterEvent)
    Metering->>DB: Check IdempotencyStore (tenantId, idempotencyKey)
    alt Event is Duplicate
        Metering-->>Consumer: Return IngestionResult.DUPLICATE
    else Event is Valid
        Metering->>DB: Check Watermark Lateness
        Metering->>DB: Append to meter_events table
        Metering->>DB: Invalidate matching window in meter_aggregations
        Metering-->>Consumer: Return IngestionResult.ACCEPTED
    end

    Consumer->>AsyncService: aggregateRateAndDrawdownAsync(tenantId, customerId, planCode, window)
    AsyncService->>Metering: generateBillableItems(tenantId, customerId, window)
    Metering->>DB: Query & compute window aggregation (SUM / COUNT / MAX / LAST / DISTINCT)
    Metering-->>AsyncService: Return List<BillableItemRequest>
    AsyncService->>Rating: evaluate(PricingRequest with billable items)
    Rating-->>AsyncService: Return PricingResult (finalTotal)
    AsyncService->>DB: Load Customer Wallet
    AsyncService->>Wallet: drawdown(wallet, finalTotal, calculationId)
    Wallet->>DB: Commit updated grants & append to wallet_transactions ledger
    AsyncService-->>Consumer: Complete Future with WalletDrawdownResult
```

---

## 4. Entity-Relationship & Relational Database Schema

The production schema (`pricing-engine-persistence`) implemented for PostgreSQL:

```mermaid
erDiagram
    rate_cards ||--o{ rate_card_items : "contains"
    rate_cards {
        varchar(128) rate_card_id PK
        varchar(64) tenant_id
        varchar(64) plan_code
        int version
        varchar(16) currency
        timestamptz effective_from
        timestamptz effective_to
        timestamptz recorded_at
        timestamptz superseded_at
        varchar(64) hierarchy_level
        text payload_json
    }

    contract_overrides {
        varchar(128) contract_id PK
        varchar(64) tenant_id
        varchar(64) customer_id
        varchar(64) plan_code
        int version
        timestamptz effective_from
        timestamptz effective_to
        timestamptz recorded_at
        timestamptz superseded_at
        text payload_json
    }

    wallets ||--o{ wallet_transactions : "audited_by"
    wallets {
        varchar(128) wallet_id PK
        varchar(64) tenant_id
        varchar(64) customer_id
        varchar(16) currency
        text payload_json
    }

    wallet_transactions {
        varchar(128) transaction_id PK
        varchar(128) wallet_id FK
        varchar(128) grant_id
        varchar(128) grant_name
        varchar(128) calculation_id
        varchar(128) line_item_code
        numeric credits_drawn
        numeric money_amount
        numeric balance_after
        timestamptz created_at
    }

    customer_entitlements {
        varchar(128) entitlement_id PK
        varchar(64) tenant_id
        varchar(64) customer_id
        varchar(128) feature_key
        varchar(64) feature_type
        varchar(64) enforcement_mode
        text payload_json
        timestamptz updated_at
    }

    meter_events {
        varchar(128) event_id PK
        varchar(128) idempotency_key
        varchar(64) tenant_id
        varchar(64) customer_id
        varchar(128) meter_code
        numeric event_value
        timestamptz event_timestamp
        text properties_json
    }

    meter_aggregations {
        varchar(128) aggregation_id PK
        varchar(64) tenant_id
        varchar(64) customer_id
        varchar(128) meter_code
        varchar(64) aggregation_type
        timestamptz window_start
        timestamptz window_end
        numeric aggregated_value
        bigint event_count
        timestamptz last_event_time
        timestamptz updated_at
    }

    pricing_audits ||--o{ pricing_audit_line_items : "details"
    pricing_audits {
        varchar(128) calculation_id PK
        varchar(64) tenant_id
        varchar(64) customer_id
        varchar(64) plan_code
        timestamptz evaluation_time
        varchar(16) currency
        numeric gross_total
        numeric discount_total
        numeric net_total
        numeric tax_total
        numeric final_total
        text trace_json
        timestamptz recorded_at
    }

    pricing_audit_line_items {
        varchar(128) item_id PK
        varchar(128) calculation_id FK
        varchar(128) item_code
        numeric raw_quantity
        numeric billable_quantity
        numeric gross_amount
        numeric discount_amount
        numeric net_amount
        numeric tax_amount
        numeric final_amount
    }
```

---

## 5. Concurrency & High-Throughput Loom Architecture

### Java 25 Project Loom Virtual Threads
* High-volume batch requests evaluated via [`BatchPricingEngine`](file:///Users/a.k.mmuhibullahnayem/Developer/pricing-engine/pricing-engine-core/src/main/java/com/saas/pricing/core/engine/BatchPricingEngine.java) using `Executors.newVirtualThreadPerTaskExecutor()`.
* Virtual threads allow thousands of concurrent pricing calculations without thread pool starvation or context-switching penalties.
* Memory footprint per virtual thread is hundreds of bytes, enabling near-instantaneous scaling on multicore nodes.

### Java 25 Scoped Values Context Management
* Traditional `ThreadLocal` storage suffers from memory leaks and inheritance overhead in virtual thread architectures.
* [`ScopedPricingContext`](file:///Users/a.k.mmuhibullahnayem/Developer/pricing-engine/pricing-engine-spring-boot-starter/src/main/java/com/saas/pricing/starter/context/ScopedPricingContext.java) utilizes Java 25 `ScopedValue` (`ScopedValue<PricingContext>`) to pass immutable tenant credentials, customer IDs, and audit correlation IDs cleanly down the calculation call tree:

```java
ScopedPricingContext.runWith(tenantId, customerId, correlationId, () -> {
    return pricingEngine.evaluate(request);
});
```
