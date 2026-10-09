package com.saas.pricing.core.model;

import com.saas.pricing.core.engine.DefaultPricingEngine;
import com.saas.pricing.core.engine.EntitlementVerifier;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryContractOverrideRepository;
import com.saas.pricing.core.spi.impl.InMemoryWalletRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Billing-cycle anchoring.
 *
 * <p>Before this, an engine could not express <em>which period</em> it was charging: there was no
 * anchor, no short-month clamping, and {@link FlatFeeModel#cadence()} was accepted and then
 * ignored. That made an annual plan and a monthly plan indistinguishable.
 */
class BillingCycleAnchorTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;

    @Nested
    @DisplayName("Short-month and leap-year clamping")
    class Clamping {

        @Test
        @DisplayName("a day-31 anchor renews Feb 28 (or 29), then Mar 31, then Apr 30")
        void day31AnchorClampsAndNeverSkips() {
            var anchor = BillingCycleAnchor.dayOfMonth(31);
            var cursor = Instant.parse("2026-01-31T00:00:00Z");

            var feb = anchor.periodContaining(cursor, BillingCadence.MONTHLY);
            assertThat(feb.start()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
            assertThat(feb.end()).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));

            var mar = anchor.periodContaining(feb.end(), BillingCadence.MONTHLY);
            assertThat(mar.end()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));

            var apr = anchor.periodContaining(mar.end(), BillingCadence.MONTHLY);
            assertThat(apr.end()).isEqualTo(Instant.parse("2026-04-30T00:00:00Z"));

            var may = anchor.periodContaining(apr.end(), BillingCadence.MONTHLY);
            assertThat(may.end()).isEqualTo(Instant.parse("2026-05-31T00:00:00Z"));
        }

        @Test
        @DisplayName("a leap year renews on Feb 29")
        void leapYearAnchor() {
            var period = BillingCycleAnchor.dayOfMonth(31)
                .periodContaining(Instant.parse("2028-02-15T00:00:00Z"), BillingCadence.MONTHLY);
            assertThat(period.start()).isEqualTo(Instant.parse("2028-01-31T00:00:00Z"));
            assertThat(period.end()).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        }

        @Test
        @DisplayName("a Feb 29 annual anchor clamps to Feb 28 in a common year")
        void annualLeapBirthdayClamps() {
            var period = BillingCycleAnchor.monthOfYear(2, 29)
                .periodContaining(Instant.parse("2027-06-01T00:00:00Z"), BillingCadence.ANNUAL);
            assertThat(period.start()).isEqualTo(Instant.parse("2027-02-28T00:00:00Z"));
            assertThat(period.end()).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        }

        @Test
        @DisplayName("periods are half-open so a boundary belongs to the period starting there")
        void periodsAreHalfOpen() {
            var anchor = BillingCycleAnchor.endOfMonth();
            var period = anchor.periodContaining(Instant.parse("2026-04-15T00:00:00Z"), BillingCadence.MONTHLY);
            // An end-of-month anchor renews on Mar 31 then Apr 30, so mid-April falls in
            // [Mar 31 .. Apr 30).
            var boundary = Instant.parse("2026-04-30T00:00:00Z");
            assertThat(period.start()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));

            assertThat(period.contains(boundary))
                .as("the renewal instant belongs to the period starting there, not the one ending")
                .isFalse();
            assertThat(anchor.nextBoundaryAfter(period.start(), BillingCadence.MONTHLY)).isEqualTo(boundary);
        }

        @Test
        @DisplayName("consecutive periods tile without gap or overlap")
        void consecutivePeriodsTileExactly() {
            var anchor = BillingCycleAnchor.dayOfMonth(31);
            var cursor = Instant.parse("2026-01-31T00:00:00Z");
            for (int i = 0; i < 24; i++) {
                var period = anchor.periodContaining(cursor, BillingCadence.MONTHLY);
                assertThat(period.start()).isEqualTo(cursor);
                cursor = period.end();
            }
            assertThat(cursor).isEqualTo(Instant.parse("2028-01-31T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("Cadence variants")
    class Cadences {

        @Test
        @DisplayName("weekly anchors snap to the configured weekday")
        void weeklyAnchor() {
            var period = BillingCycleAnchor.dayOfWeek(DayOfWeek.WEDNESDAY)
                .periodContaining(Instant.parse("2026-04-15T09:30:00Z"), BillingCadence.WEEKLY);
            assertThat(period.start()).isEqualTo("2026-04-15T00:00:00Z");
            assertThat(period.end()).isEqualTo(Instant.parse("2026-04-22T00:00:00Z"));
        }

        @Test
        @DisplayName("quarterly steps three months and keeps the anchored day")
        void quarterlyAnchor() {
            var period = BillingCycleAnchor.dayOfMonth(31)
                .periodContaining(Instant.parse("2026-02-10T00:00:00Z"), BillingCadence.QUARTERLY);
            assertThat(period.start()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
            assertThat(period.end()).isEqualTo(Instant.parse("2026-04-30T00:00:00Z"));
        }

        @ParameterizedTest(name = "day {0} is accepted")
        @CsvSource({"1", "15", "28", "31"})
        void validDaysOfMonth(int day) {
            assertThat(BillingCycleAnchor.dayOfMonth(day).dayOfMonthValue()).contains(day);
        }

        @Test
        @DisplayName("out-of-range anchor components are rejected")
        void invalidAnchorsRejected() {
            assertThatThrownBy(() -> BillingCycleAnchor.dayOfMonth(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BillingCycleAnchor.dayOfMonth(32)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BillingCycleAnchor.monthOfYear(13, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BillingCycleAnchor(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Flat-fee cadence is enforced by the engine")
    class CadenceEnforcement {

        private DefaultPricingEngine engineWithFlatFee(BillingCadence feeCadence) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            var item = RatePlanItem.of("PRO", "pro",
                PricingModel.FlatFeeModel.of(new Money(new BigDecimal("499.00"), USD), feeCadence), USD);
            var repo = new InMemoryRateCardRepository();
            repo.save(RateCard.of("rc", TenantId.of("t1"), PlanCode.of("P"), 1, now, java.util.List.of(item)));
            return new DefaultPricingEngine(repo, (f, t, ts) -> BigDecimal.ONE,
                (t, c, at) -> java.util.List.of(), r -> { }, null);
        }

        @Test
        @DisplayName("a monthly fee billed on an annual period is rejected")
        void monthlyFeeOnAnnualPeriodRejected() {
            // Before this, a $4,988/year plan and a $499/month plan both charged $499 per
            // evaluation because cadence was accepted and then ignored.
            var engine = engineWithFlatFee(BillingCadence.MONTHLY);

            assertThatThrownBy(() -> engine.evaluate(PricingRequest.builder()
                .tenantId("t1").planCode("P")
                .evaluationTime(Instant.parse("2026-04-15T12:00:00Z"))
                .targetCurrency(USD).item("PRO", 1)
                .billingCycle(BillingCadence.ANNUAL, BillingCycleAnchor.dayOfMonth(15))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONTHLY")
                .hasMessageContaining("ANNUAL");
        }

        @Test
        @DisplayName("a matching cadence is accepted")
        void matchingCadenceAccepted() {
            var engine = engineWithFlatFee(BillingCadence.MONTHLY);

            var result = engine.evaluate(PricingRequest.builder()
                .tenantId("t1").planCode("P")
                .evaluationTime(Instant.parse("2026-04-15T12:00:00Z"))
                .targetCurrency(USD).item("PRO", 1)
                .billingCycle(BillingCadence.MONTHLY, BillingCycleAnchor.dayOfMonth(15))
                .build());

            assertThat(result.finalTotal().amount()).isEqualByComparingTo("499.00");
            assertThat(result.lineItems().getFirst().traceSteps())
                .anyMatch(step -> step.stepName().equals("BILLING_PERIOD_RESOLVED"));
        }

        @Test
        @DisplayName("omitting the cadence preserves previous behaviour")
        void undeclaredCadenceStillAccepted() {
            var engine = engineWithFlatFee(BillingCadence.MONTHLY);

            var result = engine.evaluate(PricingRequest.builder()
                .tenantId("t1").planCode("P")
                .evaluationTime(Instant.parse("2026-04-15T12:00:00Z"))
                .targetCurrency(USD).item("PRO", 1)
                .build());

            assertThat(result.finalTotal().amount()).isEqualByComparingTo("499.00");
        }
    }

    @Nested
    @DisplayName("Proration over an anchored period")
    class Proration {

        @Test
        @DisplayName("an effective span is clamped to the period and prorated by overlap")
        void proratesByOverlap() {
            // Period Mar 31 .. Apr 30 (end-of-month anchor) = 30 days. A change effective Apr 16 .. May 10
            // overlaps the period for Apr 16 .. Apr 30 = 14 of 30 days; the May part falls outside
            // and must not be prorated.
            var period = BillingCycleAnchor.endOfMonth()
                .periodContaining(Instant.parse("2026-04-15T00:00:00Z"), BillingCadence.MONTHLY);

            var factor = period.prorationWindow(
                Instant.parse("2026-04-16T00:00:00Z"),
                Instant.parse("2026-05-10T00:00:00Z")).calculateFactor();

            assertThat(factor).isEqualByComparingTo(new BigDecimal("14").divide(new BigDecimal("30"), 10,
                java.math.RoundingMode.HALF_EVEN));
        }

        @Test
        @DisplayName("a span entirely outside the period is rejected rather than prorated to zero")
        void nonOverlappingSpanRejected() {
            var period = BillingCycleAnchor.endOfMonth()
                .periodContaining(Instant.parse("2026-04-15T00:00:00Z"), BillingCadence.MONTHLY);

            assertThatThrownBy(() -> period.prorationWindow(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-15T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("duration is measured in seconds, not assumed calendar months")
        void durationIsExactSeconds() {
            var period = new BillingPeriod(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"),
                BillingCadence.QUARTERLY);
            assertThat(period.durationSeconds()).isEqualTo(90L * 24 * 3600);
        }
    }
}