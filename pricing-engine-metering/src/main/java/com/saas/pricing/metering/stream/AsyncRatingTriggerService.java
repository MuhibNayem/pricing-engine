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
import com.saas.pricing.metering.spi.impl.InMemoryIdempotencyStore;

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
 *
 * <h2>Wallet drawdown idempotency</h2>
 * <p>The wallet drawdown path is idempotent at window granularity. Each charge is claimed through an
 * atomic {@code putIfAbsent} against an {@link InMemoryIdempotencyStore} keyed by tenant + customer +
 * plan + window (+ caller-supplied calculation id). Exactly one caller performs the charge; every
 * other caller for the same key awaits that caller's completion handle and receives the identical
 * {@link WalletDrawdownResult} instead of deducting the wallet a second time. If the charge fails,
 * the claim is released with an atomic compare-and-remove so a retry is not rejected as a duplicate.</p>
 */
public class AsyncRatingTriggerService implements AutoCloseable {

    private final UsageMeteringEngine meteringEngine;
    private final PricingEngine pricingEngine;
    private final Optional<WalletRepository> walletRepository;
    private final Optional<WalletDrawdownEngine> walletDrawdownEngine;
    private final ExecutorService executor;
    private final InMemoryIdempotencyStore ratingIdempotencyStore;

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
        this(meteringEngine, pricingEngine, walletRepository, walletDrawdownEngine, executor, new InMemoryIdempotencyStore());
    }

    /**
     * Full constructor allowing a shared idempotency store. When several service instances must not
     * charge the same window twice, pass the same store instance to all of them (or back it with a
     * durable implementation in a clustered deployment).
     */
    public AsyncRatingTriggerService(
        UsageMeteringEngine meteringEngine,
        PricingEngine pricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine,
        ExecutorService executor,
        InMemoryIdempotencyStore ratingIdempotencyStore
    ) {
        this.meteringEngine = Objects.requireNonNull(meteringEngine, "meteringEngine cannot be null");
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine cannot be null");
        this.walletRepository = Optional.ofNullable(walletRepository);
        this.walletDrawdownEngine = Optional.ofNullable(walletDrawdownEngine);
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.ratingIdempotencyStore = Objects.requireNonNull(ratingIdempotencyStore, "ratingIdempotencyStore cannot be null");
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
     *
     * <p>Idempotent at window granularity: calling this twice for the same
     * {@code (tenant, customer, plan, window)} performs exactly one wallet charge and returns the
     * original result to every caller. Use
     * {@link #aggregateRateAndDrawdownAsync(TenantId, CustomerId, PlanCode, TimeWindow, CurrencyUnit, String)}
     * when the caller supplies its own calculation id and needs to distinguish separate calculations
     * over the same window.</p>
     */
    public CompletableFuture<WalletDrawdownResult> aggregateRateAndDrawdownAsync(
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency
    ) {
        return aggregateRateAndDrawdownAsync(tenantId, customerId, planCode, window, currency, null);
    }

    /**
     * Asynchronously aggregates, rates and draws down, keyed for idempotency by
     * {@code tenant + customer + plan + window start/end + calculationId}.
     *
     * <p>Exactly one caller per key performs the wallet deduction. Concurrent or later callers for the
     * same key return the original {@link WalletDrawdownResult} without deducting again. If the charge
     * throws, the key is released so the retry is not rejected as a duplicate.</p>
     *
     * @param calculationId caller-supplied idempotency token; when {@code null} the key reduces to
     *                     window granularity, so a window is billed at most once.
     */
    public CompletableFuture<WalletDrawdownResult> aggregateRateAndDrawdownAsync(
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency,
        String calculationId
    ) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");

        if (walletRepository.isEmpty() || walletDrawdownEngine.isEmpty()) {
            throw new IllegalStateException("WalletRepository and WalletDrawdownEngine must be configured for wallet drawdown");
        }

        String chargeKey = buildChargeKey(tenantId, customerId, planCode, window, calculationId);

        // Claim first: only the winner performs the charge, everyone else awaits its outcome.
        RatingCharge charge = new RatingCharge();
        if (!ratingIdempotencyStore.putResultIfAbsent(chargeKey, charge)) {
            RatingCharge inFlight = ratingIdempotencyStore.findResult(chargeKey, RatingCharge.class)
                .orElseThrow(() -> new IllegalStateException(
                    "Rating idempotency claim for key '%s' disappeared while in flight".formatted(chargeKey)
                ));
            // Unwrap the CompletionException so callers see the original failure.
            return inFlight.outcome().handle((result, error) -> {
                if (error == null) {
                    return result;
                }
                Throwable cause = (error instanceof java.util.concurrent.CompletionException && error.getCause() != null)
                    ? error.getCause()
                    : error;
                if (cause instanceof RuntimeException runtimeCause) {
                    throw runtimeCause;
                }
                if (cause instanceof Error errorCause) {
                    throw errorCause;
                }
                throw new java.util.concurrent.CompletionException(cause);
            });
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    WalletDrawdownResult result = performCharge(tenantId, customerId, planCode, window, currency);
                    charge.outcome().complete(result);
                    return result;
                } catch (RuntimeException | Error e) {
                    // Release the claim on failure so a legitimate retry is not treated as a duplicate.
                    ratingIdempotencyStore.removeResult(chargeKey, charge);
                    charge.outcome().completeExceptionally(e);
                    throw e;
                }
            }, executor);
        } catch (RuntimeException e) {
            // The executor can reject the task (e.g. already shut down) before the supplier ever runs.
            // The claim was taken synchronously above, so it must be handed back or the window could
            // never be charged again.
            ratingIdempotencyStore.removeResult(chargeKey, charge);
            charge.outcome().completeExceptionally(e);
            throw e;
        }
    }

    private WalletDrawdownResult performCharge(
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency
    ) {
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
    }

    /**
     * Builds the window-level charge key: tenant + customer + plan + window start/end + optional
     * caller-supplied calculation id.
     */
    private static String buildChargeKey(
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        TimeWindow window,
        String calculationId
    ) {
        return "%s::%s::%s::%s::%s::%s".formatted(
            tenantId.value(),
            customerId.value(),
            planCode.value(),
            window.startTime(),
            window.endTime(),
            calculationId != null ? calculationId : "-"
        );
    }

    /**
     * Completion handle published as the idempotency claim. Identity-compared by the store so that a
     * released claim can never be removed by a caller that no longer owns it.
     */
    private static final class RatingCharge {
        private final CompletableFuture<WalletDrawdownResult> outcome = new CompletableFuture<>();

        CompletableFuture<WalletDrawdownResult> outcome() {
            return outcome;
        }
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
