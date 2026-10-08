package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CurrencyUnit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SPI for fetching exchange rates between currencies.
 */
public interface CurrencyExchangeProvider {

    /**
     * Gets exchange rate from source currency to target currency at given timestamp.
     * Default identity implementation returns 1.0 if currencies match.
     */
    BigDecimal getExchangeRate(CurrencyUnit from, CurrencyUnit to, Instant timestamp);

    default CurrencyExchangeProvider identity() {
        return (from, to, timestamp) -> {
            if (from.equals(to)) {
                return BigDecimal.ONE;
            }
            throw new UnsupportedOperationException(
                "Exchange rate conversion not supported between %s and %s".formatted(from.code(), to.code())
            );
        };
    }
}
