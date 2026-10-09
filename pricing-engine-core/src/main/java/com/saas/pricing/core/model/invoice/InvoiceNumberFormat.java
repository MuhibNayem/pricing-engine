package com.saas.pricing.core.model.invoice;

import java.io.Serializable;
import java.util.Objects;

/**
 * How one sequence is rendered as a document number, e.g. {@code INV-0007}.
 *
 * <h2>Why the prefix is constrained</h2>
 * A prefix of 1-12 uppercase letters or digits, and nothing else, matches what Stripe enforces and
 * for the same reason: the number is typed by people, read aloud on the phone, and printed on
 * documents that get scanned. Lowercase, spaces and punctuation all produce support tickets.
 *
 * <h2>Why there is a hard ceiling</h2>
 * The rendered number is capped at 26 characters and the sequence at one billion, so the padding
 * width can never silently change the shape of a document number that has already been issued —
 * which is what happens when a system that padded to three digits reaches 1000.
 *
 * @param prefix    1-12 uppercase letters or digits
 * @param separator between prefix and sequence
 * @param padding   digits in the sequence, at least 1
 */
public record InvoiceNumberFormat(
    String prefix,
    String separator,
    int padding
) implements Serializable {

    /** Stripe's documented maximum for an invoice number. */
    public static final int MAX_LENGTH = 26;

    /** Stripe's documented ceiling on the sequence value. */
    public static final long MAX_SEQUENCE = 1_000_000_000L;

    private static final int MAX_PREFIX_LENGTH = 12;

    public InvoiceNumberFormat {
        Objects.requireNonNull(prefix, "prefix cannot be null");
        Objects.requireNonNull(separator, "separator cannot be null");

        prefix = prefix.trim().toUpperCase(java.util.Locale.ROOT);
        if (prefix.isEmpty() || prefix.length() > MAX_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                "Invoice number prefix must be 1-" + MAX_PREFIX_LENGTH + " characters, got '"
                    + prefix + "'");
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!allowed) {
                throw new IllegalArgumentException(
                    "Invoice number prefix may contain only uppercase letters and digits, got '"
                        + prefix + "'");
            }
        }
        if (padding < 1 || padding > String.valueOf(MAX_SEQUENCE).length()) {
            throw new IllegalArgumentException(
                "Invoice number padding must be between 1 and " + String.valueOf(MAX_SEQUENCE).length()
                    + ", got " + padding);
        }
        if (prefix.length() + separator.length() + padding > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "Rendered invoice number would exceed " + MAX_LENGTH + " characters");
        }
    }

    /** {@code INV-0001}: uppercase prefix, hyphen, four digits. */
    public static InvoiceNumberFormat of(String prefix) {
        return new InvoiceNumberFormat(prefix, "-", 4);
    }

    /** Renders a sequence value, e.g. {@code 7} as {@code INV-0007}. */
    public String render(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("Invoice sequence starts at 1, got " + sequence);
        }
        if (sequence > MAX_SEQUENCE) {
            throw new IllegalStateException(
                "Invoice sequence exhausted the maximum of " + MAX_SEQUENCE
                    + "; widen the padding or start a new series rather than reuse numbers");
        }
        String digits = Long.toString(sequence);
        // Left-pad, never truncate: a number wider than the padding is still a valid, unique
        // document number, and refusing to render it would strand the tenant with no invoice.
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < padding; i++) {
            padded.append('0');
        }
        padded.append(digits);
        return prefix + separator + padded;
    }
}