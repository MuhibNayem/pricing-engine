package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CustomerId;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which numbering scheme a tenant uses, and how each series is rendered.
 *
 * <p>This is a merchant's legal and commercial decision, so it is configuration rather than a
 * constant. Two rules earn their keep:
 *
 * <ul>
 *   <li><strong>Fail closed on a missing customer prefix.</strong> Under
 *       {@link InvoiceNumberScheme#CUSTOMER_SEQUENTIAL} a customer with no prefix is refused. Giving
 *       it the account prefix instead would merge two customers into one sequence — and since the
 *       numbers would then be unique, nothing would flag it. A duplicated number is obvious; two
 *       customers quietly sharing a series is not.</li>
 *   <li><strong>Settings apply forward only.</strong> A format change never renumbers an issued
 *       document, because the sequence itself is per (tenant, key) and independent of the rendering.
 *       Reformatting mid-year is therefore safe, and restarting at 1 is not: that is what
 *       {@link #startAt()} is for.</li>
 * </ul>
 *
 * @param scheme          account-wide or per-customer sequencing
 * @param accountFormat   rendering used by {@link InvoiceNumberScheme#ACCOUNT_SEQUENTIAL}, and the
 *                        fallback tenant prefix for customer-level schemes
 * @param customerFormats per-customer renderings, required for every customer billed under
 *                        {@link InvoiceNumberScheme#CUSTOMER_SEQUENTIAL}
 * @param startAt         the first number a series may use; raise this to resume a numbering
 *                        sequence that another system was using, never lower it below a number
 *                        already issued
 */
public record InvoiceNumberPolicy(
    InvoiceNumberScheme scheme,
    InvoiceNumberFormat accountFormat,
    Map<CustomerId, InvoiceNumberFormat> customerFormats,
    long startAt
) implements Serializable {

    public InvoiceNumberPolicy {
        Objects.requireNonNull(scheme, "scheme cannot be null");
        Objects.requireNonNull(accountFormat, "accountFormat cannot be null");
        customerFormats = customerFormats == null ? Map.of() : Map.copyOf(customerFormats);
        if (startAt < 1) {
            throw new IllegalArgumentException("startAt must be 1 or later, got " + startAt);
        }
        assertPrefixesAreDistinct(customerFormats);
    }

    private static void assertPrefixesAreDistinct(Map<CustomerId, InvoiceNumberFormat> formats) {
        // Prefixes must be distinct across customers, otherwise two series render identically and
        // a customer can be handed a document number that belongs to another customer's sequence.
        var seen = new LinkedHashMap<String, CustomerId>();
        for (var entry : formats.entrySet()) {
            var previous = seen.putIfAbsent(entry.getValue().prefix(), entry.getKey());
            if (previous != null) {
                throw new IllegalArgumentException(
                    "Invoice number prefix '" + entry.getValue().prefix() + "' is shared by customers "
                        + previous.value() + " and " + entry.getKey().value());
            }
        }
    }

    /** Account-wide sequencing, e.g. {@code INV-0001}. */
    public static InvoiceNumberPolicy accountSequential(String prefix) {
        return new InvoiceNumberPolicy(InvoiceNumberScheme.ACCOUNT_SEQUENTIAL,
            InvoiceNumberFormat.of(prefix), Map.of(), 1L);
    }

    /** Per-customer sequencing. Every customer billed must have a prefix. */
    public static InvoiceNumberPolicy customerSequential(Map<CustomerId, String> prefixes) {
        Objects.requireNonNull(prefixes, "prefixes cannot be null");
        var formats = new LinkedHashMap<CustomerId, InvoiceNumberFormat>();
        prefixes.forEach((customer, prefix) -> formats.put(customer, InvoiceNumberFormat.of(prefix)));
        return new InvoiceNumberPolicy(InvoiceNumberScheme.CUSTOMER_SEQUENTIAL,
            InvoiceNumberFormat.of("INV"), formats, 1L);
    }

    /** Returns a policy resuming an existing series at {@code next}. */
    public InvoiceNumberPolicy resumingAt(long next) {
        return new InvoiceNumberPolicy(scheme, accountFormat, customerFormats, next);
    }

    /**
     * The series this customer's invoices draw from.
     *
     * @throws IllegalStateException under customer-level sequencing with no prefix for this customer
     */
    public String sequenceKeyFor(CustomerId customerId) {
        Objects.requireNonNull(customerId, "customerId cannot be null");
        if (scheme == InvoiceNumberScheme.ACCOUNT_SEQUENTIAL) {
            return "ACCOUNT";
        }
        formatFor(customerId);
        return "CUSTOMER:" + customerId.value();
    }

    /**
     * The rendering for this customer's documents.
     *
     * @throws IllegalStateException if a customer-level policy has no prefix for this customer
     */
    public InvoiceNumberFormat formatFor(CustomerId customerId) {
        if (scheme == InvoiceNumberScheme.ACCOUNT_SEQUENTIAL) {
            return accountFormat;
        }
        InvoiceNumberFormat format = customerFormats.get(customerId);
        if (format == null) {
            throw new IllegalStateException(
                "Customer " + customerId.value() + " has no invoice number prefix, and the tenant "
                    + "uses CUSTOMER_SEQUENTIAL numbering. Add the prefix rather than falling back "
                    + "to the account prefix, which would merge both customers into one series.");
        }
        return format;
    }
}