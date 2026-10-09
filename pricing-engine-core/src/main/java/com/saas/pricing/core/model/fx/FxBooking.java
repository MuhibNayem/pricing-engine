package com.saas.pricing.core.model.fx;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An invoice amount booked at an estimated rate, later trued up to the realised rate.
 *
 * <h2>The accounting problem this solves</h2>
 * An invoice denominated in EUR but settled in USD must be recognised before the money arrives. At
 * finalization only an <em>estimated</em> rate exists, so revenue and receivable are booked at that
 * rate. When the payment settles at a different rate, the difference is a gain or a loss that must
 * be posted separately - not silently folded back into the original revenue figure, which would
 * change a closed period.
 *
 * <pre>
 *   estimated  30 EUR x 1.20 = $36.00 booked (receivable + revenue)
 *   realised   30 EUR x 1.10 = $33.00 received
 *   ---------------------------------------------------------------
 *   delta                =  -$3.00  -> FxLoss
 * </pre>
 *
 * <p>The sign convention is: a <em>positive</em> delta means the realised amount exceeded the
 * estimate (a gain), a negative delta a loss. {@link #fxLoss()} returns the negated delta so it can
 * be posted as an expense.
 *
 * <p>Immutable: {@link #settle(FxRate, Instant)} returns a new booking rather than mutating this
 * one, so the original estimate remains visible for audit.
 *
 * @param bookingId        unique identifier for this booking
 * @param invoiceId        the invoice this relates to
 * @param invoiceAmount    amount on the invoice, in the invoice currency
 * @param functionalCurrency the reporting currency amounts are translated into
 * @param estimatedRate    the rate used when the invoice was finalized
 * @param estimatedAmount  invoiceAmount converted at the estimated rate
 * @param realizedRate     the rate observed when the money actually moved; absent until settlement
 * @param realizedAmount   invoiceAmount converted at the realised rate
 * @param bookedAt         when the estimated figure was booked
 * @param settledAt        when it was trued up; absent until settlement
 */
public record FxBooking(
    String bookingId,
    String invoiceId,
    Money invoiceAmount,
    CurrencyUnit functionalCurrency,
    FxRate estimatedRate,
    Money estimatedAmount,
    Optional<FxRate> realizedRate,
    Optional<Money> realizedAmount,
    Instant bookedAt,
    Optional<Instant> settledAt
) implements Serializable {

    public FxBooking {
        Objects.requireNonNull(bookingId, "bookingId cannot be null");
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        Objects.requireNonNull(invoiceAmount, "invoiceAmount cannot be null");
        Objects.requireNonNull(functionalCurrency, "functionalCurrency cannot be null");
        Objects.requireNonNull(estimatedRate, "estimatedRate cannot be null");
        Objects.requireNonNull(estimatedAmount, "estimatedAmount cannot be null");
        Objects.requireNonNull(realizedRate, "realizedRate cannot be null");
        Objects.requireNonNull(realizedAmount, "realizedAmount cannot be null");
        Objects.requireNonNull(bookedAt, "bookedAt cannot be null");
        Objects.requireNonNull(settledAt, "settledAt cannot be null");

        if (!estimatedAmount.currency().equals(functionalCurrency)) {
            throw new IllegalArgumentException(
                    "estimatedAmount must be in the functional currency " + functionalCurrency.code()
                        + ", got " + estimatedAmount.currency().code());
        }
        // The two halves of the settlement must agree, or the delta would be meaningless.
        if (realizedRate.isPresent() != realizedAmount.isPresent()) {
            throw new IllegalArgumentException(
                    "A booking is either fully settled or fully estimated, not half of each");
        }
    }

    /**
     * Books {@code invoiceAmount} at the estimated rate available at finalization.
     *
     * @param invoiceCurrency the invoice's own currency, which may differ from the reporting one
     */
    public static FxBooking estimate(String bookingId, String invoiceId, Money invoiceAmount,
                                     CurrencyUnit functionalCurrency, FxRate estimatedRate, Instant bookedAt) {
        Objects.requireNonNull(estimatedRate, "estimatedRate cannot be null");
        // Checked before converting, so the modelling mistake gets its own message rather than a
        // confusing "cannot convert USD using a EUR->USD rate".
        if (invoiceAmount.currency().equals(functionalCurrency)) {
            throw new IllegalArgumentException(
                    "Invoice currency and functional currency are both "
                        + functionalCurrency.code() + "; no FX booking is required");
        }
        Money translated = estimatedRate.convert(invoiceAmount);
        return new FxBooking(bookingId, invoiceId, invoiceAmount, functionalCurrency, estimatedRate,
            translated, Optional.empty(), Optional.empty(), bookedAt, Optional.empty());
    }

    /**
     * Trues the booking up to the realised rate.
     *
     * @param rate must be for the same currency pair as the estimate
     * @return a new settled booking; this one is unchanged
     */
    public FxBooking settle(FxRate rate, Instant at) {
        Objects.requireNonNull(rate, "rate cannot be null");
        Objects.requireNonNull(at, "at cannot be null");
        if (settledAt.isPresent()) {
            throw new IllegalStateException(
                    "Booking " + bookingId + " was already settled at " + settledAt.get());
        }
        if (!rate.from().equals(estimatedRate.from()) || !rate.to().equals(estimatedRate.to())) {
            throw new IllegalArgumentException(
                    "Settlement rate is " + rate.from().code() + "->" + rate.to().code()
                        + " but the booking was estimated at " + estimatedRate.from().code() + "->"
                        + estimatedRate.to().code());
        }
        return new FxBooking(bookingId, invoiceId, invoiceAmount, functionalCurrency, estimatedRate,
            estimatedAmount, Optional.of(rate), Optional.of(rate.convert(invoiceAmount)),
            bookedAt, Optional.of(at));
    }

    public boolean isSettled() {
        return settledAt.isPresent();
    }

    /**
     * Realised minus estimated. Positive means the estimate was conservative (a gain).
     *
     * <p>Zero while unsettled, because an un-settled booking has no realised figure to compare.
     */
    public Money delta() {
        if (realizedAmount.isEmpty()) {
            return Money.zero(functionalCurrency);
        }
        return realizedAmount.get().minus(estimatedAmount).roundToCurrency();
    }

    /**
     * The posting to an FX loss account: the negated delta.
     *
     * <p>A negative delta (money came in worth less than booked) becomes a positive expense, which
     * is how a loss is expressed on a financial statement.
     */
    public Money fxLoss() {
        return delta().negate().roundToCurrency();
    }

    /** True when the estimate was right to the currency's minor unit. */
    public boolean isAccurate() {
        return delta().isZero();
    }
}