package com.saas.pricing.core.model.fx;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exchange-rate SPI must be able to report provenance.
 *
 * <p>A bare {@link BigDecimal} cannot distinguish a spot quote from a contractual rate, so a rating
 * booked from it cannot later be trued up correctly. The default wrapper is deliberately honest:
 * it marks the rate {@link FxRate.RateSource#SPOT} rather than guessing something more flattering.
 */
class CurrencyExchangeProvenanceTest {

    private static final CurrencyUnit EUR = CurrencyUnit.EUR;
    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("an existing provider gains provenance without changing its code")
    void defaultWrapperCarriesProvenance() {
        // A provider written against the original single-method interface still compiles.
        CurrencyExchangeProvider provider = (from, to, at) -> new BigDecimal("1.20");

        FxRate rate = provider.getRate(EUR, USD, AT);

        assertThat(rate.rate()).isEqualByComparingTo("1.20");
        assertThat(rate.source())
            .as("a bare rate tells us nothing, so it is a spot quote - not a contractual rate")
            .isEqualTo(FxRate.RateSource.SPOT);
        assertThat(rate.observedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("a provider can report a contractual rate with its source")
    void providerCanOverrideProvenance() {
        CurrencyExchangeProvider provider = new CurrencyExchangeProvider() {
            @Override
            public BigDecimal getExchangeRate(CurrencyUnit from, CurrencyUnit to, Instant at) {
                return new BigDecimal("1.15");
            }

            @Override
            public FxRate getRate(CurrencyUnit from, CurrencyUnit to, Instant at) {
                return new FxRate(from, to, new BigDecimal("1.15"), at,
                    FxRate.RateSource.CONTRACTUAL, "CONTRACT-2026-ACME");
            }
        };

        FxRate rate = provider.getRate(EUR, USD, AT);

        assertThat(rate.source()).isEqualTo(FxRate.RateSource.CONTRACTUAL);
        assertThat(rate.rateId()).contains("CONTRACT-2026-ACME");
        assertThat(rate.convert(Money.of("30.00", EUR))).isEqualTo(Money.of("34.50", USD));
    }

    @Test
    @DisplayName("the identity provider still refuses a real conversion")
    void identityProviderUnchanged() {
        CurrencyExchangeProvider identity = new IdentityProvider();

        assertThatThrownBy(() -> identity.getRate(EUR, USD, AT))
            .isInstanceOf(UnsupportedOperationException.class);

        // Same currency is refused too: a "rate" of 1.00 from USD to USD would let a same-currency
        // invoice slip past the FX booking path that exists to catch exactly that mistake.
        assertThatThrownBy(() -> identity.getRate(USD, USD, AT))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(identity.getExchangeRate(USD, USD, AT)).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("a spot rate can be booked as an estimate and later trued up")
    void spotRateFeedsTheBooking() {
        var booking = FxBooking.estimate("fb", "inv", Money.of("30.00", EUR), USD,
            new FxRate(EUR, USD, new BigDecimal("1.20"), AT, FxRate.RateSource.SPOT, ""), AT);

        assertThat(booking.estimatedAmount()).isEqualTo(Money.of("36.00", USD));
        assertThat(booking.isSettled()).isFalse();
    }

    /** Local copy of the interface's identity behaviour, so it can be instantiated in a test. */
    private static final class IdentityProvider implements CurrencyExchangeProvider {
        @Override
        public BigDecimal getExchangeRate(CurrencyUnit from, CurrencyUnit to, Instant at) {
            if (from.equals(to)) {
                return BigDecimal.ONE;
            }
            throw new UnsupportedOperationException(
                "Exchange rate conversion not supported between %s and %s".formatted(from.code(), to.code()));
        }

        @Override
        public com.saas.pricing.core.model.fx.FxRate getRate(CurrencyUnit from, CurrencyUnit to, Instant at) {
            throw new UnsupportedOperationException(
                "No exchange rate is defined for %s -> %s".formatted(from.code(), to.code()));
        }
    }
}
