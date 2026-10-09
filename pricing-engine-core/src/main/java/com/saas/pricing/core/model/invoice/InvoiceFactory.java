package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an {@link Invoice} from a {@link PricingResult}.
 *
 * <p>Without this, the invoice aggregate would be a model nothing produces, which is how the flat-fee
 * cadence field ended up accepted and ignored in the first place. Every rated request can now be
 * turned into a numbered, finalizable document without re-deriving anything.
 *
 * <p><strong>Tax is not re-derived here.</strong> The rating engine already apportioned tax per line
 * and that apportionment is what an invoice must carry, because the sum of the line taxes is the
 * invoice tax. Recomputing it would produce a document that disagrees with the amount the customer
 * was quoted.
 */
public final class InvoiceFactory {

    private InvoiceFactory() {
        // static utility
    }

    /**
     * Creates a draft invoice from a rating result.
     *
     * @param invoiceId     unique invoice identifier
     * @param customerId    customer being billed; required - an invoice without a customer is not a document
     * @param periodStart   inclusive period start
     * @param periodEnd     exclusive period end
     * @param createdAt     draft creation time
     * @param taxCode       tax code applied to every line, or empty when none applies
     */
    public static Invoice draftFrom(PricingResult result, String invoiceId, CustomerId customerId,
                                    Instant periodStart, Instant periodEnd, Instant createdAt,
                                    String taxCode) {
        Objects.requireNonNull(result, "result cannot be null");
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");

        TenantId tenantId = result.tenantId();
        PlanCode planCode = result.planCode();
        CurrencyUnit currency = result.currency();

        Invoice.Builder builder = Invoice.draft(invoiceId, tenantId, customerId, planCode, currency,
            periodStart, periodEnd, createdAt);

        for (var line : result.lineItems()) {
            // The line amount is the NET the customer owes, not the gross before discounts. Billing
            // the gross silently dropped every discount from the generated document: the invoice
            // said the customer owed more than the rating result they were quoted.
            builder.addLine(new InvoiceLineItem(
                line.itemCode(),
                describe(line.itemCode()),
                line.billableQuantity(),
                unitPriceFor(line, currency),
                line.netAmount().roundToCurrency(),
                taxCode == null ? "" : taxCode,
                Map.of("calculationId", result.calculationId())));
        }

        // Tax comes from the rating result verbatim. It was already apportioned across lines, and
        // an invoice that re-derived it would not match the quote the customer accepted.
        builder.taxTotal(result.totalTax().roundToCurrency());
        builder.metadata(Map.of("calculationId", result.calculationId()));

        return builder.build();
    }

    /**
     * Derives a unit price from a rated line's net amount so that {@code quantity x unitPrice} is
     * as close to the net as the currency allows.
     *
     * <p>The line amount itself is carried verbatim (not re-derived from the unit price) because the
     * net is authoritative - it is what the customer was quoted. A non-exact division rounded back
     * up must not change what is owed. For a zero quantity the line is carried at its amount with a
     * unit price of zero rather than being dropped: a zero-quantity line can still carry a fixed fee.
     */
    private static Money unitPriceFor(com.saas.pricing.core.model.RatedLineItem line, CurrencyUnit currency) {
        BigDecimal quantity = line.billableQuantity();
        if (quantity == null || quantity.signum() == 0) {
            return Money.zero(currency);
        }
        BigDecimal unitPrice = line.netAmount().amount()
            .divide(quantity, currency.defaultFractionDigits() + 4, java.math.RoundingMode.HALF_EVEN)
            .stripTrailingZeros();
        return Money.of(unitPrice, currency);
    }

    private static String describe(String itemCode) {
        return switch (itemCode.toUpperCase(Locale.ROOT)) {
            case "SEATS" -> "Subscription seats";
            case "BASE" -> "Platform subscription";
            case "API_CALLS" -> "API calls";
            default -> itemCode;
        };
    }
}