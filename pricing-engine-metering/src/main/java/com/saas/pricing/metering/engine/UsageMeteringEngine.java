package com.saas.pricing.metering.engine;

import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;

import java.util.List;
import java.util.Optional;

/**
 * High-throughput usage metering engine for ingesting usage events,
 * performing deduplication, aggregating across time windows, and feeding
 * rating engines with billable item requests.
 */
public interface UsageMeteringEngine {

    /**
     * Ingests a single usage event with idempotency verification and out-of-order validation.
     */
    IngestionResult ingest(MeterEvent event);

    /**
     * Ingests a batch of usage events atomically or sequentially.
     */
    List<IngestionResult> ingestBatch(List<MeterEvent> events);

    /**
     * Aggregates usage for a specific meter and customer/tenant over a given time window.
     */
    MeterAggregation aggregate(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window);

    /**
     * Aggregates all active meters for a tenant/customer over a given time window.
     */
    List<MeterAggregation> aggregateAll(TenantId tenantId, Optional<CustomerId> customerId, TimeWindow window);

    /**
     * Converts all aggregated meter readings for a given window into BillableItemRequests
     * ready for input into the PricingEngine.
     */
    List<BillableItemRequest> generateBillableItems(TenantId tenantId, Optional<CustomerId> customerId, TimeWindow window);
}
