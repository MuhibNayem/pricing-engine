package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import com.saas.pricing.metering.engine.UsageMeteringEngine;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.web.dto.PricingDtos;
import com.saas.pricing.starter.web.dto.PricingDtos.MeterEventDto;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * REST controller for real-time usage metering event ingestion and aggregation endpoints.
 */
@RestController
@RequestMapping("/api/v1/pricing/meter")
public class MeteringController {

    /** Bounds the batch ingestion payload; the engine rejects a stream burst without a ceiling. */
    static final int MAX_BATCH_SIZE = 10_000;

    private final UsageMeteringEngine meteringEngine;
    private final EnterprisePricingService pricingService;
    private final com.saas.pricing.starter.tenant.TenantGuard tenantGuard;
    private final String defaultCurrency;

    public MeteringController(
        UsageMeteringEngine meteringEngine,
        EnterprisePricingService pricingService,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard,
        com.saas.pricing.starter.PricingEngineProperties properties
    ) {
        this.tenantGuard = tenantGuard;
        this.meteringEngine = Objects.requireNonNull(meteringEngine, "meteringEngine cannot be null");
        this.pricingService = pricingService;
        this.defaultCurrency = (properties != null && properties.getDefaultCurrency() != null)
            ? properties.getDefaultCurrency() : "USD";
    }

    public MeteringController(
        UsageMeteringEngine meteringEngine,
        EnterprisePricingService pricingService,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard
    ) {
        this(meteringEngine, pricingService, tenantGuard, null);
    }

    @PostMapping("/events")
    public ResponseEntity<IngestionResult> ingestEvent(@Valid @RequestBody PricingDtos.MeterEventDto dto) {
        MeterEvent event = mapToMeterEvent(dto);
        IngestionResult result = meteringEngine.ingest(event);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/events/batch")
    public ResponseEntity<List<IngestionResult>> ingestBatch(@RequestBody List<@Valid MeterEventDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            throw new IllegalArgumentException("Batch request cannot be empty");
        }
        if (dtos.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                "Batch request of " + dtos.size() + " exceeds the maximum of " + MAX_BATCH_SIZE);
        }
        List<MeterEvent> events = dtos.stream().map(this::mapToMeterEvent).toList();
        List<IngestionResult> results = meteringEngine.ingestBatch(events);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/aggregations")
    public ResponseEntity<PricingDtos.MeterAggregationResponseDto> getAggregation(
        @RequestParam String tenantId,
        @RequestParam(required = false) String customerId,
        @RequestParam String meterCode,
        @RequestParam String windowStart,
        @RequestParam String windowEnd
    ) {
        TimeWindow window = TimeWindow.of(Instant.parse(windowStart), Instant.parse(windowEnd));
        Optional<CustomerId> cust = customerId != null ? Optional.of(CustomerId.of(customerId)) : Optional.empty();

        MeterAggregation agg = meteringEngine.aggregate(TenantId.of(tenantGuard.verify(tenantId)), cust, meterCode, window);
        return ResponseEntity.ok(new PricingDtos.MeterAggregationResponseDto(
            agg.tenantId().value(),
            agg.customerId().map(CustomerId::value).orElse(null),
            agg.meterCode(),
            agg.aggregationType().name(),
            agg.window().startTime().toString(),
            agg.window().endTime().toString(),
            agg.aggregatedValue(),
            agg.eventCount()
        ));
    }

    @PostMapping("/rate-and-drawdown")
    public ResponseEntity<PricingDtos.WalletDrawdownResponseDto> rateAndDrawdown(
        @Valid @RequestBody PricingDtos.MeterRateAndDrawdownRequestDto request
    ) {
        if (pricingService == null) {
            throw new IllegalStateException("EnterprisePricingService is not configured");
        }

        TenantId tenantId = TenantId.of(tenantGuard.verify(request.tenantId()));
        Optional<CustomerId> customerId = request.customerId() != null
            ? Optional.of(CustomerId.of(request.customerId()))
            : Optional.empty();

        TimeWindow window = TimeWindow.of(Instant.parse(request.windowStart()), Instant.parse(request.windowEnd()));
        List<BillableItemRequest> billableItems = meteringEngine.generateBillableItems(tenantId, customerId, window);

        PricingRequest.Builder builder = PricingRequest.builder()
            .tenantId(tenantId)
            .planCode(request.planCode())
            .evaluationTime(window.endTime())
            .targetCurrency(CurrencyUnit.of(request.targetCurrency() != null ? request.targetCurrency() : defaultCurrency));

        customerId.ifPresent(builder::customerId);
        billableItems.forEach(builder::item);

        WalletDrawdownResult drawdownResult = pricingService.evaluateAndDrawdown(builder.build());
        return ResponseEntity.ok(PricingDtos.WalletDrawdownResponseDto.from(drawdownResult));
    }

    private MeterEvent mapToMeterEvent(PricingDtos.MeterEventDto dto) {
        Instant ts = dto.timestamp() != null ? Instant.parse(dto.timestamp()) : Instant.now();
        Map<String, Object> props = dto.properties() != null ? dto.properties() : Map.of();

        return MeterEvent.builder()
            .eventId(dto.eventId())
            .idempotencyKey(dto.idempotencyKey())
            .tenantId(tenantGuard.verify(dto.tenantId()))
            .customerId(dto.customerId())
            .meterCode(dto.meterCode())
            .value(dto.value() != null ? dto.value() : java.math.BigDecimal.ONE)
            .timestamp(ts)
            .properties(props)
            .build();
    }
}
