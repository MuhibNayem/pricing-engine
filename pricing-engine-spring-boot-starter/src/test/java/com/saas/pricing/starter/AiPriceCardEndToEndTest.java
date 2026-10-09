package com.saas.pricing.starter;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.ai.AiPriceCardRenderer;
import com.saas.pricing.core.model.ai.ModelPrice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rendered AI rate card must produce the intended price through the real evaluator.
 *
 * <p>This lives in the starter rather than in {@code core} because it needs both the renderer and
 * the SpEL evaluator, and the evaluator module depends on {@code core} - a core test cannot reach
 * it without a dependency cycle.
 *
 * <p>The point is that a rate card which <em>looks</em> correct but evaluates to the wrong number is
 * worse than one that is obviously broken: it bills real customers incorrectly and nothing errors.
 */
class AiPriceCardEndToEndTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant SOURCE = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("rendered formula evaluates to the intended price at zero markup")
    void evaluatesAtZeroMarkup() {
        var price = new ModelPrice("claude-3-5-sonnet",
            new BigDecimal("3.00"), new BigDecimal("15.00"),
            Optional.of(new BigDecimal("0.30")), SOURCE, "provider-price-page");
        var item = AiPriceCardRenderer.renderItems(
            AiPriceListFactory.of(price), BigDecimal.ZERO, USD, "ANTH").getFirst();

        var evaluator = new com.saas.pricing.evaluator.SpelFormulaExpressionEvaluator();
        var priced = evaluator.evaluate(
            ((PricingModel.DynamicFormulaModel) item.pricingModel()).expression(),
            Map.of("promptTokens", new BigDecimal("1000000"),
                "completionTokens", new BigDecimal("500000"),
                "cachedTokens", BigDecimal.ZERO),
            2);

        // 1M prompt @ $3/M = $3.00; 0.5M completion @ $15/M = $7.50
        assertThat(priced).isEqualByComparingTo("10.50");
    }

    @Test
    @DisplayName("a 20% markup shows up in the evaluated price")
    void markupIsReflected() {
        var price = new ModelPrice("m", new BigDecimal("3.00"), new BigDecimal("15.00"),
            Optional.of(new BigDecimal("3.00")), SOURCE, "s");
        var item = AiPriceCardRenderer.renderItems(
            AiPriceListFactory.of(price), new BigDecimal("0.20"), USD, "ANTH").getFirst();

        var evaluator = new com.saas.pricing.evaluator.SpelFormulaExpressionEvaluator();
        var priced = evaluator.evaluate(
            ((PricingModel.DynamicFormulaModel) item.pricingModel()).expression(),
            Map.of("promptTokens", new BigDecimal("1000000"),
                "completionTokens", BigDecimal.ZERO,
                "cachedTokens", BigDecimal.ZERO),
            2);

        // 1M prompt @ $3/M with 20% markup = $3.60
        assertThat(priced).isEqualByComparingTo("3.60");
    }

    /** Small helper so the test reads cleanly; the price list needs three timestamps. */
    private static final class AiPriceListFactory {
        static com.saas.pricing.core.model.ai.AiPriceList of(ModelPrice price) {
            return com.saas.pricing.core.model.ai.AiPriceList.of("anthropic", "USD",
                SOURCE, SOURCE, List.of(price));
        }
    }
}
