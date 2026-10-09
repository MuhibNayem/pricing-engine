package com.saas.pricing.core.model;

import java.io.Serializable;
import java.util.Currency;
import java.util.Objects;

/**
 * Represents a currency unit, supporting standard ISO-4217 currencies
 * as well as custom tokens / credit units for SaaS consumption models.
 */
public record CurrencyUnit(String code, int defaultFractionDigits) implements Serializable {

    public static final CurrencyUnit USD = new CurrencyUnit("USD", 2);
    public static final CurrencyUnit EUR = new CurrencyUnit("EUR", 2);
    public static final CurrencyUnit GBP = new CurrencyUnit("GBP", 2);
    public static final CurrencyUnit JPY = new CurrencyUnit("JPY", 0);
    public static final CurrencyUnit CAD = new CurrencyUnit("CAD", 2);
    public static final CurrencyUnit AUD = new CurrencyUnit("AUD", 2);
    public static final CurrencyUnit CREDITS = new CurrencyUnit("CREDITS", 4);
    public static final CurrencyUnit TOKENS = new CurrencyUnit("TOKENS", 6);

    public CurrencyUnit {
        Objects.requireNonNull(code, "Currency code cannot be null");
        // Normalised in the canonical constructor so every construction path produces the same
        // value: `new CurrencyUnit("usd", 2)` and `CurrencyUnit.USD` must be equal, or currencies
        // silently fail Money's currency-isolation check.
        code = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (code.isBlank()) {
            throw new IllegalArgumentException("Currency code cannot be blank");
        }
        if (defaultFractionDigits < 0) {
            throw new IllegalArgumentException("Default fraction digits cannot be negative");
        }
    }

    public static CurrencyUnit of(String code) {
        Objects.requireNonNull(code, "Currency code cannot be null");
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        // Known non-ISO tokens keep their declared precision, so `of("CREDITS")` returns the same
        // value as the CREDITS constant instead of a 2-digit lookalike that is unequal to it.
        if (CREDITS.code().equals(normalized)) {
            return CREDITS;
        }
        if (TOKENS.code().equals(normalized)) {
            return TOKENS;
        }
        try {
            Currency javaCurrency = Currency.getInstance(normalized);
            return new CurrencyUnit(javaCurrency.getCurrencyCode(), javaCurrency.getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            // Non-ISO currency (e.g. a tenant-defined credit unit) - default to 2 decimal places
            return new CurrencyUnit(normalized, 2);
        }
    }

    public static CurrencyUnit of(String code, int fractionDigits) {
        return new CurrencyUnit(code, fractionDigits);
    }
}
