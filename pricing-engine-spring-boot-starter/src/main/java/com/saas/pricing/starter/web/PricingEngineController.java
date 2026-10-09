package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.DiscountScope;
import com.saas.pricing.core.model.DiscountType;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.EntitlementDecision;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.PricingEngineProperties;
import com.saas.pricing.starter.web.dto.PricingDtos;
import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone REST API controller for enterprise pricing rating, entitlements, and credit drawdowns.
 */
@RestController
@RequestMapping("/api/v1/pricing")
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
public class PricingEngineController {

    /**
     * A batch is a convenience, not a denial-of-service vector: the batch engine submits one
     * virtual thread per element, so an unbounded list is an unbounded resource request.
     */
    static final int MAX_BATCH_SIZE = 1_000;

    private final EnterprisePricingService pricingService;
    private final com.saas.pricing.starter.tenant.TenantGuard tenantGuard;
    private final boolean allowRequestDiscounts;

    public PricingEngineController(
        EnterprisePricingService pricingService,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard,
        PricingEngineProperties properties
    ) {
        this.pricingService = pricingService;
        this.tenantGuard = tenantGuard;
        this.allowRequestDiscounts = properties.isAllowRequestDiscounts();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "Enterprise Pricing Engine"));
    }

    @PostMapping("/evaluate")
    public ResponseEntity<PricingDtos.PricingEvaluationResponseDto> evaluate(
        @Valid @RequestBody PricingDtos.PricingEvaluationRequestDto requestDto
    ) {
        PricingRequest request = mapToDomainRequest(requestDto);
        PricingResult result = pricingService.evaluate(request);
        return ResponseEntity.ok(mapToResponseDto(result));
    }

    @PostMapping("/evaluate-batch")
    public ResponseEntity<List<PricingDtos.PricingEvaluationResponseDto>> evaluateBatch(
        @RequestBody List<PricingDtos.PricingEvaluationRequestDto> requestDtos
    ) {
        if (requestDtos == null || requestDtos.isEmpty()) {
            throw new IllegalArgumentException("Batch request cannot be empty");
        }
        if (requestDtos.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                "Batch request of " + requestDtos.size() + " exceeds the maximum of " + MAX_BATCH_SIZE);
        }
        List<PricingRequest> requests = requestDtos.stream().map(this::mapToDomainRequest).toList();
        List<PricingResult> results = pricingService.evaluateBatch(requests);
        return ResponseEntity.ok(results.stream().map(this::mapToResponseDto).toList());
    }

    @PostMapping("/entitlements/verify")
    public ResponseEntity<PricingDtos.EntitlementCheckResponseDto> verifyEntitlement(
        @Valid @RequestBody PricingDtos.EntitlementCheckRequestDto dto
    ) {
        EntitlementDecision decision = pricingService.verifyEntitlement(
            TenantId.of(tenantGuard.verify(dto.tenantId())),
            CustomerId.of(dto.customerId()),
            dto.featureKey(),
            dto.requestedUnits(),
            Instant.now()
        );

        return ResponseEntity.ok(new PricingDtos.EntitlementCheckResponseDto(
            decision.allowed(),
            decision.featureKey(),
            decision.requestedQuantity(),
            decision.currentUsage(),
            decision.remainingQuota(),
            decision.isOverage(),
            decision.overageQuantity(),
            decision.reason()
        ));
    }

    @PostMapping("/wallets/drawdown")
    public ResponseEntity<PricingDtos.WalletDrawdownResponseDto> drawdown(
        @Valid @RequestBody PricingDtos.WalletDrawdownRequestDto dto
    ) {
        PricingRequest.Builder builder = PricingRequest.builder()
            .tenantId(tenantGuard.verify(dto.tenantId()))
            .customerId(dto.customerId())
            .planCode(dto.planCode())
            .targetCurrency(CurrencyUnit.of(dto.targetCurrency() != null ? dto.targetCurrency() : "USD"));

        if (dto.items() != null) {
            for (var item : dto.items()) {
                builder.item(item.itemCode(), item.quantity(), item.attributes() != null ? item.attributes() : Map.of());
            }
        }

        WalletDrawdownResult result = pricingService.evaluateAndDrawdown(builder.build());
        return ResponseEntity.ok(new PricingDtos.WalletDrawdownResponseDto(
            result.walletId(),
            result.originalInvoiceAmount().toString(),
            result.totalCreditsDrawn(),
            result.totalCreditMoneyValue().toString(),
            result.remainingInvoiceDue().toString(),
            result.isFullyCoveredByCredits()
        ));
    }

    private PricingRequest mapToDomainRequest(PricingDtos.PricingEvaluationRequestDto dto) {
        CurrencyUnit targetCurrency = CurrencyUnit.of(dto.targetCurrency() != null ? dto.targetCurrency() : "USD");
        PricingRequest.Builder builder = PricingRequest.builder()
            .tenantId(tenantGuard.verify(dto.tenantId()))
            .planCode(dto.planCode())
            .targetCurrency(targetCurrency);

        if (dto.customerId() != null && !dto.customerId().isBlank()) {
            builder.customerId(dto.customerId());
        }

        if (dto.attributes() != null) {
            builder.attributes(dto.attributes());
        }

        if (dto.items() != null) {
            for (var item : dto.items()) {
                builder.item(item.itemCode(), item.quantity(), item.attributes() != null ? item.attributes() : Map.of());
            }
        }

        // Discount authoring is a catalog/contract concern, not a rating input. Allowing it here
        // would let any caller name its own discount and price its own usage, so it is refused
        // unless the operator has explicitly enabled it for trusted internal callers.
        if (dto.discounts() != null && !dto.discounts().isEmpty() && !allowRequestDiscounts) {
            throw new IllegalArgumentException(
                "Caller-supplied discounts are not accepted by this endpoint. Configure "
                    + "pricing.engine.allow-request-discounts=true only for trusted internal callers; "
                    + "discounts belong in the rate card or a contract override.");
        }

        if (dto.discounts() != null) {
            for (var disc : dto.discounts()) {
                DiscountType type = "FIXED_AMOUNT".equalsIgnoreCase(disc.type()) ? DiscountType.FIXED_AMOUNT : DiscountType.PERCENTAGE;
                DiscountScope scope = "LINE_ITEM".equalsIgnoreCase(disc.scope()) ? DiscountScope.LINE_ITEM : DiscountScope.INVOICE_TOTAL;
                if (type == DiscountType.PERCENTAGE) {
                    if (scope == DiscountScope.LINE_ITEM) {
                        builder.discount(Discount.percentageItem(disc.code(), disc.value(), disc.targetItemCode()));
                    } else {
                        builder.discount(Discount.percentage(disc.code(), disc.value()));
                    }
                } else {
                    Money money = Money.of(disc.value(), targetCurrency);
                    if (scope == DiscountScope.LINE_ITEM) {
                        builder.discount(Discount.fixedAmountItem(disc.code(), money, disc.targetItemCode()));
                    } else {
                        builder.discount(Discount.fixedAmount(disc.code(), money));
                    }
                }
            }
        }

        return builder.build();
    }

    private PricingDtos.PricingEvaluationResponseDto mapToResponseDto(PricingResult result) {
        List<PricingDtos.RatedLineItemDto> lineDtos = new ArrayList<>();
        for (var item : result.lineItems()) {
            lineDtos.add(new PricingDtos.RatedLineItemDto(
                item.itemCode(),
                item.rawQuantity(),
                item.billableQuantity(),
                item.grossAmount().toString(),
                item.discountAmount().toString(),
                item.netAmount().toString(),
                item.taxAmount().toString(),
                item.finalAmount().toString()
            ));
        }

        return new PricingDtos.PricingEvaluationResponseDto(
            result.calculationId(),
            result.tenantId().value(),
            result.customerId().map(CustomerId::value).orElse(null),
            result.planCode().value(),
            result.currency().code(),
            result.totalGross().toString(),
            result.totalDiscount().toString(),
            result.totalNet().toString(),
            result.totalTax().toString(),
            result.finalTotal().toString(),
            lineDtos
        );
    }
}
