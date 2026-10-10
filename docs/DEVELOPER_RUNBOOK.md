# Developer Runbook: Enterprise SaaS Pricing & Rating Engine

A comprehensive engineering guide for integrating, configuring, operating, and extending the **Enterprise SaaS Pricing Engine** built on **Java 25** and **Spring Boot 4**.

---

## 1. Quickstart & Environment Setup

### Prerequisites
* **Java**: OpenJDK 25 (standard release; no preview flags required)
* **Build Tool**: Apache Maven 3.9+
* **Database (Optional for production)**: PostgreSQL 15+ (H2 in PostgreSQL mode supported for testing)
* **Message Broker (Optional for streaming)**: Apache Kafka 3.x, RabbitMQ, or AWS SQS

### Maven Dependency Integration
Add the starter to your microservice's `pom.xml`:

```xml
<dependency>
    <groupId>com.saas.pricing</groupId>
    <artifactId>pricing-engine-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Application Configuration (`application.yml`)
```yaml
pricing:
  engine:
    enabled: true
    persistence-type: JDBC            # Options: IN_MEMORY (default), JDBC
    default-currency: USD             # ISO-4217 Currency Code
    enable-audit: true                # Persists full evaluation traces to AuditSink
    rounding-mode: HALF_EVEN          # Banker's Rounding standard
    web-enabled: true                 # Exposes /api/v1/pricing & /api/v1/pricing/meter REST endpoints
    streaming:
      enabled: true
      async-rating-enabled: true      # Project Loom virtual thread dispatcher

    # Admission control. Off by default: unconfigured, the engine sheds nothing and the hot path
    # pays no allocation and no CAS. Turn it on once you have sized your database pool and want the
    # engine to refuse cleanly above that point rather than degrade inside the pricing path.
    admission:
      enabled: true
      permits: 1000                    # sustained permits per period, per tenant
      period: 1s
      burst: 1000                      # largest burst a tenant may send at once
      max-concurrency: 256             # in-flight ceiling across ALL tenants on this node
```

Shed requests carry `Retry-After` and a distinct status, and the split matters operationally:

| Condition | Status | Meaning |
| :--- | :--- | :--- |
| Tenant over its token bucket | `429` | That tenant's quota. The engine is healthy. |
| Engine at its in-flight ceiling | `503` | This node is at capacity. No tenant's quota is at fault. |

Answering `503` to a tenant that is merely over its own rate makes every healthy caller believe the
platform is down and hides the real problem from monitoring.

This governs the engine's own capacity. Per-IP and per-API-key limiting belongs to the gateway in
front of it — a library cannot see the caller. Note also that the limits are **per process**: behind
N load balancers the effective per-tenant rate is N times the configured one.

---

## 2. Core Scenario-Based Code Examples

### Scenario 1: Setting Up a Rate Card with Graduated Tiered Slabs
*Use case: Egress bandwidth or storage tiers where units in each bracket are rated at that bracket's price.*

```java
@Service
public class CatalogService {

    private final RateCardRepository rateCardRepository;

    public CatalogService(RateCardRepository rateCardRepository) {
        this.rateCardRepository = rateCardRepository;
    }

    public void configureBandwidthPlan() {
        // The domain model is records with static of(...) factories - there are no builders.
        RatePlanItem egressItem = RatePlanItem.of("BANDWIDTH_EGRESS_GB", "egress_gb",
            PricingModel.GraduatedTierModel.of(
                Tier.of(BigDecimal.ZERO, BigDecimal.valueOf(10_000), BigDecimal.valueOf(0.0800)),          // 0 - 10,000 GB @ $0.08
                Tier.of(BigDecimal.valueOf(10_000), BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.0600)), // 10,001 - 50,000 GB @ $0.06
                Tier.unbounded(BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.0400))                     // > 50,000 GB @ $0.04
            ),
            CurrencyUnit.USD
        );

        RateCard rateCard = RateCard.global(
            "rc-global-infra-v1",
            PlanCode.of("CLOUD_INFRA"),
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(egressItem)
        );

        rateCardRepository.save(rateCard);
    }
}
```

---

### Scenario 2: Dynamic LLM Token Billing (Prompt vs. Completion Math)
*Use case: Generative AI inference where input tokens, cached prompt tokens, and output tokens carry distinct algebraic rates.*

```java
@Service
public class LlmPricingService {

    private final EnterprisePricingService pricingService;

    public LlmPricingService(EnterprisePricingService pricingService) {
        this.pricingService = pricingService;
    }

    public PricingResult rateInferenceRequest(String tenantId, String customerId, long promptTokens, long cachedTokens, long completionTokens) {
        PricingRequest request = PricingRequest.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .planCode("LLM_PRO")
            .item("LLM_INFERENCE", BigDecimal.ONE, Map.of(
                "promptTokens", promptTokens,
                "cachedTokens", cachedTokens,
                "completionTokens", completionTokens
            ))
            .build();

        return pricingService.evaluate(request);
    }
}
```

---

### Scenario 3: Real-Time Usage Meter Ingestion & Automated Wallet Drawdown
*Use case: Stream-ingested API calls automatically debited from a developer's prepaid credit wallet.*

```java
@Service
public class MeteringOrchestrationService {

    private final UsageMeteringEngine meteringEngine;
    private final AsyncRatingTriggerService asyncRatingTriggerService;

    public MeteringOrchestrationService(
        UsageMeteringEngine meteringEngine,
        AsyncRatingTriggerService asyncRatingTriggerService
    ) {
        this.meteringEngine = meteringEngine;
        this.asyncRatingTriggerService = asyncRatingTriggerService;
    }

    public void processIncomingApiEvent(String tenantId, String customerId, String idempotencyKey, double requestCount) {
        MeterEvent event = MeterEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .idempotencyKey(idempotencyKey)
            .tenantId(TenantId.of(tenantId))
            .customerId(CustomerId.of(customerId))
            .meterCode("API_INVOCATIONS")
            .value(BigDecimal.valueOf(requestCount))
            .timestamp(Instant.now())
            .property("endpoint", "/v1/embeddings")
            .build();

        // 1. Ingest event with deduplication and lateness checks
        IngestionResult ingestion = meteringEngine.ingest(event);
        if (ingestion.status() == IngestionResult.Status.DUPLICATE) {
            return; // Idempotent discard
        }

        // 2. Trigger asynchronous rate and credit drawdown
        TimeWindow currentBillingWindow = TimeWindow.of(
            Instant.now().truncatedTo(ChronoUnit.HOURS),
            Instant.now().plus(1, ChronoUnit.HOURS)
        );

        asyncRatingTriggerService.aggregateRateAndDrawdownAsync(
            TenantId.of(tenantId),
            Optional.of(CustomerId.of(customerId)),
            PlanCode.of("DEVELOPER_TIER"),
            currentBillingWindow
        ).thenAccept(drawdownOpt -> {
            drawdownOpt.ifPresent(drawdown -> {
                System.out.printf("Drawn down: %s USD across grants. New Balance: %s USD%n",
                    drawdown.totalDrawnDown(), drawdown.updatedWallet().totalBalance());
            });
        });
    }
}
```

---

### Scenario 4: B2B Enterprise Custom Contract Overrides & Negotiated Slabs
*Use case: An enterprise sales team negotiates special pricing for ACME Corp that overrides the standard catalog for `SEATS` and `STORAGE_GB`.*

```java
@Service
public class EnterpriseContractService {

    private final ContractOverrideRepository contractOverrideRepository;

    public EnterpriseContractService(ContractOverrideRepository contractOverrideRepository) {
        this.contractOverrideRepository = contractOverrideRepository;
    }

    public void provisionNegotiatedContract(String tenantId, String customerId) {
        // ContractOverride is a record with static of(...) factories - there is no builder().
        RatePlanItem negotiatedSeat = RatePlanItem.of("SEATS", "seats",
            PricingModel.PerUnitModel.of(new BigDecimal("18.00")), CurrencyUnit.USD);

        ContractOverride override = ContractOverride.of(
            "contract-acme-2026",
            TenantId.of(tenantId),
            CustomerId.of(customerId),
            PlanCode.of("ENTERPRISE_PLAN"),
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2027-01-01T00:00:00Z"),
            List.of(negotiatedSeat),   // $18/seat instead of the $25 catalog default
            List.of(Discount.percentage("VIP_ENTERPRISE_15", new BigDecimal("15")))
        );

        contractOverrideRepository.save(override);
    }
}
```

---

### Scenario 5: Mid-Month Subscription Proration (Seat Additions)
*Use case: Adding 10 seats 12 days into a 30-day billing cycle.*

```java
public PricingResult calculateProratedSeatUpgrade() {
    // 12 days into a 30-day month
    ProrationWindow window = ProrationWindow.of(
        Instant.parse("2026-04-12T00:00:00Z"), // Upgrade effective date
        Instant.parse("2026-04-30T23:59:59Z"), // Billing cycle end
        Instant.parse("2026-04-01T00:00:00Z"), // Billing cycle start
        Instant.parse("2026-04-30T23:59:59Z")  // Billing cycle end
    );

    PricingRequest request = PricingRequest.builder()
        .tenantId("saas-app")
        .customerId("cust-900")
        .planCode("PRO_SEATS")
        .item("SEATS", BigDecimal.valueOf(10))
        .prorationWindow(window)
        .build();

    return pricingEngine.evaluate(request);
    // Factor is ~0.60. Full price $250.00 prorates to $150.00 USD with zero penny drift.
}
```

---

## 3. REST API Reference

### 1. Ingest Usage Meter Event
```http
POST /api/v1/pricing/meter/events
Content-Type: application/json

{
  "eventId": "evt_100982",
  "idempotencyKey": "req_abc_123",
  "tenantId": "tenant-corp",
  "customerId": "cust-55",
  "meterCode": "API_CALLS",
  "eventValue": 1.0,
  "eventTimestamp": "2026-10-08T20:30:00Z",
  "properties": {
    "model": "claude-3-opus",
    "region": "us-east-1"
  }
}
```

### 2. High-Throughput Batch Event Ingestion
```http
POST /api/v1/pricing/meter/events/batch
Content-Type: application/json

[
  { "eventId": "evt_1", "idempotencyKey": "k1", "tenantId": "t1", "meterCode": "TOKENS", "eventValue": 1500, "eventTimestamp": "2026-10-08T20:00:00Z" },
  { "eventId": "evt_2", "idempotencyKey": "k2", "tenantId": "t1", "meterCode": "TOKENS", "eventValue": 2500, "eventTimestamp": "2026-10-08T20:01:00Z" }
]
```

### 3. Evaluate Pricing Request
```http
POST /api/v1/pricing/evaluate
Content-Type: application/json

{
  "tenantId": "tenant-corp",
  "customerId": "cust-55",
  "planCode": "PRO_PLAN",
  "targetCurrency": "USD",
  "items": [
    { "itemCode": "SEATS", "quantity": 15 },
    { "itemCode": "API_CALLS", "quantity": 50000 }
  ],
  "discounts": [
    { "discountCode": "PROMO_SUMMER", "type": "PERCENTAGE", "value": 10.0 }
  ]
}
```

---

## 4. Operational Monitoring & Observability

The library registers comprehensive **Micrometer Metrics** under the `pricing.engine.*` namespace:

| Metric Name | Type | Description & Tags |
| :--- | :--- | :--- |
| `pricing.engine.evaluations` | Counter | Total evaluations by `planCode`, `tenantId`, `status`. |
| `pricing.engine.evaluation.duration` | Timer | Latency distribution of rating calculations. |
| `pricing.engine.meter.events.ingested` | Counter | Meter event volume tagged by `meterCode` and `status` (`ACCEPTED`, `DUPLICATE`, `LATE_REJECTED`). |
| `pricing.engine.meter.aggregations` | Counter | Aggregations computed by `aggregationType`. |
| `pricing.engine.entitlement.checks` | Counter | Real-time entitlement decisions (`allowed=true/false`). |

### 4.1 Trace propagation across the outbox

Metrics say *how much*. Traces say *why*, and the outbox is exactly where a trace would otherwise die.
The request that finalised an invoice opens a trace; the event announcing it is written to a table
and picked up later by a dispatcher, possibly in another process. Context does not cross that hop
on its own, so `outbox_events` carries the W3C headers on the row and the library stamps them at
enqueue time, inside the transaction that caused the state change.

Supply a `TraceContextProvider` bean and every queued event starts carrying it:

```java
@Bean
TraceContextProvider traceContextProvider() {
    return () -> {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) {
            return TraceContext.NONE;
        }
        String traceparent = "00-" + span.getTraceId() + "-" + span.getSpanId()
            + "-" + span.getTraceFlags().asHex();
        String tracestate = W3CTraceContextPropagator.getInstance()
            .getTraceState(Span.current()).toString();
        return new TraceContext(Optional.of(traceparent),
            tracestate.isEmpty() ? Optional.empty() : Optional.of(tracestate));
    };
}
```

The dispatcher then injects `event.traceContext().asCarrierHeaders()` into whatever it publishes —
a Kafka record header, a RabbitMQ property, an HTTP header. **This is deliberately the whole SDK-free
contract.** `pricing-engine-core` has no OpenTelemetry dependency and will not acquire one: the outbox
never creates a span, it only carries two strings, and the propagation format is a host decision.
The `traceparent` grammar is validated against [W3C Trace Context](https://www.w3.org/TR/trace-context/)
§3.2 at construction, so a malformed value is refused where it is created rather than silently
failing to correlate at the far end of a broker. Absent a provider, behaviour is unchanged: no trace
is stamped.

### 4.2 Exposing the engine on another transport (gRPC, GraphQL, message consumers)

The REST controllers are an adapter, not the engine. `pricing-engine-core` knows nothing about HTTP;
it is entered through plain Java calls on the domain model, which is why gRPC is **not** shipped here.

That is a deliberate boundary rather than an omission. A server adapter means picking HTTP/2 and a
`protoc` build for every consumer, maintaining a second wire contract alongside the REST one
forever, and — most importantly — choosing the transport on the host's behalf. Most hosts already
run a mesh, a gateway or a service framework with opinions about this, and a service mesh handles
gRPC and REST alike. Message transport is the host's job; so is the protobuf schema, because only the
host knows which parts of the pricing domain cross a service boundary.

What you need is already there:

1. Generate stubs from your own `.proto`.
2. Map them onto `PricingEngine.evaluate(PricingRequest) -> PricingResult` and the other core
   entry points, the same way `PricingEngineController` maps DTOs onto the same domain types.
3. Take an `IdempotencyKeyStore` and an `AdmissionController` in the constructor — the 429/503
   shedding semantics come free rather than being reimplemented per transport.

Nothing in core needs to change to do this. If it turns out something does, that is a bug in the
seam and worth reporting rather than a reason to fork.

---

## 5. Troubleshooting & Common Operational Errors

| Symptom | Root Cause | Remediation |
| :--- | :--- | :--- |
| `NoSuchElementException: Item code 'XYZ' is not defined in RateCard` | The billable item requested does not exist on the effective rate card. | Ensure the rate card includes `RatePlanItem` for code `'XYZ'` or add a default fallback. |
| `IngestionResult.Status.LATE_REJECTED` | The event timestamp is older than the configured `watermark - allowedLateness`. | Check clock drift on producer or increase `allowedLateness` duration on `MeterDefinition`. |
| `Duplicate Key Exception on idempotencyKey` | Concurrent ingestion of identical `(tenant_id, idempotency_key)`. | Normal idempotent protection; verify that clients do not reuse keys across different events. |
| `Rate card version X already exists and is immutable` | Attempting to save an updated rate card with an existing version number. | Increment `version` (e.g. v1 $\to$ v2) or set `supersededAt` timestamp to retire previous version. |
