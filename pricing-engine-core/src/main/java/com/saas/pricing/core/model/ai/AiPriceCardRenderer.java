package com.saas.pricing.core.model.ai;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RatePlanItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders an AI rate card from a provider price list and a markup.
 *
 * <h2>The markup is applied to the price, not to the formula</h2>
 * Markup is folded into a numeric constant at render time, so the emitted formula stays readable
 * and the resulting number can be traced back to the upstream figure it came from. Leaving markup
 * as a runtime variable would mean a rate card whose price silently depends on state the document
 * does not record.
 *
 * <h2>Formulas use {@code #divide}, never {@code /}</h2>
 * SpEL divides BigDecimals at the maximum scale of the two operands, so a plain division silently
 * collapses to an integer. The evaluator rejects {@code /} outright for exactly this reason, so
 * every formula rendered here uses {@code #divide}.
 */
public final class AiPriceCardRenderer {

    /** Tokens per unit in provider price lists; the arithmetic depends on this being exact. */
    public static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

    private AiPriceCardRenderer() {
        // static utility
    }

    /**
     * A rendered model price: the formula plus the constants behind it.
     *
     * @param modelId        provider model identifier
     * @param formula        SpEL expression the rating engine evaluates
     * @param promptRate     rendered price per prompt token, after markup
     * @param completionRate rendered price per completion token, after markup
     * @param cachedRate     rendered price per cached token, after markup
     */
    public record RenderedModel(
        String modelId,
        String formula,
        BigDecimal promptRate,
        BigDecimal completionRate,
        BigDecimal cachedRate,
        String sourceAsOf
    ) {
    }

    /**
     * Applies a markup to an upstream price.
     *
     * @param markup {@code 0.15} means 15% on top; {@code 0} is pass-through. Must not be negative.
     * <p>Private on purpose: markup is applied once, at render time, so every emitted figure has
     * exactly one derivation. Exposing it would let a caller apply it twice or skip it.
     */
    private static BigDecimal applyMarkup(BigDecimal upstreamPrice, BigDecimal markup) {
        Objects.requireNonNull(upstreamPrice, "upstreamPrice cannot be null");
        Objects.requireNonNull(markup, "markup cannot be null");
        if (markup.signum() < 0) {
            throw new IllegalArgumentException("Markup cannot be negative: " + markup);
        }
        // 18 decimal places keeps per-token precision far beyond any currency's minor unit.
        return upstreamPrice.multiply(BigDecimal.ONE.add(markup)).setScale(18, RoundingMode.HALF_EVEN);
    }

    /**
     * Renders one model's formula and its per-token rates.
     *
     * <p>The three token classes are priced separately because providers bill them differently.
     * Collapsing them into one rate is how a seller ends up charging prompt price for cached tokens,
     * or worse, the reverse.
     */
    public static RenderedModel render(ModelPrice price, BigDecimal markup) {
        Objects.requireNonNull(price, "price cannot be null");

        BigDecimal promptRate = applyMarkup(price.promptPerMillion(), markup)
            .divide(TOKENS_PER_MILLION, 18, RoundingMode.HALF_EVEN);
        BigDecimal completionRate = applyMarkup(price.completionPerMillion(), markup)
            .divide(TOKENS_PER_MILLION, 18, RoundingMode.HALF_EVEN);
        BigDecimal cachedRate = applyMarkup(price.effectiveCachedPrice(), markup)
            .divide(TOKENS_PER_MILLION, 18, RoundingMode.HALF_EVEN);

        String formula = "(promptTokens * " + promptRate.toPlainString() + ")"
            + " + (completionTokens * " + completionRate.toPlainString() + ")"
            + " + (cachedTokens * " + cachedRate.toPlainString() + ")";

        return new RenderedModel(price.modelId(), formula, promptRate, completionRate, cachedRate,
            price.sourceAsOf().toString());
    }

    /** Renders every model in a price list. */
    public static List<RenderedModel> render(AiPriceList priceList, BigDecimal markup) {
        Objects.requireNonNull(priceList, "priceList cannot be null");
        List<RenderedModel> rendered = new ArrayList<>(priceList.size());
        for (ModelPrice price : priceList.models().values()) {
            rendered.add(render(price, markup));
        }
        return rendered;
    }

    /**
     * Renders the price list into rate-card items the pricing engine can evaluate directly.
     *
     * @param priceList      upstream prices
     * @param markup         configured markup
     * @param targetCurrency the currency the rate card is denominated in; must match the price list
     * @param itemCodePrefix prefix for generated item codes, so several providers can coexist
     */
    public static List<RatePlanItem> renderItems(AiPriceList priceList, BigDecimal markup,
                                                 CurrencyUnit targetCurrency, String itemCodePrefix) {
        Objects.requireNonNull(priceList, "priceList cannot be null");
        Objects.requireNonNull(targetCurrency, "targetCurrency cannot be null");
        if (!priceList.currencyCode().equalsIgnoreCase(targetCurrency.code())) {
            // Rendering a USD price list into a EUR rate card would need a stored rate. Guessing one
            // would misprice every invoice, so the mismatch is refused here.
            throw new IllegalArgumentException(
                "Price list is in " + priceList.currencyCode() + " but the rate card is in "
                    + targetCurrency.code() + "; converting belongs in FxBooking, not in rendering");
        }
        String prefix = itemCodePrefix == null || itemCodePrefix.isBlank() ? "AI" : itemCodePrefix;

        List<RatePlanItem> items = new ArrayList<>();
        for (RenderedModel model : render(priceList, markup)) {
            items.add(RatePlanItem.of(
                prefix + "_" + model.modelId(),
                "AI tokens for " + model.modelId(),
                PricingModel.DynamicFormulaModel.of(model.formula(),
                    "promptTokens", "completionTokens", "cachedTokens"),
                targetCurrency));
        }
        return items;
    }
}