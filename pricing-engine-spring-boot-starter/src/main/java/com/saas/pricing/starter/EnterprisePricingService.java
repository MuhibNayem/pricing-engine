package com.saas.pricing.starter;

import com.saas.pricing.core.engine.BatchPricingEngine;
import com.saas.pricing.core.engine.EntitlementVerifier;
import com.saas.pricing.core.engine.PricingEngine;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.model.entitlement.EntitlementDecision;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import com.saas.pricing.core.spi.EntitlementRepository;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.starter.context.ScopedPricingContext;
import com.saas.pricing.starter.metrics.PricingEngineMetrics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Enterprise service orchestrator for SaaS pricing evaluation, quota verification, and wallet drawdown.
 */
public class EnterprisePricingService {

    private final PricingEngine pricingEngine;
    private final BatchPricingEngine batchPricingEngine;
    private final WalletRepository walletRepository;
    private final WalletDrawdownEngine walletDrawdownEngine;
    private final EntitlementRepository entitlementRepository;
    private final EntitlementVerifier entitlementVerifier;
    private final PricingEngineMetrics metrics;

    public EnterprisePricingService(
        PricingEngine pricingEngine,
        BatchPricingEngine batchPricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine,
        EntitlementRepository entitlementRepository,
        EntitlementVerifier entitlementVerifier,
        PricingEngineMetrics metrics
    ) {
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine cannot be null");
        this.batchPricingEngine = batchPricingEngine;
        this.walletRepository = walletRepository;
        this.walletDrawdownEngine = walletDrawdownEngine;
        this.entitlementRepository = entitlementRepository;
        this.entitlementVerifier = entitlementVerifier;
        this.metrics = metrics;
    }

    /**
     * Evaluates a pricing request with context extraction and metrics tracking.
     */
    public PricingResult evaluate(PricingRequest request) {
        Objects.requireNonNull(request, "PricingRequest cannot be null");

        // Context enrichment from ScopedPricingContext if missing
        PricingRequest effectiveRequest = enrichWithScopedContext(request);
        long startTime = System.nanoTime();
        boolean success = false;

        try {
            PricingResult result = pricingEngine.evaluate(effectiveRequest);
            success = true;
            return result;
        } finally {
            if (metrics != null) {
                Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
                metrics.recordEvaluationDuration(
                    duration,
                    effectiveRequest.tenantId().value(),
                    effectiveRequest.planCode().value(),
                    success
                );
            }
        }
    }

    /**
     * Evaluates a pricing request and automatically applies prepaid credit wallet drawdown if a wallet exists.
     */
    public WalletDrawdownResult evaluateAndDrawdown(PricingRequest request) {
        PricingResult pricingResult = evaluate(request);
        if (walletRepository == null || walletDrawdownEngine == null || request.customerId().isEmpty()) {
            throw new IllegalStateException("Wallet repository or drawdown engine not configured, or customerId missing");
        }

        TenantId tenantId = request.tenantId();
        CustomerId customerId = request.customerId().get();
        Instant evalTime = request.evaluationTime().orElseGet(Instant::now);

        Wallet wallet = walletRepository.findWallet(tenantId, customerId)
            .orElseThrow(() -> new IllegalStateException(
                "No wallet found for customer '%s' in tenant '%s'".formatted(customerId.value(), tenantId.value())
            ));

        WalletDrawdownResult drawdownResult = walletDrawdownEngine.applyDrawdown(
            wallet,
            pricingResult.calculationId(),
            pricingResult.finalTotal(),
            evalTime
        );

        // Persist updated wallet and transactions
        walletRepository.save(drawdownResult.updatedWallet());
        walletRepository.recordTransactions(drawdownResult.transactions());

        if (metrics != null) {
            metrics.recordDrawdown(tenantId.value(), drawdownResult.totalCreditsDrawn().doubleValue());
        }

        return drawdownResult;
    }

    /**
     * High-throughput batch pricing evaluation across virtual threads.
     */
    public List<PricingResult> evaluateBatch(List<PricingRequest> requests) {
        Objects.requireNonNull(requests, "requests cannot be null");
        if (batchPricingEngine != null) {
            return batchPricingEngine.evaluateBatch(requests);
        }
        return requests.stream().map(this::evaluate).toList();
    }

    /**
     * Verifies real-time entitlement and quota access.
     */
    public EntitlementDecision verifyEntitlement(
        TenantId tenantId,
        CustomerId customerId,
        String featureKey,
        BigDecimal requestedUnits,
        Instant timestamp
    ) {
        if (entitlementRepository == null || entitlementVerifier == null) {
            throw new IllegalStateException("Entitlement verifier or repository not configured");
        }

        Instant evalTime = timestamp != null ? timestamp : Instant.now();
        Optional<CustomerEntitlement> entOpt = entitlementRepository.findEntitlement(tenantId, customerId, featureKey, evalTime);

        EntitlementDecision decision;
        if (entOpt.isEmpty()) {
            decision = EntitlementDecision.denied(featureKey, com.saas.pricing.core.model.entitlement.FeatureType.BOOLEAN, "No entitlement found for feature");
        } else {
            decision = entitlementVerifier.verify(entOpt.get(), requestedUnits, evalTime);
        }

        if (metrics != null) {
            metrics.recordEntitlementCheck(featureKey, decision.allowed());
        }

        return decision;
    }

    private PricingRequest enrichWithScopedContext(PricingRequest request) {
        Optional<TenantId> scopedTenant = ScopedPricingContext.currentTenant();
        Optional<CustomerId> scopedCustomer = ScopedPricingContext.currentCustomer();

        PricingRequest.Builder builder = PricingRequest.builder()
            .tenantId(scopedTenant.orElse(request.tenantId()))
            .planCode(request.planCode())
            .targetCurrency(request.targetCurrency())
            .attributes(request.globalAttributes());

        request.evaluationTime().ifPresent(builder::evaluationTime);
        request.prorationWindow().ifPresent(builder::prorationWindow);

        if (request.customerId().isPresent()) {
            builder.customerId(request.customerId().get());
        } else if (scopedCustomer.isPresent()) {
            builder.customerId(scopedCustomer.get());
        }

        for (var item : request.items()) {
            builder.item(item);
        }
        for (var disc : request.discounts()) {
            builder.discount(disc);
        }

        return builder.build();
    }
}
