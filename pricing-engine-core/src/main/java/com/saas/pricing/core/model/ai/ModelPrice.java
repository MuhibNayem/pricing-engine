package com.saas.pricing.core.model.ai;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One model's upstream token prices, as published by the model provider.
 *
 * <h2>Why a price list is data, not code</h2>
 * A hand-maintained per-model rate card does not survive a provider price release. Providers change
 * prices and add models without notice, and a seller who hardcodes them is either overcharging,
 * undercharging, or silently billing new models at zero. So the prices live in a versioned list
 * with a {@code sourceAsOf} and a {@code source}, and the rate card is <em>rendered</em> from that
 * list at a configured markup rather than typed in by hand.
 *
 * <p>Prices are per **million** tokens because that is how providers publish them; dividing by a
 * million inside the rendered formula keeps the raw upstream figure visible and auditable instead
 * of baking a pre-divided constant into a formula nobody can trace back.
 *
 * <p>Prices are never negative. A negative price would be a refund, which belongs in a credit note.
 *
 * @param modelId            provider's model identifier, e.g. {@code anthropic.claude-3-5-sonnet}
 * @param promptPerMillion   price for prompt/input tokens
 * @param completionPerMillion price for completion/output tokens
 * @param cachedPerMillion  price for cached/reasoning tokens, empty when the provider bills them
 *                           at the prompt rate or does not discount them
 * @param sourceAsOf         when the provider published this figure
 * @param source             where the figure came from, for audit
 */
public record ModelPrice(
    String modelId,
    BigDecimal promptPerMillion,
    BigDecimal completionPerMillion,
    Optional<BigDecimal> cachedPerMillion,
    Instant sourceAsOf,
    String source
) implements Serializable {

    public ModelPrice {
        Objects.requireNonNull(modelId, "modelId cannot be null");
        Objects.requireNonNull(promptPerMillion, "promptPerMillion cannot be null");
        Objects.requireNonNull(completionPerMillion, "completionPerMillion cannot be null");
        Objects.requireNonNull(cachedPerMillion, "cachedPerMillion cannot be null");
        Objects.requireNonNull(sourceAsOf, "sourceAsOf cannot be null");
        source = source == null ? "" : source;

        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId cannot be blank");
        }
        if (promptPerMillion.signum() < 0 || completionPerMillion.signum() < 0) {
            throw new IllegalArgumentException(
                "A model price cannot be negative; a refund belongs in a credit note");
        }
        cachedPerMillion.ifPresent(cached -> {
            if (cached.signum() < 0) {
                throw new IllegalArgumentException("A cached-token price cannot be negative");
            }
        });
    }

    public static ModelPrice of(String modelId, String promptPerMillion, String completionPerMillion,
                                Instant sourceAsOf, String source) {
        return new ModelPrice(modelId, new BigDecimal(promptPerMillion),
            new BigDecimal(completionPerMillion), Optional.empty(), sourceAsOf, source);
    }

    public ModelPrice withCachedPrice(String cachedPerMillion) {
        return new ModelPrice(modelId, promptPerMillion, completionPerMillion,
            Optional.of(new BigDecimal(cachedPerMillion)), sourceAsOf, source);
    }

    /**
     * The cached price to use, falling back to the prompt price when the provider publishes none.
     *
     * <p>Falling back rather than billing cached tokens at zero is the whole point: a missing field
     * means "billed at the prompt rate", not "free".
     */
    public BigDecimal effectiveCachedPrice() {
        return cachedPerMillion.orElse(promptPerMillion);
    }
}