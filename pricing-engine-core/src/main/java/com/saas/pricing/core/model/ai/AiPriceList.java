package com.saas.pricing.core.model.ai;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A versioned snapshot of a provider's token prices.
 *
 * <p>The snapshot is the unit of staleness: it carries the moment the figures were published, so a
 * seller can be told their rate card is out of date rather than discovering it on an invoice.
 *
 * <p>AI rate cards are denominated in the provider's currency because providers publish in USD. A
 * seller presenting in another currency converts at payment - which is why the FX layer exists
 * downstream and why this class does not try to guess a rate.
 */
public record AiPriceList(
    String provider,
    String currencyCode,
    Instant effectiveFrom,
    Instant sourceAsOf,
    Map<String, ModelPrice> models
) implements Serializable {

    /** How old a price list may be before it is treated as stale. */
    public static final java.time.Duration DEFAULT_STALENESS_THRESHOLD = java.time.Duration.ofDays(30);

    public AiPriceList {
        Objects.requireNonNull(provider, "provider cannot be null");
        Objects.requireNonNull(currencyCode, "currencyCode cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(sourceAsOf, "sourceAsOf cannot be null");
        Objects.requireNonNull(models, "models cannot be null");
        if (models.isEmpty()) {
            throw new IllegalArgumentException("A price list with no models cannot be rated against");
        }
        models = new LinkedHashMap<>(models);
    }

    public static AiPriceList of(String provider, String currencyCode,
                                 Instant effectiveFrom, Instant sourceAsOf,
                                 Collection<ModelPrice> models) {
        Map<String, ModelPrice> byId = new LinkedHashMap<>();
        for (ModelPrice price : models) {
            ModelPrice clash = byId.put(price.modelId(), price);
            if (clash != null && !clash.equals(price)) {
                throw new IllegalArgumentException(
                    "Price list lists " + price.modelId() + " twice with different prices");
            }
        }
        return new AiPriceList(provider, currencyCode, effectiveFrom, sourceAsOf, byId);
    }

    public Optional<ModelPrice> priceFor(String modelId) {
        return Optional.ofNullable(models.get(modelId));
    }

    public Set<String> modelIds() {
        return Set.copyOf(models.keySet());
    }

    public int size() {
        return models.size();
    }

    /**
     * True when this snapshot is older than {@code now - threshold}.
     *
     * <p>Staleness is reported rather than enforced. Refusing to rate against an old price list
     * would take the product down; telling the operator it is old lets them decide.
     */
    public boolean isStale(Instant now, java.time.Duration threshold) {
        return now.isAfter(sourceAsOf.plus(threshold));
    }

    public boolean isStale(Instant now) {
        return isStale(now, DEFAULT_STALENESS_THRESHOLD);
    }

    /** How long ago the provider published these figures. */
    public java.time.Duration age(Instant now) {
        return java.time.Duration.between(sourceAsOf, now);
    }

    /**
     * Compares this snapshot against a newer one and reports what changed.
     *
     * <p>Used on sync: a seller needs to know which models moved before the next invoice, not just
     * that "something changed".
     */
    public record Diff(List<String> added, List<String> removed, List<PriceChange> changed) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }

    /** A single model's price movement between two snapshots. */
    public record PriceChange(String modelId, String field, BigDecimal from, BigDecimal to) {
        public BigDecimal delta() {
            return to.subtract(from);
        }
    }

    public Diff diffAgainst(AiPriceList newer) {
        Objects.requireNonNull(newer, "newer must not be null");
        List<String> added = newer.models.keySet().stream()
            .filter(id -> !models.containsKey(id)).sorted().toList();
        List<String> removed = models.keySet().stream()
            .filter(id -> !newer.models.containsKey(id)).sorted().toList();

        List<PriceChange> changed = new java.util.ArrayList<>();
        for (Map.Entry<String, ModelPrice> entry : models.entrySet()) {
            ModelPrice after = newer.models.get(entry.getKey());
            if (after == null) {
                continue;
            }
            ModelPrice before = entry.getValue();
            if (before.promptPerMillion().compareTo(after.promptPerMillion()) != 0) {
                changed.add(new PriceChange(entry.getKey(), "promptPerMillion",
                    before.promptPerMillion(), after.promptPerMillion()));
            }
            if (before.completionPerMillion().compareTo(after.completionPerMillion()) != 0) {
                changed.add(new PriceChange(entry.getKey(), "completionPerMillion",
                    before.completionPerMillion(), after.completionPerMillion()));
            }
            if (before.effectiveCachedPrice().compareTo(after.effectiveCachedPrice()) != 0) {
                changed.add(new PriceChange(entry.getKey(), "cachedPerMillion",
                    before.effectiveCachedPrice(), after.effectiveCachedPrice()));
            }
        }
        return new Diff(added, removed, List.copyOf(changed));
    }
}