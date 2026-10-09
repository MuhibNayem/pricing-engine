package com.saas.pricing.core.model.fx;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Estimated-versus-realised foreign exchange booking.
 *
 * <p>The worked example throughout is Stripe's published one: a 30 EUR invoice booked at 1.20 and
 * settled at 1.10 produces a 3.00 FX loss. Getting the sign of that delta wrong is the whole bug
 * this guards against.
 */
class FxBookingTest {

    private static final CurrencyUnit EUR = CurrencyUnit.EUR;
    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-15T00:00:00Z");

    private static FxRate rate(String value, FxRate.RateSource source) {
        return new FxRate(EUR, USD, new BigDecimal(value), T0, source, "ECB-REF");
    }

    @Nested
    @DisplayName("Rate")
    class RateBehaviour {

        @Test
        @DisplayName("converts and rounds at the target currency scale")
        void convertsAndRounds() {
            var converted = rate("1.20", FxRate.RateSource.SPOT)
                .convert(Money.of("30.00", EUR));

            assertThat(converted).isEqualTo(Money.of("36.00", USD));
        }

        @Test
        @DisplayName("refuses a zero or negative rate")
        void rateMustBePositive() {
            assertThatThrownBy(() -> rate("0", FxRate.RateSource.SPOT))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> rate("-1.1", FxRate.RateSource.SPOT))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a same-currency rate, which is always a modelling mistake")
        void sameCurrencyRejected() {
            assertThatThrownBy(() -> new FxRate(USD, USD, BigDecimal.ONE, T0,
                FxRate.RateSource.SPOT, ""))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses to convert an amount it does not apply to")
        void wrongCurrencyRejected() {
            assertThatThrownBy(() -> rate("1.20", FxRate.RateSource.SPOT)
                .convert(Money.of("10.00", CurrencyUnit.GBP)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot convert");
        }
    }

    @Nested
    @DisplayName("Booking")
    class BookingBehaviour {

        @Test
        @DisplayName("an unsettled booking has no delta")
        void unsettledHasNoDelta() {
            var booking = FxBooking.estimate("fb-1", "inv-1", Money.of("30.00", EUR), USD,
                rate("1.20", FxRate.RateSource.REFERENCE), T0);

            assertThat(booking.estimatedAmount()).isEqualTo(Money.of("36.00", USD));
            assertThat(booking.isSettled()).isFalse();
            assertThat(booking.delta()).isEqualTo(Money.zero(USD));
        }

        @Test
        @DisplayName("the worked example: 30 EUR at 1.20 settled at 1.10 loses $3.00")
        void settlementLossesProduceFxLoss() {
            var booked = FxBooking.estimate("fb-2", "inv-2", Money.of("30.00", EUR), USD,
                rate("1.20", FxRate.RateSource.REFERENCE), T0);

            var settled = booked.settle(rate("1.10", FxRate.RateSource.SETTLEMENT), T1);

            assertThat(settled.realizedAmount()).contains(Money.of("33.00", USD));
            assertThat(settled.delta().amount())
                .as("realised 33.00 minus estimated 36.00")
                .isEqualByComparingTo("-3.00");
            assertThat(settled.fxLoss().amount())
                .as("a loss is posted as a positive expense")
                .isEqualByComparingTo("3.00");
            assertThat(settled.isAccurate()).isFalse();
        }

        @Test
        @DisplayName("a favourable movement produces a gain, not a negative loss")
        void settlementGainsProduceGain() {
            var booked = FxBooking.estimate("fb-3", "inv-3", Money.of("30.00", EUR), USD,
                rate("1.10", FxRate.RateSource.REFERENCE), T0);

            var settled = booked.settle(rate("1.20", FxRate.RateSource.SETTLEMENT), T1);

            assertThat(settled.delta().amount()).isEqualByComparingTo("3.00");
            assertThat(settled.fxLoss().amount())
                .as("a gain posts as a negative loss")
                .isEqualByComparingTo("-3.00");
        }

        @Test
        @DisplayName("an unchanged rate produces no posting at all")
        void unchangedRateIsAccurate() {
            var booked = FxBooking.estimate("fb-4", "inv-4", Money.of("30.00", EUR), USD,
                rate("1.20", FxRate.RateSource.REFERENCE), T0);

            var settled = booked.settle(rate("1.20", FxRate.RateSource.SETTLEMENT), T1);

            assertThat(settled.isAccurate()).isTrue();
            assertThat(settled.fxLoss().isZero()).isTrue();
        }

        @Test
        @DisplayName("settling twice is refused")
        void cannotSettleTwice() {
            var booked = FxBooking.estimate("fb-5", "inv-5", Money.of("30.00", EUR), USD,
                rate("1.20", FxRate.RateSource.REFERENCE), T0);
            var settled = booked.settle(rate("1.10", FxRate.RateSource.SETTLEMENT), T1);

            assertThatThrownBy(() -> settled.settle(rate("1.15", FxRate.RateSource.SETTLEMENT), T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already settled");
        }

        @Test
        @DisplayName("a settlement in the wrong currency pair is refused")
        void mismatchedPairRejected() {
            var booked = FxBooking.estimate("fb-6", "inv-6", Money.of("30.00", EUR), USD,
                rate("1.20", FxRate.RateSource.REFERENCE), T0);

            var gbpRate = new FxRate(CurrencyUnit.GBP, USD, new BigDecimal("1.27"), T1,
                FxRate.RateSource.SETTLEMENT, "");

            assertThatThrownBy(() -> booked.settle(gbpRate, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GBP");
        }

        @Test
        @DisplayName("the original estimate survives settlement unchanged")
        void estimateIsImmutable() {
            var booked = FxBooking.estimate("fb-7", "inv-7", Money.of("30.00", EUR), USD,
                rate("1.20", FxRate.RateSource.REFERENCE), T0);

            booked.settle(rate("1.10", FxRate.RateSource.SETTLEMENT), T1);

            assertThat(booked.estimatedAmount())
                .as("a closed period's booked figure must not change underneath the auditor")
                .isEqualTo(Money.of("36.00", USD));
            assertThat(booked.isSettled()).isFalse();
        }

        @Test
        @DisplayName("an FX booking for an invoice already in the reporting currency is refused")
        void noBookingNeededWhenCurrenciesMatch() {
            assertThatThrownBy(() -> FxBooking.estimate("fb-8", "inv-8", Money.of("30.00", USD), USD,
                rate("1.20", FxRate.RateSource.REFERENCE), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no FX booking is required");
        }
    }
}