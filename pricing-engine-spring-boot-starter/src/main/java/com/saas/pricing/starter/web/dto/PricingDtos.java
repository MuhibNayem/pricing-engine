package com.saas.pricing.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST API DTOs for the standalone pricing engine endpoints.
 *
 * <p>Request DTOs carry Bean Validation constraints, and controllers declare {@code @Valid}. Without
 * both, a null or negative field travelled into the domain and surfaced as an NPE or a 500 instead
 * of a 400 naming the offending field.
 */
public final class PricingDtos {

    private PricingDtos() {}

    public record ItemDto(
        @NotBlank String itemCode,
        @NotNull @PositiveOrZero BigDecimal quantity,
        Map<String, Object> attributes
    ) {}

    public record DiscountDto(
        @NotBlank String code,
        @NotBlank String type,
        @NotNull @PositiveOrZero BigDecimal value,
        String scope,
        String targetItemCode
    ) {}

    public record PricingEvaluationRequestDto(
        @NotBlank String tenantId,
        String customerId,
        @NotBlank String planCode,
        String targetCurrency,
        @NotEmpty @Valid List<ItemDto> items,
        @Valid List<DiscountDto> discounts,
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
        @NotBlank String tenantId,
        @NotBlank String customerId,
        @NotBlank String featureKey,
        @NotNull @PositiveOrZero BigDecimal requestedUnits
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
        @NotBlank String tenantId,
        String customerId,
        @NotBlank String planCode,
        String targetCurrency,
        @NotEmpty @Valid List<ItemDto> items
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
        @NotBlank String eventId,
        String idempotencyKey,
        @NotBlank String tenantId,
        String customerId,
        @NotBlank String meterCode,
        @NotNull @PositiveOrZero BigDecimal value,
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
        @NotBlank String tenantId,
        String customerId,
        @NotBlank String planCode,
        String targetCurrency,
        @NotBlank String windowStart,
        @NotBlank String windowEnd
    ) {}
}
