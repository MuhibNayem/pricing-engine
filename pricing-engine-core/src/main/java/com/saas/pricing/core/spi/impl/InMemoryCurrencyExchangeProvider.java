package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise in-memory FX currency exchange provider.
 * Supports direct rate pairs, inverse rate calculation, and triangulation via bridge currencies (e.g. USD).
 */
public class InMemoryCurrencyExchangeProvider implements CurrencyExchangeProvider {

    private static final CurrencyUnit BRIDGE_CURRENCY = CurrencyUnit.USD;
    private final Map<String, BigDecimal> directRates = new ConcurrentHashMap<>();

    private String pairKey(CurrencyUnit from, CurrencyUnit to) {
        return from.code().toUpperCase() + "->" + to.code().toUpperCase();
    }

    public InMemoryCurrencyExchangeProvider setRate(CurrencyUnit from, CurrencyUnit to, BigDecimal rate) {
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        Objects.requireNonNull(rate, "rate cannot be null");
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Exchange rate must be positive: " + rate);
        }
        directRates.put(pairKey(from, to), rate);
        return this;
    }

    public InMemoryCurrencyExchangeProvider setRate(String fromCode, String toCode, double rate) {
        return setRate(CurrencyUnit.of(fromCode), CurrencyUnit.of(toCode), BigDecimal.valueOf(rate));
    }

    @Override
    public BigDecimal getExchangeRate(CurrencyUnit from, CurrencyUnit to, Instant timestamp) {
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        // 1. Direct rate
        BigDecimal direct = directRates.get(pairKey(from, to));
        if (direct != null) {
            return direct;
        }

        // 2. Inverse rate (1 / rate)
        BigDecimal inverse = directRates.get(pairKey(to, from));
        if (inverse != null) {
            return BigDecimal.ONE.divide(inverse, 12, RoundingMode.HALF_EVEN);
        }

        // 3. Triangulation via USD bridge currency
        if (!from.equals(BRIDGE_CURRENCY) && !to.equals(BRIDGE_CURRENCY)) {
            BigDecimal fromToBridge = findDirectOrInverse(from, BRIDGE_CURRENCY);
            BigDecimal bridgeToTarget = findDirectOrInverse(BRIDGE_CURRENCY, to);
            if (fromToBridge != null && bridgeToTarget != null) {
                return fromToBridge.multiply(bridgeToTarget).setScale(12, RoundingMode.HALF_EVEN);
            }
        }

        throw new UnsupportedOperationException(
            "No exchange rate found between %s and %s".formatted(from.code(), to.code())
        );
    }

    private BigDecimal findDirectOrInverse(CurrencyUnit from, CurrencyUnit to) {
        if (from.equals(to)) return BigDecimal.ONE;
        BigDecimal direct = directRates.get(pairKey(from, to));
        if (direct != null) return direct;
        BigDecimal inverse = directRates.get(pairKey(to, from));
        if (inverse != null) return BigDecimal.ONE.divide(inverse, 12, RoundingMode.HALF_EVEN);
        return null;
    }
}
