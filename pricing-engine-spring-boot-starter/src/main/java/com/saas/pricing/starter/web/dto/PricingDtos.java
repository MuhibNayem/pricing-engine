package com.saas.pricing.starter.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST API DTOs for the standalone pricing engine endpoints.
 */
public final class PricingDtos {

    private PricingDtos() {}

    public record ItemDto(
        String itemCode,
        BigDecimal quantity,
        Map<String, Object> attributes
    ) {}

    public record DiscountDto(
        String code,
        String type, // PERCENTAGE or FIXED_AMOUNT
        BigDecimal value,
        String scope, // LINE_ITEM or INVOICE_TOTAL
        String targetItemCode
    ) {}

    public record PricingEvaluationRequestDto(
        String tenantId,
        String customerId,
        String planCode,
        String targetCurrency,
        List<ItemDto> items,
        List<DiscountDto> discounts,
        Map<String, Object> attributes
    ) {}

    public record RatedLineItemDto(
        String itemCode,
        BigDecimal rawQuantity,
        BigDecimal billableQuantity,
        String grossAmount,
        String discountAmount,
        String netAmount,
        String taxAmount,
        String lineTotal
    ) {}

    public record PricingEvaluationResponseDto(
        String calculationId,
        String tenantId,
        String customerId,
        String planCode,
        String currency,
        String totalGross,
        String totalDiscount,
        String totalNet,
        String totalTax,
        String finalTotal,
        List<RatedLineItemDto> lineItems
    ) {}

    public record EntitlementCheckRequestDto(
        String tenantId,
        String customerId,
        String featureKey,
        BigDecimal requestedUnits
    ) {}

    public record EntitlementCheckResponseDto(
        boolean allowed,
        String featureKey,
        BigDecimal requestedUnits,
        BigDecimal currentUsage,
        BigDecimal remainingQuota,
        boolean isOverage,
        BigDecimal overageQuantity,
        String reason
    ) {}

    public record WalletDrawdownRequestDto(
        String tenantId,
        String customerId,
        String planCode,
        String targetCurrency,
        List<ItemDto> items
    ) {}

    public record WalletDrawdownResponseDto(
        String walletId,
        String originalInvoiceAmount,
        BigDecimal totalCreditsDrawn,
        String totalCreditMoneyValue,
        String remainingInvoiceDue,
        boolean fullyCovered
    ) {}

    public record MeterEventDto(
        String eventId,
        String idempotencyKey,
        String tenantId,
        String customerId,
        String meterCode,
        BigDecimal value,
        String timestamp,
        Map<String, Object> properties
    ) {}

    public record MeterAggregationResponseDto(
        String tenantId,
        String customerId,
        String meterCode,
        String aggregationType,
        String windowStart,
        String windowEnd,
        BigDecimal aggregatedValue,
        long eventCount
    ) {}

    public record MeterRateAndDrawdownRequestDto(
        String tenantId,
        String customerId,
        String planCode,
        String targetCurrency,
        String windowStart,
        String windowEnd
    ) {}
}
