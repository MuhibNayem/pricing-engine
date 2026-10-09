package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * One line on an invoice or credit note.
 *
 * <p><strong>Tax is per line, not per invoice.</strong> E-invoicing regimes (Germany B2B, France
 * Factur-X, Italy SdI, India GST, Saudi ZATCA) require a structured breakdown of rate, base and
 * amount for each line. Carrying a single invoice-level tax total makes those documents impossible
 * to produce and makes a tax audit a manual reconstruction.
 *
 * @param itemCode     product or metric code, matching the rated line
 * @param description  human-readable description that appears on the document
 * @param quantity     billed quantity (units, seats, tokens, credits)
 * @param unitPrice    price per unit, in the invoice currency
 * @param amount       quantity x unit price, rounded to the currency scale
 * @param taxCode      code a tax provider can resolve to a rate and jurisdiction; may be empty
 * @param metadata     free-form context for ERP mapping; never interpreted by the engine
 */
public record InvoiceLineItem(
    String itemCode,
    String description,
    BigDecimal quantity,
    Money unitPrice,
    Money amount,
    String taxCode,
    Map<String, String> metadata
) implements Serializable {

    public InvoiceLineItem {
        Objects.requireNonNull(itemCode, "itemCode cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
        Objects.requireNonNull(taxCode, "taxCode cannot be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Builds a line, computing {@code amount = quantity x unitPrice} at the currency scale.
     *
     * <p>Rounding HALF_EVEN at the currency scale matches the rest of the engine; computing the
     * amount here rather than trusting the caller is what keeps an invoice total equal to the sum of
     * its lines.
     */
    public static InvoiceLineItem of(String itemCode, String description, BigDecimal quantity,
                                     Money unitPrice, String taxCode) {
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
        CurrencyUnit currency = unitPrice.currency();
        Money amount = unitPrice.times(quantity).roundTo(currency.defaultFractionDigits(), RoundingMode.HALF_EVEN);
        return new InvoiceLineItem(itemCode, description, quantity, unitPrice, amount,
            taxCode == null ? "" : taxCode, Map.of());
    }

    public InvoiceLineItem withMetadata(Map<String, String> extra) {
        return new InvoiceLineItem(itemCode, description, quantity, unitPrice, amount, taxCode, extra);
    }

    /** Negative of this line, for a credit note. Carries the tax code so the reversal is traceable. */
    public InvoiceLineItem negated(String negatedDescription) {
        return new InvoiceLineItem(itemCode, negatedDescription, quantity.negate(),
            unitPrice, amount.negate(), taxCode, metadata);
    }
}