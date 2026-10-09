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
        @NotEmpty List<@Valid ItemDto> items,
        List<@Valid DiscountDto> discounts,
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
        @NotEmpty List<@Valid ItemDto> items
    ) {}

    public record WalletDrawdownResponseDto(
        String walletId,
        String originalInvoiceAmount,
        BigDecimal totalCreditsDrawn,
        String totalCreditMoneyValue,
        String remainingInvoiceDue,
        boolean fullyCovered,
        String currency,
        BigDecimal originalAmount,
        BigDecimal creditMoneyAmount,
        BigDecimal remainingDueAmount
    ) {
        public WalletDrawdownResponseDto {
            // Validate: if both string and numeric forms are supplied, they must agree
            if (originalInvoiceAmount != null && originalAmount != null) {
                BigDecimal parsed = parseMoneyAmount(originalInvoiceAmount);
                if (parsed != null && parsed.compareTo(originalAmount) != 0) {
                    throw new IllegalArgumentException(
                        "originalInvoiceAmount '%s' disagrees with originalAmount %s"
                            .formatted(originalInvoiceAmount, originalAmount.toPlainString()));
                }
            }
            if (totalCreditMoneyValue != null && creditMoneyAmount != null) {
                BigDecimal parsed = parseMoneyAmount(totalCreditMoneyValue);
                if (parsed != null && parsed.compareTo(creditMoneyAmount) != 0) {
                    throw new IllegalArgumentException(
                        "totalCreditMoneyValue '%s' disagrees with creditMoneyAmount %s"
                            .formatted(totalCreditMoneyValue, creditMoneyAmount.toPlainString()));
                }
            }
            if (remainingInvoiceDue != null && remainingDueAmount != null) {
                BigDecimal parsed = parseMoneyAmount(remainingInvoiceDue);
                if (parsed != null && parsed.compareTo(remainingDueAmount) != 0) {
                    throw new IllegalArgumentException(
                        "remainingInvoiceDue '%s' disagrees with remainingDueAmount %s"
                            .formatted(remainingInvoiceDue, remainingDueAmount.toPlainString()));
                }
            }

            // Fill derived fields from whichever representation was supplied
            if (currency == null) {
                currency = extractCurrency(originalInvoiceAmount, totalCreditMoneyValue, remainingInvoiceDue);
            }
            if (originalAmount == null && originalInvoiceAmount != null) {
                originalAmount = parseMoneyAmount(originalInvoiceAmount);
            }
            if (creditMoneyAmount == null && totalCreditMoneyValue != null) {
                creditMoneyAmount = parseMoneyAmount(totalCreditMoneyValue);
            }
            if (remainingDueAmount == null && remainingInvoiceDue != null) {
                remainingDueAmount = parseMoneyAmount(remainingInvoiceDue);
            }
            if (originalInvoiceAmount == null && originalAmount != null) {
                originalInvoiceAmount = originalAmount.stripTrailingZeros().toPlainString()
                    + (currency != null ? " " + currency : "");
            }
            if (totalCreditMoneyValue == null && creditMoneyAmount != null) {
                totalCreditMoneyValue = creditMoneyAmount.stripTrailingZeros().toPlainString()
                    + (currency != null ? " " + currency : "");
            }
            if (remainingInvoiceDue == null && remainingDueAmount != null) {
                remainingInvoiceDue = remainingDueAmount.stripTrailingZeros().toPlainString()
                    + (currency != null ? " " + currency : "");
            }
        }

        public WalletDrawdownResponseDto(
            String walletId,
            String originalInvoiceAmount,
            BigDecimal totalCreditsDrawn,
            String totalCreditMoneyValue,
            String remainingInvoiceDue,
            boolean fullyCovered
        ) {
            this(
                walletId,
                originalInvoiceAmount,
                totalCreditsDrawn,
                totalCreditMoneyValue,
                remainingInvoiceDue,
                fullyCovered,
                null,
                null,
                null,
                null
            );
        }

        public static WalletDrawdownResponseDto from(com.saas.pricing.core.model.wallet.WalletDrawdownResult result) {
            java.util.Objects.requireNonNull(result, "WalletDrawdownResult cannot be null");
            return new WalletDrawdownResponseDto(
                result.walletId(),
                result.originalInvoiceAmount().toString(),
                result.totalCreditsDrawn(),
                result.totalCreditMoneyValue().toString(),
                result.remainingInvoiceDue().toString(),
                result.isFullyCoveredByCredits(),
                result.originalInvoiceAmount().currency().code(),
                result.originalInvoiceAmount().amount(),
                result.totalCreditMoneyValue().amount(),
                result.remainingInvoiceDue().amount()
            );
        }

        private static String extractCurrency(String... candidates) {
            if (candidates == null) return null;
            for (String s : candidates) {
                if (s != null) {
                    // Money.parse is the single authority on the rendered format; the DTO must not
                    // re-derive it by splitting on the last space, which is how the two drifted.
                    int idx = s.lastIndexOf(' ');
                    if (idx >= 0 && idx < s.length() - 1) {
                        return com.saas.pricing.core.model.Money.parse(s).currency().code();
                    }
                }
            }
            return null;
        }

        private static BigDecimal parseMoneyAmount(String s) {
            if (s == null) return null;
            try {
                return com.saas.pricing.core.model.Money.parse(s).amount();
            } catch (IllegalArgumentException e) {   // NumberFormatException extends it
                throw new IllegalArgumentException(
                    "Cannot parse monetary amount from '%s': %s".formatted(s, e.getMessage()), e);
            }
        }
    }

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
