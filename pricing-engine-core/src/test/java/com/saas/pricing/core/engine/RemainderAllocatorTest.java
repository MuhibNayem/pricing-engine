package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemainderAllocatorTest {

    @Test
    @DisplayName("Should distribute $10.00 across 3 equal weights with zero remainder drift")
    void testDistribute10DollarsAcross3Items() {
        Money total = Money.of("10.00", CurrencyUnit.USD);
        List<BigDecimal> weights = List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        List<Money> allocated = RemainderAllocator.allocate(total, weights);

        assertThat(allocated).hasSize(3);
        assertThat(allocated.get(0).amount()).isEqualByComparingTo("3.34");
        assertThat(allocated.get(1).amount()).isEqualByComparingTo("3.33");
        assertThat(allocated.get(2).amount()).isEqualByComparingTo("3.33");

        Money sum = allocated.stream().reduce(Money.zero(CurrencyUnit.USD), Money::plus);
        assertThat(sum.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Should distribute exact weighted shares when weights divide evenly")
    void testEvenWeights() {
        Money total = Money.of("100.00", CurrencyUnit.USD);
        List<BigDecimal> weights = List.of(
            BigDecimal.valueOf(20),
            BigDecimal.valueOf(30),
            BigDecimal.valueOf(50)
        );

        List<Money> allocated = RemainderAllocator.allocate(total, weights);

        assertThat(allocated.get(0).amount()).isEqualByComparingTo("20.00");
        assertThat(allocated.get(1).amount()).isEqualByComparingTo("30.00");
        assertThat(allocated.get(2).amount()).isEqualByComparingTo("50.00");

        Money sum = allocated.stream().reduce(Money.zero(CurrencyUnit.USD), Money::plus);
        assertThat(sum.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Should handle small amount distribution across larger number of items")
    void testSmallAmountDistribution() {
        Money total = Money.of("0.05", CurrencyUnit.USD);
        List<BigDecimal> weights = List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        List<Money> allocated = RemainderAllocator.allocate(total, weights);

        Money sum = allocated.stream().reduce(Money.zero(CurrencyUnit.USD), Money::plus);
        assertThat(sum.amount()).isEqualByComparingTo("0.05");
    }
}
