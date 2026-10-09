package com.saas.pricing.core.model.fx;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A currency conversion with its provenance.
 *
 * <p><strong>Why provenance matters.</strong> Revenue recognition cannot book an amount at an
 * anonymous rate. A figure booked before an invoice is paid is an <em>estimate</em> made at
 * finalization; the figure booked when the money actually moves is <em>realised</em>. Conflating
 * them means the recognised-revenue figure changes after the period has closed, which breaks the
 * whole point of the exercise.
 *
 * <p>So a rate records where it came from ({@link RateSource}) and when it was observed, and the
 * two are never silently interchanged.
 *
 * @param from      currency being converted from
 * @param to        currency being converted into
 * @param rate      multiply an amount in {@code from} by this to get {@code to}
 * @param observedAt when the rate was observed; never "now" by default
 * @param source    how the rate was obtained
 * @param providerId the provider or rate-series identifier, so an auditor can find the source
 */
public record FxRate(
    CurrencyUnit from,
    CurrencyUnit to,
    BigDecimal rate,
    Instant observedAt,
    RateSource source,
    String providerId
) implements Serializable {

    /** How a rate was obtained. Determines whether it can be used for revenue recognition. */
    public enum RateSource {
        /** Live spot at the moment of quoting. Usable for a quote, not for a booked figure. */
        SPOT,
        /** The rate agreed for a contract, e.g. a locked or hedged rate. */
        CONTRACTUAL,
        /** A rate observed when money actually moved. This is the realised rate. */
        SETTLEMENT,
        /** A rate loaded from a daily reference feed. */
        REFERENCE
    }

    public FxRate {
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        Objects.requireNonNull(rate, "rate cannot be null");
        Objects.requireNonNull(observedAt, "observedAt cannot be null");
        Objects.requireNonNull(source, "source cannot be null");
        if (from.equals(to)) {
            throw new IllegalArgumentException("An FX rate must be between two different currencies");
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("An FX rate must be positive, got " + rate);
        }
        if (providerId == null) {
            providerId = "";
        }
    }

    /**
     * Converts {@code amount} using this rate.
     *
     * <p>Computed in {@link MathContext#DECIMAL128} and then rounded HALF_EVEN to the target
     * currency's scale. Rounding at the boundary rather than carrying a 34-digit intermediate is
     * what makes a chain of conversions land on a value a ledger can actually store.
     */
    public Money convert(Money amount) {
        Objects.requireNonNull(amount, "amount cannot be null");
        if (!amount.currency().equals(from)) {
            throw new IllegalArgumentException(
                    "Cannot convert " + amount.currency().code() + " using a " + from.code() + "->"
                        + to.code() + " rate");
        }
        BigDecimal converted = amount.amount().multiply(rate, MathContext.DECIMAL128);
        return Money.of(converted.setScale(to.defaultFractionDigits(), RoundingMode.HALF_EVEN), to);
    }

    /** The inverse rate, useful when a settlement arrives in the presentation currency. */
    public FxRate inverted() {
        return new FxRate(to, from,
            BigDecimal.ONE.divide(rate, MathContext.DECIMAL128), observedAt, source, providerId);
    }

    public Optional<String> rateId() {
        return providerId.isBlank() ? Optional.empty() : Optional.of(providerId);
    }
}