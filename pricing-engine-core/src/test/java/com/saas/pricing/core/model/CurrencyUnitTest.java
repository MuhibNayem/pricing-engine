package com.saas.pricing.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Currency identity.
 *
 * <p>Two {@code CurrencyUnit} values that denote the same currency must be {@code equals}, or every
 * {@code Money} operation across them fails its currency-isolation check. In particular
 * {@code of("CREDITS")} used to build a 2-digit lookalike that was unequal to the 4-digit constant.
 */
class CurrencyUnitTest {

    @Test
    @DisplayName("known non-ISO tokens round-trip through of() to the same value")
    void knownTokensRoundTrip() {
        assertThat(CurrencyUnit.of("CREDITS")).isEqualTo(CurrencyUnit.CREDITS);
        assertThat(CurrencyUnit.of("credits")).isEqualTo(CurrencyUnit.CREDITS);
        assertThat(CurrencyUnit.of(" credits ")).isEqualTo(CurrencyUnit.CREDITS);
        assertThat(CurrencyUnit.of("TOKENS")).isEqualTo(CurrencyUnit.TOKENS);
        assertThat(CurrencyUnit.of("CREDITS").defaultFractionDigits()).isEqualTo(4);
    }

    @Test
    @DisplayName("construction normalises case, so a hand-built unit equals the constant")
    void constructionNormalisesCase() {
        assertThat(new CurrencyUnit("usd", 2)).isEqualTo(CurrencyUnit.USD);
        assertThat(new CurrencyUnit(" Usd ", 2)).isEqualTo(CurrencyUnit.USD);
        assertThat(CurrencyUnit.of("usd")).isEqualTo(CurrencyUnit.USD);
    }

    @Test
    @DisplayName("ISO currencies keep their declared precision")
    void isoPrecision() {
        assertThat(CurrencyUnit.of("JPY").defaultFractionDigits()).isZero();
        assertThat(CurrencyUnit.of("EUR")).isEqualTo(CurrencyUnit.EUR);
    }

    @Test
    @DisplayName("unknown tokens default to two digits and remain usable")
    void unknownTokensDefault() {
        assertThat(CurrencyUnit.of("TENANT_CREDIT")).isEqualTo(new CurrencyUnit("TENANT_CREDIT", 2));
    }

    @Test
    @DisplayName("a blank code is refused")
    void blankCodeRefused() {
        assertThatThrownBy(() -> new CurrencyUnit("  ", 2))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
