package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.fx.FxRate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SPI for fetching exchange rates between currencies.
 *
 * <p><strong>Prefer {@link #getRate(CurrencyUnit, CurrencyUnit, Instant)}.</strong> It returns an
 * {@link FxRate}, which carries where the rate came from and when it was observed. A bare
 * {@link BigDecimal} cannot distinguish a spot quote from a contractual or settlement rate, and
 * that distinction is exactly what revenue recognition needs: a figure booked before an invoice is
 * paid must be marked as an estimate so it can be trued up later.
 *
 * <p>{@link #getExchangeRate} remains the abstract method so existing implementations keep
 * compiling unchanged.
 */
public interface CurrencyExchangeProvider {

    /**
     * Gets exchange rate from source currency to target currency at given timestamp.
     * Default identity implementation returns 1.0 if currencies match.
     */
    BigDecimal getExchangeRate(CurrencyUnit from, CurrencyUnit to, Instant timestamp);

    /**
     * Returns the rate with its provenance.
     *
     * <p>The default implementation wraps {@link #getExchangeRate} and marks it
     * {@link FxRate.RateSource#SPOT} with no provider id, which is the honest reading of a
     * provider that has told us nothing else. Implementations backed by a real rate feed should
     * override this to record the provider and the correct source.
     */
    default FxRate getRate(CurrencyUnit from, CurrencyUnit to, Instant timestamp) {
        return new FxRate(from, to, getExchangeRate(from, to, timestamp), timestamp,
            FxRate.RateSource.SPOT, "");
    }

    default CurrencyExchangeProvider identity() {
        return new CurrencyExchangeProvider() {
            @Override
            public BigDecimal getExchangeRate(CurrencyUnit from, CurrencyUnit to, Instant timestamp) {
                if (from.equals(to)) {
                    return BigDecimal.ONE;
                }
                throw new UnsupportedOperationException(
                    "Exchange rate conversion not supported between %s and %s".formatted(from.code(), to.code())
                );
            }

            @Override
            public FxRate getRate(CurrencyUnit from, CurrencyUnit to, Instant timestamp) {
                // An FxRate between identical currencies is not a thing - FxRate rejects it
                // deliberately, because a "rate" of 1.00 from USD to USD would let a same-currency
                // invoice slip through the FX booking path that exists to catch exactly that.
                throw new UnsupportedOperationException(
                    "No exchange rate is defined for %s -> %s; a booking is only meaningful between "
                        .formatted(from.code(), to.code()) + "different currencies");
            }
        };
    }
}
