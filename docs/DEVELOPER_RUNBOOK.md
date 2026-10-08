# Developer Runbook: Enterprise SaaS Pricing & Rating Engine

A comprehensive engineering guide for integrating, configuring, operating, and extending the **Enterprise SaaS Pricing Engine** built on **Java 25** and **Spring Boot 4**.

---

## 1. Quickstart & Environment Setup

### Prerequisites
* **Java**: OpenJDK 25 (with `--enable-preview` enabled)
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
    stream:
      enabled: true
      virtual-threads: true           # Project Loom virtual thread dispatcher
```

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
        RatePlanItem egressItem = RatePlanItem.builder()
            .itemCode("BANDWIDTH_EGRESS_GB")
            .pricingModel(PricingModel.GraduatedTierModel.of(
                Tier.of(BigDecimal.ZERO, BigDecimal.valueOf(10_000), BigDecimal.valueOf(0.0800)),      // 0 - 10,000 GB @ $0.08
                Tier.of(BigDecimal.valueOf(10_000), BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.0600)), // 10,001 - 50,000 GB @ $0.06
                Tier.unbounded(BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.0400))                 // > 50,000 GB @ $0.04
            ))
            .baseCurrency(CurrencyUnit.USD)
            .proratable(false)
            .build();

        RateCard rateCard = RateCard.builder()
            .rateCardId("rc-global-infra-v1")
            .tenantId(TenantId.of("GLOBAL")) // Global catalog fallback
            .planCode(PlanCode.of("CLOUD_INFRA"))
            .version(1)
            .currency(CurrencyUnit.USD)
            .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
            .hierarchyLevel(CatalogHierarchyLevel.GLOBAL_CATALOG)
            .addItem(egressItem)
            .build();

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
            .eventValue(BigDecimal.valueOf(requestCount))
            .eventTimestamp(Instant.now())
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
        ContractOverride override = ContractOverride.builder()
            .contractId("contract-acme-2026")
            .tenantId(TenantId.of(tenantId))
            .customerId(CustomerId.of(customerId))
            .planCode(PlanCode.of("ENTERPRISE_PLAN"))
            .version(1)
            .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
            .effectiveTo(Instant.parse("2027-01-01T00:00:00Z"))
            // Custom negotiated price: $18/seat instead of $25 catalog default
            .overrideItem(RatePlanItem.builder()
                .itemCode("SEATS")
                .pricingModel(PricingModel.PerUnitModel.of(BigDecimal.valueOf(18.00)))
                .baseCurrency(CurrencyUnit.USD)
                .build())
            // Custom contract-level discount: 15% VIP discount
            .discount(Discount.percentage("VIP_ENTERPRISE_15", BigDecimal.valueOf(15)))
            .build();

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

---

## 5. Troubleshooting & Common Operational Errors

| Symptom | Root Cause | Remediation |
| :--- | :--- | :--- |
| `NoSuchElementException: Item code 'XYZ' is not defined in RateCard` | The billable item requested does not exist on the effective rate card. | Ensure the rate card includes `RatePlanItem` for code `'XYZ'` or add a default fallback. |
| `IngestionResult.Status.LATE_REJECTED` | The event timestamp is older than the configured `watermark - allowedLateness`. | Check clock drift on producer or increase `allowedLateness` duration on `MeterDefinition`. |
| `Duplicate Key Exception on idempotencyKey` | Concurrent ingestion of identical `(tenant_id, idempotency_key)`. | Normal idempotent protection; verify that clients do not reuse keys across different events. |
| `Rate card version X already exists and is immutable` | Attempting to save an updated rate card with an existing version number. | Increment `version` (e.g. v1 $\to$ v2) or set `supersededAt` timestamp to retire previous version. |
