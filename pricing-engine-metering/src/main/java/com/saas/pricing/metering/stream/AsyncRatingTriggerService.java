package com.saas.pricing.metering.stream;

import com.saas.pricing.core.engine.PricingEngine;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
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

import java.math.BigDecimal;
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
    /**
     * Durable memory of what each window has been charged, so a late event charges only the
     * difference and a restart does not re-charge the window.
     */
    private final com.saas.pricing.metering.spi.RatingClaimStore ratingClaimStore;
    /** In-JVM single-flight: only one thread per window performs the charge in this process. */
    private final InMemoryIdempotencyStore inFlightCharges = new InMemoryIdempotencyStore();

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
        this(meteringEngine, pricingEngine, walletRepository, walletDrawdownEngine, executor,
            new com.saas.pricing.metering.spi.impl.InMemoryRatingClaimStore());
    }

    /**
     * Full constructor. Supply the JDBC claim store in a clustered deployment: it is what makes the
     * window-charge decision atomic across nodes.
     */
    public AsyncRatingTriggerService(
        UsageMeteringEngine meteringEngine,
        PricingEngine pricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine,
        ExecutorService executor,
        com.saas.pricing.metering.spi.RatingClaimStore ratingClaimStore
    ) {
        this.meteringEngine = Objects.requireNonNull(meteringEngine, "meteringEngine cannot be null");
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine cannot be null");
        this.walletRepository = Optional.ofNullable(walletRepository);
        this.walletDrawdownEngine = Optional.ofNullable(walletDrawdownEngine);
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.ratingClaimStore = Objects.requireNonNull(ratingClaimStore, "ratingClaimStore cannot be null");
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
        //
        // The winner clears its entry as soon as the outcome is known (see below), so a loser can
        // observe the entry vanishing between its failed claim and its lookup. That is not an error:
        // the charge already finished, so the loser re-attempts the claim and, failing that, simply
        // evaluates the window itself. Correctness never depends on winning this race - the durable
        // claim's compare-and-set does - the in-flight registry only exists to share a concurrent
        // outcome.
        RatingCharge charge = new RatingCharge();
        RatingCharge inFlight = null;
        boolean registered = false;
        for (int attempt = 0; attempt < 64 && !registered && inFlight == null; attempt++) {
            if (inFlightCharges.putResultIfAbsent(chargeKey, charge)) {
                registered = true;
            } else {
                inFlight = inFlightCharges.findResult(chargeKey, RatingCharge.class).orElse(null);
            }
        }

        if (inFlight != null) {
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

        if (!registered) {
            // Spinning losers fall through here after the bounded attempts. Running the charge on
            // the caller's thread is safe: the durable claim serialises the delta.
            return CompletableFuture.completedFuture(
                performCharge(tenantId, customerId, planCode, window, currency, chargeKey));
        }

        // The in-flight entry exists only to make concurrent duplicates await the same outcome. As
        // soon as that outcome is known it is dropped, so a LATER call re-evaluates the window
        // against the durable claim - which is what turns a late event into a delta charge and a
        // retry into a zero charge. Leaving the completed future in place made the first charge
        // permanent and every later call a replay, the under-billing this service exists to stop.
        charge.outcome().whenComplete((result, error) -> inFlightCharges.removeResult(chargeKey, charge));

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    WalletDrawdownResult result = performCharge(tenantId, customerId, planCode, window, currency, chargeKey);
                    charge.outcome().complete(result);
                    return result;
                } catch (RuntimeException | Error e) {
                    // Release the claim on failure so a legitimate retry is not treated as a duplicate.
                    inFlightCharges.removeResult(chargeKey, charge);
                    charge.outcome().completeExceptionally(e);
                    throw e;
                }
            }, executor);
        } catch (RuntimeException e) {
            // The executor can reject the task (e.g. already shut down) before the supplier ever runs.
            // The claim was taken synchronously above, so it must be handed back or the window could
            // never be charged again.
            inFlightCharges.removeResult(chargeKey, charge);
            charge.outcome().completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Rates the window and draws down only the amount not already charged for it.
     *
     * <p>The durable claim is what makes a late event safe: ingest accepts it, the next rating
     * recomputes a larger total, and the difference between the new total and the recorded one is
     * charged. The claim is moved with an atomic compare-and-set before the drawdown, so two nodes
     * rating the same window cannot both charge the same delta - the loser re-reads and charges
     * zero. A retry after a restart likewise finds the claim and charges nothing.</p>
     */
    private WalletDrawdownResult performCharge(
        TenantId tenantId,
        CustomerId customerId,
        PlanCode planCode,
        TimeWindow window,
        CurrencyUnit currency,
        String claimKey
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
        Money newTotal = pricingResult.finalTotal();

        while (true) {
            Optional<com.saas.pricing.metering.spi.RatingClaimStore.Charged> previous =
                ratingClaimStore.find(tenantId, claimKey);
            if (previous.isPresent() && !previous.get().currency().equals(currency.code())) {
                throw new IllegalStateException(
                    "Rating claim for window %s..%s is recorded in %s but the charge is in %s; "
                        .formatted(window.startTime(), window.endTime(),
                            previous.get().currency(), currency.code())
                        + "convert the claim or the charge before rating");
            }
            BigDecimal alreadyCharged = previous
                .map(com.saas.pricing.metering.spi.RatingClaimStore.Charged::amount)
                .orElse(BigDecimal.ZERO);
            BigDecimal difference = newTotal.amount().subtract(alreadyCharged);
            Money delta = difference.signum() > 0 ? Money.of(difference, currency) : Money.zero(currency);

            com.saas.pricing.metering.spi.RatingClaimStore.Charged updated =
                new com.saas.pricing.metering.spi.RatingClaimStore.Charged(
                    newTotal.amount(), currency.code(), window.endTime());

            if (!ratingClaimStore.compareAndSet(tenantId, claimKey, previous, updated)) {
                // Another node moved the claim first. Re-read and recompute the delta from the new
                // base instead of charging the stale difference.
                continue;
            }

            try {
                return drawDown(tenantId, customerId, delta, pricingResult, window);
            } catch (RuntimeException | Error e) {
                // Hand the claim back so a retry can still charge the delta. Best effort: if another
                // node has already moved it, this caller's failure is the only one that matters.
                if (previous.isPresent()) {
                    ratingClaimStore.compareAndSet(tenantId, claimKey, Optional.of(updated), previous.get());
                } else {
                    ratingClaimStore.remove(tenantId, claimKey, updated);
                }
                throw e;
            }
        }
    }

    private WalletDrawdownResult drawDown(
        TenantId tenantId,
        CustomerId customerId,
        Money amountToDraw,
        PricingResult pricingResult,
        TimeWindow window
    ) {
        WalletRepository repo = walletRepository.get();
        WalletDrawdownEngine engine = walletDrawdownEngine.get();

        // Balance write and ledger rows must commit together. A find/save pair loses concurrent
        // charges (last write wins) and leaves a debit with no ledger row if the process dies
        // between the two calls; updateAtomicallyAndRecord serialises per wallet and records inside
        // the same unit of work.
        var atomicDrawdown = new java.util.concurrent.atomic.AtomicReference<WalletDrawdownResult>();
        var transactions = new java.util.ArrayList<com.saas.pricing.core.model.wallet.DrawdownTransaction>();

        var updated = repo.updateAtomicallyAndRecord(tenantId, customerId,
            wallet -> {
                WalletDrawdownResult drawdown = engine.applyDrawdown(
                    wallet,
                    pricingResult.calculationId(),
                    amountToDraw,
                    window.endTime()
                );
                atomicDrawdown.set(drawdown);
                transactions.clear();
                transactions.addAll(drawdown.transactions());
                return drawdown.updatedWallet();
            },
            transactions);

        if (updated.isEmpty()) {
            throw new IllegalStateException(
                "No wallet found for customer '%s' in tenant '%s'".formatted(customerId.value(), tenantId.value())
            );
        }
        return atomicDrawdown.get();
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

        return CompletableFuture.supplyAsync(() -> meteringEngine.ingest(event), executor)
            .thenCompose(ingestionResult -> {
                if (!ingestionResult.isAccepted()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                // Chained, never join()ed: joining an inner task submitted to the same executor
                // deadlocks a bounded pool (pool size 1 deadlocks trivially) and starves larger
                // ones. Composition also means no worker thread ever waits on another task.
                return aggregateRateAndDrawdownAsync(
                        event.tenantId(), event.customerId().get(), planCode, window, currency)
                    .thenApply(Optional::of);
            });
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
