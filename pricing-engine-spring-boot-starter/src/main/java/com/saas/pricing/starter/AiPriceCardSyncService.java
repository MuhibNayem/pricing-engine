package com.saas.pricing.starter;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.ai.AiPriceCardRenderer;
import com.saas.pricing.core.model.ai.AiPriceList;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Keeps an AI rate card in step with the provider's published prices.
 *
 * <h2>Why this exists</h2>
 * Providers change prices and release new models without notice. A rate card typed in by hand
 * therefore drifts three ways: it overcharges, it undercharges, or — worst — it silently bills a
 * brand-new model at <em>zero</em>, because there is no line for it. None of those raise an error;
 * they show up as a wrong invoice months later.
 *
 * <p>So the prices live in a versioned {@link AiPriceList} and the rate card is rendered from it.
 *
 * <h2>What this service deliberately does not do</h2>
 * It does not <em>refuse</em> to render a stale list. Taking the product down because a price feed
 * is 45 days old is worse than rendering it and telling the operator. {@link SyncResult} reports
 * staleness and the diff so a human decides; the engine never decides on their behalf.
 */
public class AiPriceCardSyncService {

    private final Clock clock;

    public AiPriceCardSyncService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * The outcome of a sync check.
     *
     * @param priceList the snapshot evaluated
     * @param stale      whether it is older than the threshold
     * @param age        how old the published figures are
     * @param diff       what changed against the list the rate card was last rendered from;
     *                   empty when there is nothing to compare against
     */
    public record SyncResult(
        AiPriceList priceList,
        boolean stale,
        Duration age,
        AiPriceList.Diff diff
    ) {
        public boolean hasChanges() {
            return diff != null && !diff.isEmpty();
        }
    }

    /**
     * Compares an incoming price list against the one currently rendered.
     *
     * @param current the list the rate card was last rendered from; may be {@code null} on first sync
     */
    public SyncResult check(AiPriceList incoming, AiPriceList current,
                            Duration stalenessThreshold) {
        Objects.requireNonNull(incoming, "incoming must not be null");
        Instant now = clock.instant();

        AiPriceList.Diff diff = current == null
            ? new AiPriceList.Diff(List.copyOf(incoming.models().keySet()), List.of(), List.of())
            : current.diffAgainst(incoming);

        return new SyncResult(incoming, incoming.isStale(now, stalenessThreshold),
            incoming.age(now), diff);
    }

    /**
     * Renders the rate card items, applying the configured markup.
     *
     * @param targetCurrency must match the price list's currency; converting belongs in FxBooking
     */
    public List<RatePlanItem> render(AiPriceList priceList, BigDecimal markup,
                                     CurrencyUnit targetCurrency, String itemCodePrefix) {
        return AiPriceCardRenderer.renderItems(priceList, markup, targetCurrency, itemCodePrefix);
    }

    /**
     * Renders the rates the next invoice would use, without building rate-card items.
     *
     * <p>Exists because a seller needs to answer "what will this customer actually be charged at
     * the new upstream prices" <em>before</em> the sync lands. Showing a rate change as opaque
     * item codes is not reviewable; showing the per-token rates is.
     *
     * @return one entry per model, with the per-token rates and the formula behind them
     */
    public List<com.saas.pricing.core.model.ai.AiPriceCardRenderer.RenderedModel> previewRates(
            AiPriceList priceList, BigDecimal markup) {
        return AiPriceCardRenderer.render(priceList, markup);
    }

    /** Convenience for a pass-through rate card. */
    public List<RatePlanItem> render(AiPriceList priceList, CurrencyUnit currency, String prefix) {
        return render(priceList, BigDecimal.ZERO, currency, prefix);
    }
}