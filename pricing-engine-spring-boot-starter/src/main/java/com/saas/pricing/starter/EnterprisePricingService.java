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
import com.saas.pricing.core.model.entitlement.EntitlementReconciler;
import com.saas.pricing.core.model.entitlement.EntitlementDecision;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import com.saas.pricing.core.spi.EntitlementEventRepository;
import com.saas.pricing.core.spi.EntitlementRepository;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.starter.context.ScopedPricingContext;
import com.saas.pricing.starter.metrics.PricingEngineMetrics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * Append-only entitlement event stream. Optional: a deployment still on the legacy mutable
     * rows has no stream, and reconciliation simply reports that there is nothing to compare.
     */
    private final EntitlementEventRepository entitlementEventRepository;

    /**
     * Transactional outbox. Optional in the sense that a deployment may not have one wired, in which
     * case draws are recorded but not announced - never the other way round.
     */
    private final com.saas.pricing.core.model.event.OutboxRepository outboxRepository;
    private final EntitlementVerifier entitlementVerifier;
    private final PricingEngineMetrics metrics;
    private final java.time.Clock clock;

    /**
     * Recently computed ratings, keyed by calculation id.
     *
     * <p>An invoice must be raised from a rating the engine actually produced, never from amounts
     * supplied by the caller - otherwise a client can invoice itself whatever it likes. Bounded so
     * it cannot become a memory leak.
     */
    private static final int RECENT_RESULT_CAPACITY = 1_000;
    private final java.util.Map<String, PricingResult> recentResults =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PricingResult> eldest) {
                    return size() > RECENT_RESULT_CAPACITY;
                }
            });

    public EnterprisePricingService(
        PricingEngine pricingEngine,
        BatchPricingEngine batchPricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine,
        EntitlementRepository entitlementRepository,
        EntitlementVerifier entitlementVerifier,
        PricingEngineMetrics metrics
    ) {
        this(pricingEngine, batchPricingEngine, walletRepository, walletDrawdownEngine,
            entitlementRepository, entitlementVerifier, metrics, java.time.Clock.systemUTC(), null, null);
    }

    public EnterprisePricingService(
        PricingEngine pricingEngine,
        BatchPricingEngine batchPricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine,
        EntitlementRepository entitlementRepository,
        EntitlementVerifier entitlementVerifier,
        PricingEngineMetrics metrics,
        java.time.Clock clock,
        EntitlementEventRepository entitlementEventRepository,
        com.saas.pricing.core.model.event.OutboxRepository outboxRepository
    ) {
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine cannot be null");
        this.batchPricingEngine = batchPricingEngine;
        this.walletRepository = walletRepository;
        this.walletDrawdownEngine = walletDrawdownEngine;
        this.entitlementRepository = entitlementRepository;
        this.entitlementEventRepository = entitlementEventRepository;
        this.outboxRepository = outboxRepository;
        this.entitlementVerifier = entitlementVerifier;
        this.metrics = metrics;
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
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
            recentResults.put(result.calculationId(), result);
            success = true;
            return result;
        } finally {
            if (metrics != null) {
                Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
                metrics.recordEvaluationDuration(
                    duration,
                    success ? "success" : "error",
                    "rated",
                    "unspecified"
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
        Instant evalTime = request.evaluationTime().orElseGet(clock::instant);

        // Read-modify-write must be atomic. A plain findWallet/applyDrawdown/save cycle lets two
        // concurrent drawdowns both read the same balance and both write it back, so a customer
        // can consume far more credit than they are charged for. updateAtomically serialises the
        // cycle per wallet (ConcurrentHashMap.compute in-memory, SELECT ... FOR UPDATE in JDBC).
        var atomicDrawdown = new java.util.concurrent.atomic.AtomicReference<WalletDrawdownResult>();
        // The transaction list is created empty and filled by the mutator below; the repository
        // reads it AFTER applying the mutator, so passing it by reference is what lets the balance
        // update and its ledger rows commit together.
        var ledgerEntries = new java.util.ArrayList<com.saas.pricing.core.model.wallet.DrawdownTransaction>();

        // The retry deliberately wraps the repository call rather than living inside it: the
        // repository method is transactional, so a retry must re-enter the proxy to get a fresh
        // transaction. Retrying inside the aborted one could never succeed.
        com.saas.pricing.core.spi.SerializationFailureRetry.execute("wallet-drawdown", () ->
        walletRepository.updateAtomicallyAndRecord(tenantId, customerId, wallet -> {
            WalletDrawdownResult result = walletDrawdownEngine.applyDrawdown(
                wallet,
                pricingResult.calculationId(),
                pricingResult.finalTotal(),
                evalTime
            );
            atomicDrawdown.set(result);
            ledgerEntries.clear();
            ledgerEntries.addAll(result.transactions());
            return result.updatedWallet();
        }, ledgerEntries)).orElseThrow(() -> new IllegalStateException(
            "No wallet found for customer '%s' in tenant '%s'".formatted(customerId.value(), tenantId.value())
        ));

        WalletDrawdownResult drawdownResult = atomicDrawdown.get();

        // Record the movement on the append-only ledger. The wallet's stored balance is a cache;
        // this entry stream is the record of truth, and reconciliation detects the two diverging.
        //
        // Written immediately after the balance commit rather than inside the same unit of work: if
        // the process dies between them the ledger is short one entry, which reconcile() reports -
        // a visible, repairable inconsistency - whereas failing the drawdown because an audit row
        // could not be written would debit nothing and charge nothing.
        //
        // The entry id is derived from the calculation, so a retry re-appends an identical entry
        // and the ledger treats that as already-recorded rather than a conflict.
        if (drawdownResult.totalCreditsDrawn().signum() != 0) {
            walletRepository.appendLedgerEntries(List.of(
                com.saas.pricing.core.model.wallet.LedgerEntry.of(
                    "led-" + pricingResult.calculationId(),
                    drawdownResult.walletId(),
                    com.saas.pricing.core.model.wallet.LedgerEntryType.DRAWDOWN,
                    drawdownResult.totalCreditsDrawn().negate(),
                    drawdownResult.totalCreditMoneyValue().negate(),
                    pricingResult.calculationId(),
                    evalTime)
            ));

            // Announce the drawdown. Without this the wallet ledger records that credit left the
            // account and no subscriber ever hears that it did - the exact failure the transactional
            // outbox exists to prevent.
            announceWalletDrawdown(tenantId, drawdownResult, pricingResult.calculationId(), evalTime);
        }

        if (metrics != null) {
            metrics.recordDrawdown(
                drawdownResult.remainingInvoiceDue().isZero() ? "settled" : "partial",
                drawdownResult.totalCreditsDrawn().doubleValue());
        }

        return drawdownResult;
    }

    /**
     * Looks up a recently computed rating by calculation id.
     *
     * <p>Used when raising an invoice, so the document is built from figures the engine produced
     * rather than from anything the caller supplied.
     */
    public Optional<PricingResult> findRatingResult(String calculationId) {
        if (calculationId == null || calculationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(recentResults.get(calculationId));
    }

    private void announceWalletDrawdown(TenantId tenantId, WalletDrawdownResult drawdown,
                                        String calculationId, Instant at) {
        if (outboxRepository == null) {
            return;
        }
        outboxRepository.enqueue(com.saas.pricing.core.model.event.DomainEventFactory.walletDrawnDown(
            tenantId.value(), drawdown.walletId(), calculationId,
            drawdown.totalCreditsDrawn().toPlainString(),
            drawdown.totalCreditMoneyValue().amount().toPlainString(),
            drawdown.totalCreditMoneyValue().currency().code(), at));
    }

    /** Reconciles derived entitlement state against the legacy stored rows.
     *
     * <p>Two sources of truth exist during the migration from mutable entitlement rows to the
     * append-only event stream. This makes the divergence visible instead of silently picking one:
     *
     * <ul>
     *   <li>{@code WRONGLY_DENIED} - the stream says the customer holds a feature the store refuses.
     *       A support incident and potentially a billing dispute.</li>
     *   <li>{@code WRONGLY_GRANTED} - access survives that the stream says was revoked. The
     *       security-relevant direction.</li>
     *   <li>{@code USAGE_MISMATCH} - access agrees, counters do not.</li>
     * </ul>
     *
     * @return the report; empty (everything in sync) when no event stream is configured
     * @throws IllegalStateException if the event repository is not wired
     */
    public EntitlementReconciler.Report reconcileEntitlements(TenantId tenantId, CustomerId customerId) {
        if (entitlementEventRepository == null || entitlementRepository == null) {
            throw new IllegalStateException(
                "Entitlement reconciliation needs both EntitlementEventRepository and EntitlementRepository");
        }
        Instant at = clock.instant();

        var events = entitlementEventRepository.findAllEvents(tenantId, customerId, Optional.empty());
        var stored = new java.util.LinkedHashMap<String, CustomerEntitlement>();
        for (CustomerEntitlement entitlement : entitlementRepository.findAllEntitlements(tenantId, customerId, at)) {
            stored.put(entitlement.featureKey(), entitlement);
        }
        return EntitlementReconciler.reconcile(tenantId, customerId, at, events, stored);
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

        Instant evalTime = timestamp != null ? timestamp : clock.instant();
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
