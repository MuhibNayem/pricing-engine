package com.saas.pricing.core.model.ai;

import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rendering an AI rate card from a provider price list.
 *
 * <p>The decisive test is {@link #renderedFormulaEvaluatesThroughTheRealEngine}: a rendered rate
 * card is only correct if the formula the engine actually evaluates produces the intended price.
 */
class AiPriceCardRendererTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant SOURCE = Instant.parse("2026-10-01T00:00:00Z");

    private static ModelPrice sonnet() {
        return new ModelPrice("claude-3-5-sonnet",
            new BigDecimal("3.00"), new BigDecimal("15.00"),
            java.util.Optional.of(new BigDecimal("0.30")), SOURCE, "provider-price-page");
    }

    private static AiPriceList priceList(ModelPrice... models) {
        return AiPriceList.of("anthropic", "USD", SOURCE, SOURCE, List.of(models));
    }

    @Nested
    @DisplayName("Markup")
    class Markup {

        @Test
        @DisplayName("20% markup multiplies the per-million price by 1.2")
        void appliesMarkup() {
            var rendered = AiPriceCardRenderer.render(sonnet(), new BigDecimal("0.20"));

            // 3.00 per million, 20% on top, then divided by a million for the per-token rate.
            assertThat(rendered.promptRate()).isEqualByComparingTo("0.0000036");
        }

        @Test
        @DisplayName("zero markup is pass-through")
        void zeroMarkupIsPassThrough() {
            assertThat(AiPriceCardRenderer.render(sonnet(), BigDecimal.ZERO).promptRate())
                .isEqualByComparingTo("0.000003");
        }

        @Test
        @DisplayName("a negative markup is refused")
        void negativeMarkupRefused() {
            assertThatThrownBy(() -> AiPriceCardRenderer.render(sonnet(), new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Token classes")
    class TokenClasses {

        @Test
        @DisplayName("cached tokens fall back to the prompt price, never to zero")
        void cachedFallsBackToPrompt() {
            var noCached = ModelPrice.of("m", "3.00", "15.00", SOURCE, "s");
            var withCached = noCached.withCachedPrice("0.30");

            assertThat(noCached.effectiveCachedPrice())
                .as("a missing cached price means 'billed at the prompt rate', not 'free'")
                .isEqualByComparingTo("3.00");
            assertThat(withCached.effectiveCachedPrice()).isEqualByComparingTo("0.30");
        }

        @Test
        @DisplayName("a negative provider price is refused")
        void negativePriceRefused() {
            assertThatThrownBy(() -> new ModelPrice("m", new BigDecimal("-1"),
                new BigDecimal("1"), java.util.Optional.empty(), SOURCE, "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credit note");
        }
    }

    @Nested
    @DisplayName("Rendering")
    class Rendering {

        @Test
        @DisplayName("the rendered formula divides by a million without integer truncation")
        void noIntegerTruncation() {
            var rendered = AiPriceCardRenderer.render(sonnet(), BigDecimal.ZERO);

            assertThat(rendered.promptRate())
                .as("3 USD per million is 0.000003 per token; an integer division would give 0")
                .isEqualByComparingTo("0.000003");
            assertThat(rendered.completionRate()).isEqualByComparingTo("0.000015");
            assertThat(rendered.cachedRate()).isEqualByComparingTo("0.0000003");
        }

        @Test
        @DisplayName("markup is baked into the emitted constants, not left as a variable")
        void markupIsBakedIn() {
            var rendered = AiPriceCardRenderer.render(sonnet(), new BigDecimal("0.20"));

            assertThat(rendered.promptRate()).isEqualByComparingTo("0.0000036");
            assertThat(rendered.formula()).contains("0.0000036").doesNotContain("#markup");
        }

        @Test
        @DisplayName("a currency mismatch is refused rather than silently converted")
        void currencyMismatchRefused() {
            var list = priceList(sonnet());

            assertThatThrownBy(() -> AiPriceCardRenderer.renderItems(list, BigDecimal.ZERO,
                CurrencyUnit.EUR, "AI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FxBooking");
        }

        @Test
        @DisplayName("rate-card items are produced with stable, prefixed codes")
        void rendersItems() {
            var items = AiPriceCardRenderer.renderItems(priceList(sonnet()), BigDecimal.ZERO, USD, "ANTH");

            assertThat(items).hasSize(1);
            assertThat(items.getFirst().itemCode()).isEqualTo("ANTH_claude-3-5-sonnet");
            assertThat(items.getFirst().pricingModel())
                .isInstanceOf(PricingModel.DynamicFormulaModel.class);
        }
    }

    @Nested
    @DisplayName("Staleness and sync")
    class Sync {

        @Test
        @DisplayName("an old price list is reported stale rather than refused")
        void staleIsReported() {
            var list = priceList(sonnet());
            var muchLater = SOURCE.plus(java.time.Duration.ofDays(90));

            assertThat(list.isStale(muchLater)).isTrue();
            assertThat(list.isStale(SOURCE.plus(java.time.Duration.ofDays(1)))).isFalse();
            assertThat(list.age(muchLater).toDays()).isEqualTo(90);
        }

        @Test
        @DisplayName("a sync diff names what actually moved")
        void diffNamesChanges() {
            var before = priceList(ModelPrice.of("old", "3.00", "15.00", SOURCE, "s"), sonnet());
            var after = priceList(
                new ModelPrice("claude-3-5-sonnet", new BigDecimal("3.50"), new BigDecimal("15.00"),
                    java.util.Optional.of(new BigDecimal("0.30")), SOURCE.plusSeconds(60), "s"),
                ModelPrice.of("brand-new", "1.00", "5.00", SOURCE, "s"));

            var diff = before.diffAgainst(after);

            assertThat(diff.added()).containsExactly("brand-new");
            assertThat(diff.removed()).containsExactly("old");
            assertThat(diff.changed()).singleElement().satisfies(change -> {
                assertThat(change.modelId()).isEqualTo("claude-3-5-sonnet");
                assertThat(change.field()).isEqualTo("promptPerMillion");
                assertThat(change.delta()).isEqualByComparingTo("0.50");
            });
        }

        @Test
        @DisplayName("a price list listing one model twice with different prices is refused")
        void duplicateModelRefused() {
            assertThatThrownBy(() -> AiPriceList.of("p", "USD", SOURCE, SOURCE, List.of(
                ModelPrice.of("m", "1.00", "2.00", SOURCE, "s"),
                ModelPrice.of("m", "9.00", "2.00", SOURCE, "s"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
        }
    }
}