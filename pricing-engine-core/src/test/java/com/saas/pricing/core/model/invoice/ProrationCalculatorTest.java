package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two-sided proration.
 *
 * <p>Worked from Stripe's published example: a $10 plan upgraded to $20 halfway through a monthly
 * period yields a $5 credit for the unused old plan and a $10 debit for the remaining new one.
 * Emitting only one side is the bug this guards against.
 */
class ProrationCalculatorTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant MID = Instant.parse("2026-10-16T00:00:00Z");   // 15 days into 30
    private static final Instant END = Instant.parse("2026-10-31T00:00:00Z");

    private static List<ProrationAdjustment> upgrade() {
        return ProrationCalculator.forPlanChange("SEATS", "BASIC", Money.of("10.00", USD),
            "PRO", Money.of("20.00", USD), START, END, MID, USD);
    }

    @Nested
    @DisplayName("The pair")
    class ThePair {

        @Test
        @DisplayName("a mid-period upgrade produces both a credit and a debit")
        void producesBothSides() {
            var adjustments = upgrade();

            assertThat(adjustments).hasSize(2);
            assertThat(adjustments.get(0).kind()).isEqualTo(ProrationAdjustment.Kind.CREDIT);
            assertThat(adjustments.get(0).sourcePlanCode()).isEqualTo("BASIC");
            assertThat(adjustments.get(1).kind()).isEqualTo(ProrationAdjustment.Kind.DEBIT);
            assertThat(adjustments.get(1).sourcePlanCode()).isEqualTo("PRO");
        }

        @Test
        @DisplayName("half a period halves both sides: $5 credit, $10 debit")
        void amountsMatchTheWorkedExample() {
            var adjustments = upgrade();

            assertThat(adjustments.get(0).amount().amount()).isEqualByComparingTo("5.00");
            assertThat(adjustments.get(1).amount().amount()).isEqualByComparingTo("10.00");
            assertThat(adjustments.get(0).factor()).isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("an upgrade nets positive, a downgrade nets negative")
        void netReflectsDirection() {
            assertThat(ProrationCalculator.netChange(upgrade()).amount())
                .as("customer pays 5 more for the remaining half")
                .isEqualByComparingTo("5.00");

            var downgrade = ProrationCalculator.forPlanChange("SEATS", "PRO", Money.of("20.00", USD),
                "BASIC", Money.of("10.00", USD), START, END, MID, USD);

            assertThat(ProrationCalculator.netChange(downgrade).amount())
                .as("customer is owed 5 for the remaining half")
                .isEqualByComparingTo("-5.00");
        }

        @Test
        @DisplayName("neither side self-settles")
        void nothingSelfSettles() {
            assertThat(upgrade()).allSatisfy(adjustment -> {
                assertThat(adjustment.automatic())
                    .as("refunding a credit or billing a debit is the host's commercial decision")
                    .isFalse();
            });
        }

        @Test
        @DisplayName("amounts are stored positive; direction lives in the kind")
        void directionIsNotCarriedBySign() {
            var credit = upgrade().get(0);

            assertThat(credit.amount().amount()).isPositive();
            assertThat(credit.signedAmount().amount()).isNegative();
            assertThat(credit.isCredit()).isTrue();
        }

        @Test
        @DisplayName("a negative magnitude is rejected outright")
        void negativeMagnitudeRejected() {
            assertThatThrownBy(() -> new ProrationAdjustment(ProrationAdjustment.Kind.CREDIT,
                "SEATS", "BASIC", Money.of("-5.00", USD), new BigDecimal("0.5"),
                MID, END, START, END, false, ProrationReason.PLAN_CHANGED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direction belongs to the kind");
        }

        @Test
        @DisplayName("an adjustment that claims to self-settle is rejected")
        void automaticRejected() {
            assertThatThrownBy(() -> new ProrationAdjustment(ProrationAdjustment.Kind.CREDIT,
                "SEATS", "BASIC", Money.of("5.00", USD), new BigDecimal("0.5"),
                MID, END, START, END, true, ProrationReason.PLAN_CHANGED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commercial decision");
        }
    }

    @Nested
    @DisplayName("Edges")
    class Edges {

        @Test
        @DisplayName("a change at the very start bills the whole period at the new price, without a credit")
        void changeAtPeriodStart() {
            var adjustments = ProrationCalculator.forPlanChange("SEATS", "BASIC", Money.of("10.00", USD),
                "PRO", Money.of("20.00", USD), START, END, START, USD);

            assertThat(adjustments).hasSize(1);
            assertThat(adjustments.getFirst().isCredit())
                .as("the old plan was never in force this period, so there is nothing to credit")
                .isFalse();
            assertThat(adjustments.getFirst().amount().amount()).isEqualByComparingTo("20.00");
            assertThat(adjustments.getFirst().factor()).isEqualByComparingTo("1.0");
        }

        @Test
        @DisplayName("a change at the period end produces nothing")
        void changeAtPeriodEnd() {
            var adjustments = ProrationCalculator.forPlanChange("SEATS", "BASIC", Money.of("10.00", USD),
                "PRO", Money.of("20.00", USD), START, END, END, USD);

            assertThat(adjustments)
                .as("a change effective at the period end belongs to the next period")
                .isEmpty();
        }

        @Test
        @DisplayName("a span outside the period is refused")
        void spanOutsidePeriodRejected() {
            assertThatThrownBy(() -> new ProrationAdjustment(ProrationAdjustment.Kind.CREDIT,
                "SEATS", "BASIC", Money.of("5.00", USD), new BigDecimal("0.5"),
                START.minus(Duration.ofDays(1)), END, START, END, false, ProrationReason.PLAN_CHANGED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("falls outside the billing period");
        }

        @Test
        @DisplayName("a cross-currency proration is refused rather than guessed")
        void crossCurrencyRejected() {
            assertThatThrownBy(() -> ProrationCalculator.forPlanChange("SEATS", "BASIC",
                Money.of("10.00", USD), "PRO", Money.of("20.00", CurrencyUnit.EUR),
                START, END, MID, USD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FxBooking");
        }

        @Test
        @DisplayName("a backdated change is charged for the period that actually applied")
        void backdatedUsesEffectiveTime() {
            var adjustments = ProrationCalculator.forBackdatedChange("SEATS", "BASIC",
                Money.of("10.00", USD), "PRO", Money.of("20.00", USD),
                START, END, MID, END, USD);

            assertThat(adjustments.get(0).factor())
                .as("noticed a month late, the customer still gets the mid-period treatment")
                .isEqualByComparingTo("0.5");
            assertThat(adjustments.get(0).amount().amount()).isEqualByComparingTo("5.00");
        }

        @Test
        @DisplayName("an 'effective' time after it was recorded is refused")
        void backdatedOrderingEnforced() {
            assertThatThrownBy(() -> ProrationCalculator.forBackdatedChange("SEATS", "BASIC",
                Money.of("10.00", USD), "PRO", Money.of("20.00", USD),
                START, END, END, MID, USD))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Invoice rendering")
    class Rendering {

        @Test
        @DisplayName("a credit renders as a negative invoice line")
        void creditRendersNegative() {
            var line = upgrade().get(0).toLineItem();

            assertThat(line.amount().amount()).isEqualByComparingTo("-5.00");
            assertThat(line.description()).contains("Unused credit");
            assertThat(line.metadata()).containsEntry("kind", "CREDIT");
        }

        @Test
        @DisplayName("a debit renders as a positive invoice line")
        void debitRendersPositive() {
            var line = upgrade().get(1).toLineItem();

            assertThat(line.amount().amount()).isEqualByComparingTo("10.00");
            assertThat(line.description()).contains("Remaining charge");
        }

        @Test
        @DisplayName("an upgrade's two lines sum to the net change")
        void linesSumToNet() {
            var adjustments = upgrade();
            var sum = adjustments.get(0).toLineItem().amount()
                .plus(adjustments.get(1).toLineItem().amount())
                .roundToCurrency();

            assertThat(sum.amount()).isEqualByComparingTo("5.00");
            assertThat(sum).isEqualTo(ProrationCalculator.netChange(adjustments));
        }
    }

    @Nested
    @DisplayName("Effective before the period")
    class EffectiveBeforeThePeriod {

        @Test
        @DisplayName("a change effective at or before the period start credits nothing")
        void noOldPlanCreditWhenTheOldPlanWasNeverInForce() {
            // The old plan was not in force during this period, so a full-period credit refunds a
            // charge the period never carried. Only the new plan's full debit belongs here.
            var adjustments = ProrationCalculator.forPlanChange("SEATS", "BASIC", Money.of("10.00", USD),
                "PRO", Money.of("20.00", USD), START, END, START.minusSeconds(3600), USD);

            assertThat(adjustments).hasSize(1);
            assertThat(adjustments.getFirst().kind()).isEqualTo(ProrationAdjustment.Kind.DEBIT);
            assertThat(adjustments.getFirst().amount().amount()).isEqualByComparingTo("20.00");
            assertThat(adjustments.getFirst().factor()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a change effective at the exact period start credits nothing")
        void exactStartIsNotInsideThePeriod() {
            var adjustments = ProrationCalculator.forPlanChange("SEATS", "BASIC", Money.of("10.00", USD),
                "PRO", Money.of("20.00", USD), START, END, START, USD);

            assertThat(adjustments).hasSize(1);
            assertThat(adjustments.getFirst().isCredit()).isFalse();
        }

        @Test
        @DisplayName("a change effective after the period end produces nothing")
        void afterThePeriodProducesNothing() {
            var adjustments = ProrationCalculator.forPlanChange("SEATS", "BASIC", Money.of("10.00", USD),
                "PRO", Money.of("20.00", USD), START, END, END.plusSeconds(60), USD);

            assertThat(adjustments).isEmpty();
        }

        @Test
        @DisplayName("negative plan prices are refused instead of being masked with abs()")
        void negativePricesRefused() {
            assertThatThrownBy(() -> ProrationCalculator.forPlanChange("SEATS", "BASIC",
                Money.of("-10.00", USD), "PRO", Money.of("20.00", USD), START, END, MID, USD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
        }
    }
}