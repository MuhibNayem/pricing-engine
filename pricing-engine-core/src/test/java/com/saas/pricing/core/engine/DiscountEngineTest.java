package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.DiscountScope;
import com.saas.pricing.core.model.DiscountStackingRule;
import com.saas.pricing.core.model.DiscountType;
import com.saas.pricing.core.model.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountEngineTest {

    private final DiscountEngine discountEngine = new DiscountEngine();
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Waterfall discounts should apply sequentially against remaining balance")
    void testWaterfallDiscounts() {
        Money gross = Money.of("100.00", CurrencyUnit.USD);

        // Priority 1: 10% off ($10.00 discount -> $90.00 balance)
        var d1 = new Discount("P10", DiscountType.PERCENTAGE, BigDecimal.valueOf(10),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.WATERFALL, 1, Optional.empty(), Optional.empty());

        // Priority 2: $20.00 off ($20.00 discount -> $70.00 balance)
        var d2 = new Discount("FIXED20", DiscountType.FIXED_AMOUNT, BigDecimal.valueOf(20),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.WATERFALL, 2, Optional.empty(), Optional.empty());

        var outcome = discountEngine.applyDiscounts(gross, List.of(d1, d2), now);

        assertThat(outcome.totalDiscount().amount()).isEqualByComparingTo("30.00");
        assertThat(outcome.netAmount().amount()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("Additive discounts should calculate percentages against original gross")
    void testAdditiveDiscounts() {
        Money gross = Money.of("100.00", CurrencyUnit.USD);

        var d1 = new Discount("ADD10", DiscountType.PERCENTAGE, BigDecimal.valueOf(10),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.ADDITIVE, 1, Optional.empty(), Optional.empty());

        var d2 = new Discount("ADD5", DiscountType.PERCENTAGE, BigDecimal.valueOf(5),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.ADDITIVE, 2, Optional.empty(), Optional.empty());

        var outcome = discountEngine.applyDiscounts(gross, List.of(d1, d2), now);

        // 10% of 100 = 10, 5% of 100 = 5 -> Total discount = 15.00
        assertThat(outcome.totalDiscount().amount()).isEqualByComparingTo("15.00");
        assertThat(outcome.netAmount().amount()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("Exclusive discount rule should choose the best offer for the customer")
    void testExclusiveBestOffer() {
        Money gross = Money.of("100.00", CurrencyUnit.USD);

        // Offer 1: $15 off
        var d1 = new Discount("OFF15", DiscountType.FIXED_AMOUNT, BigDecimal.valueOf(15),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.EXCLUSIVE, 1, Optional.empty(), Optional.empty());

        // Offer 2: 25% off ($25 off)
        var d2 = new Discount("OFF25PCT", DiscountType.PERCENTAGE, BigDecimal.valueOf(25),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.EXCLUSIVE, 2, Optional.empty(), Optional.empty());

        var outcome = discountEngine.applyDiscounts(gross, List.of(d1, d2), now);

        // Should pick the 25% ($25.00) discount
        assertThat(outcome.totalDiscount().amount()).isEqualByComparingTo("25.00");
        assertThat(outcome.netAmount().amount()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("Discount should be capped when maxCap is configured")
    void testDiscountCap() {
        Money gross = Money.of("200.00", CurrencyUnit.USD);

        // 50% discount capped at $50.00
        var d = new Discount("HALF_OFF_CAPPED", DiscountType.PERCENTAGE, BigDecimal.valueOf(50),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.WATERFALL, 1,
            Optional.of(Money.of("50.00", CurrencyUnit.USD)), Optional.empty());

        var outcome = discountEngine.applyDiscounts(gross, List.of(d), now);

        assertThat(outcome.totalDiscount().amount()).isEqualByComparingTo("50.00");
        assertThat(outcome.netAmount().amount()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("an exclusive discount competes with every other discount, not just exclusives")
    void exclusiveChoosesAmongAllDiscounts() {
        Money gross = Money.of("100.00", CurrencyUnit.USD);

        // The exclusive offer is worth $15; a plain waterfall offer is worth $40. "Exclusive" means
        // nothing stacks with the chosen offer, not that the larger non-exclusive offer disappears.
        var exclusive = new Discount("EXCL_15", DiscountType.FIXED_AMOUNT, BigDecimal.valueOf(15),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.EXCLUSIVE, 1,
            Optional.empty(), Optional.empty());
        var plain = new Discount("BIG_40", DiscountType.FIXED_AMOUNT, BigDecimal.valueOf(40),
            DiscountScope.INVOICE_TOTAL, Optional.empty(), DiscountStackingRule.WATERFALL, 2,
            Optional.empty(), Optional.empty());

        var outcome = discountEngine.applyDiscounts(gross, List.of(exclusive, plain), now);

        assertThat(outcome.totalDiscount().amount()).isEqualByComparingTo("40.00");
        assertThat(outcome.netAmount().amount()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("a fixed-amount discount in another currency is refused, not reinterpreted")
    void fixedAmountDiscountCurrencyMismatchRefused() {
        Money gross = Money.of("100.00", CurrencyUnit.EUR);
        var usdCoupon = Discount.fixedAmount("USD_20", Money.of("20.00", CurrencyUnit.USD));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                discountEngine.applyDiscounts(gross, List.of(usdCoupon), now))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("USD")
            .hasMessageContaining("EUR");
    }

    @Test
    @DisplayName("a FREE_UNITS discount on an invoice total is refused at construction")
    void freeUnitsCannotApplyToAnInvoiceTotal() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new Discount("FREE", DiscountType.FREE_UNITS, BigDecimal.TEN, DiscountScope.INVOICE_TOTAL,
                    Optional.empty(), DiscountStackingRule.WATERFALL, 1, Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("line item");
    }
}
