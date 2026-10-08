package com.saas.pricing.metering.stream;

import com.saas.pricing.core.engine.PricingEngine;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.metering.engine.UsageMeteringEngine;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Service orchestrating asynchronous ratings and wallet drawdowns
 * triggered by streaming usage events, running non-blocking on Java 25 virtual threads.
 */
public class AsyncRatingTriggerService implements AutoCloseable {

    private final UsageMeteringEngine meteringEngine;
    private final PricingEngine pricingEngine;
    private final Optional<WalletRepository> walletRepository;
    private final Optional<WalletDrawdownEngine> walletDrawdownEngine;
    private final ExecutorService executor;

    public AsyncRatingTriggerService(UsageMeteringEngine meteringEngine, PricingEngine pricingEngine) {
        this(meteringEngine, pricingEngine, null, null, Executors.newVirtualThreadPerTaskExecutor());
    }

    public AsyncRatingTriggerService(
        UsageMeteringEngine meteringEngine,
        PricingEngine pricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine
    ) {
        this(meteringEngine, pricingEngine, walletRepository, walletDrawdownEngine, Executors.newVirtualThreadPerTaskExecutor());
    }

    public AsyncRatingTriggerService(
        UsageMeteringEngine meteringEngine,
        PricingEngine pricingEngine,
        ExecutorService executor
    ) {
        this(meteringEngine, pricingEngine, null, null, executor);
    }

    public AsyncRatingTriggerService(
        UsageMeteringEngine meteringEngine,
        PricingEngine pricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine,
        ExecutorService executor
    ) {
        this.meteringEngine = Objects.requireNonNull(meteringEngine, "meteringEngine cannot be null");
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine cannot be null");
        this.walletRepository = Optional.ofNullable(walletRepository);
        this.walletDrawdownEngine = Optional.ofNullable(walletDrawdownEngine);
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    /**
     * Asynchronously evaluates a pricing request.
     */
    public CompletableFuture<PricingResult> evaluateAsync(PricingRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        return CompletableFuture.supplyAsync(() -> pricingEngine.evaluate(request), executor);
    }

    /**
     * Asynchronously aggregates usage for a time window and runs the rating engine.
     */
    public CompletableFuture<PricingResult> aggregateAndRateAsync(
        TenantId tenantId,
        Optional<CustomerId> customerId,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency
    ) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            List<BillableItemRequest> billableItems = meteringEngine.generateBillableItems(tenantId, customerId, window);

            PricingRequest.Builder builder = PricingRequest.builder()
                .tenantId(tenantId)
                .planCode(planCode)
                .targetCurrency(currency)
                .evaluationTime(window.endTime());

            customerId.ifPresent(builder::customerId);

            for (BillableItemRequest item : billableItems) {
                builder.item(item);
            }

            PricingRequest pricingRequest = builder.build();
            return pricingEngine.evaluate(pricingRequest);
        }, executor);
    }

    /**
     * Ingests a streaming event, and if accepted, immediately triggers rating evaluation.
     */
    public CompletableFuture<Optional<PricingResult>> ingestAndTriggerRatingAsync(
        MeterEvent event,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency
    ) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            IngestionResult result = meteringEngine.ingest(event);
            if (!result.isAccepted()) {
                return Optional.empty();
            }

            List<BillableItemRequest> billableItems = meteringEngine.generateBillableItems(
                event.tenantId(), event.customerId(), window
            );

            PricingRequest.Builder builder = PricingRequest.builder()
                .tenantId(event.tenantId())
                .planCode(planCode)
                .targetCurrency(currency)
                .evaluationTime(window.endTime());

            event.customerId().ifPresent(builder::customerId);
            billableItems.forEach(builder::item);

            PricingResult pricingResult = pricingEngine.evaluate(builder.build());
            return Optional.of(pricingResult);
        }, executor);
    }

    /**
     * Asynchronously aggregates usage for a time window, evaluates pricing,
     * and performs credit wallet drawdown with ledger persistence.
     */
    public CompletableFuture<WalletDrawdownResult> aggregateRateAndDrawdownAsync(
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency
    ) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");

        if (walletRepository.isEmpty() || walletDrawdownEngine.isEmpty()) {
            throw new IllegalStateException("WalletRepository and WalletDrawdownEngine must be configured for wallet drawdown");
        }

        return CompletableFuture.supplyAsync(() -> {
            List<BillableItemRequest> billableItems = meteringEngine.generateBillableItems(
                tenantId, Optional.of(customerId), window
            );

            PricingRequest.Builder builder = PricingRequest.builder()
                .tenantId(tenantId)
                .customerId(customerId)
                .planCode(planCode)
                .targetCurrency(currency)
                .evaluationTime(window.endTime());

            billableItems.forEach(builder::item);
            PricingResult pricingResult = pricingEngine.evaluate(builder.build());

            WalletRepository repo = walletRepository.get();
            WalletDrawdownEngine engine = walletDrawdownEngine.get();

            Wallet wallet = repo.findWallet(tenantId, customerId)
                .orElseThrow(() -> new IllegalStateException(
                    "No wallet found for customer '%s' in tenant '%s'".formatted(customerId.value(), tenantId.value())
                ));

            WalletDrawdownResult drawdownResult = engine.applyDrawdown(
                wallet,
                pricingResult.calculationId(),
                pricingResult.finalTotal(),
                window.endTime()
            );

            repo.save(drawdownResult.updatedWallet());
            repo.recordTransactions(drawdownResult.transactions());

            return drawdownResult;
        }, executor);
    }

    /**
     * Ingests a streaming event, and if accepted, triggers rating and wallet drawdown asynchronously.
     */
    public CompletableFuture<Optional<WalletDrawdownResult>> ingestRateAndDrawdownAsync(
        MeterEvent event,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency
    ) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");

        if (event.customerId().isEmpty()) {
            throw new IllegalArgumentException("Event must have a customerId to apply wallet drawdown");
        }

        return CompletableFuture.supplyAsync(() -> {
            IngestionResult ingestionResult = meteringEngine.ingest(event);
            if (!ingestionResult.isAccepted()) {
                return Optional.empty();
            }

            WalletDrawdownResult drawdownResult = aggregateRateAndDrawdownAsync(
                event.tenantId(),
                event.customerId().get(),
                planCode,
                window,
                currency
            ).join();

            return Optional.of(drawdownResult);
        }, executor);
    }

    /**
     * Asynchronously evaluates pricing and invokes a callback (e.g. for wallet drawdown or ledger dispatch).
     */
    public CompletableFuture<PricingResult> evaluateWithCallbackAsync(
        PricingRequest request,
        Consumer<PricingResult> callback
    ) {
        Objects.requireNonNull(request, "request cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            PricingResult result = pricingEngine.evaluate(request);
            if (callback != null) {
                callback.accept(result);
            }
            return result;
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
