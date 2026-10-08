package com.saas.pricing.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    @DisplayName("Should add money with same currency accurately")
    void testAddition() {
        Money m1 = Money.of("10.50", CurrencyUnit.USD);
        Money m2 = Money.of("20.25", CurrencyUnit.USD);

        Money result = m1.plus(m2);

        assertThat(result.amount()).isEqualByComparingTo("30.75");
        assertThat(result.currency()).isEqualTo(CurrencyUnit.USD);
    }

    @Test
    @DisplayName("Should reject arithmetic across different currencies")
    void testCurrencyMismatch() {
        Money usd = Money.of("10.00", CurrencyUnit.USD);
        Money eur = Money.of("10.00", CurrencyUnit.EUR);

        assertThatThrownBy(() -> usd.plus(eur))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("Should multiply with banker's rounding precision")
    void testMultiply() {
        Money m = Money.of("100.00", CurrencyUnit.USD);
        Money result = m.times(new BigDecimal("0.15555"));

        assertThat(result.amount()).isEqualByComparingTo("15.555");
        assertThat(result.roundToCurrency().amount()).isEqualByComparingTo("15.56");
    }

    @Test
    @DisplayName("Should accurately divide and round")
    void testDivision() {
        Money m = Money.of("10.00", CurrencyUnit.USD);
        Money result = m.dividedBy(3);

        assertThat(result.roundTo(2, RoundingMode.HALF_EVEN).amount()).isEqualByComparingTo("3.33");
    }

    @Test
    @DisplayName("Should detect zero, positive, and negative amounts")
    void testSigns() {
        assertThat(Money.zero(CurrencyUnit.USD).isZero()).isTrue();
        assertThat(Money.of("5.00", CurrencyUnit.USD).isPositive()).isTrue();
        assertThat(Money.of("-5.00", CurrencyUnit.USD).isNegative()).isTrue();
    }
}
