package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.SequenceAllocator;

import java.util.Objects;

/**
 * Allocates document numbers at finalization.
 *
 * <h2>Why numbering happens at finalization, not at draft</h2>
 * A draft is not a demand for payment and is routinely edited or abandoned. A number taken at draft
 * time is a number that may never appear on a document, and under {@link InvoiceNumberScheme#ACCOUNT_SEQUENTIAL}
 * that is exactly the gap the scheme exists to prove does not exist. Drafts therefore carry no
 * number — a database constraint, not a convention — and the sequence only moves when an invoice
 * actually becomes {@code OPEN}.
 *
 * <h2>Why a number is only consumed when the invoice is issued</h2>
 * The number is allocated immediately before the finalized invoice is written. If that write fails,
 * {@link #release} hands the value back, so a failed finalization leaves neither a document nor a
 * hole. The alternative — allocating and keeping — turns every transient database error into a
 * permanent gap in the tax series.
 */
public class InvoiceNumberService {

    private final SequenceAllocator allocator;
    private final InvoiceNumberPolicy policy;

    public InvoiceNumberService(SequenceAllocator allocator, InvoiceNumberPolicy policy) {
        this.allocator = Objects.requireNonNull(allocator, "allocator cannot be null");
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
    }

    public InvoiceNumberPolicy policy() {
        return policy;
    }

    /**
     * Allocates the next document number for a customer.
     *
     * @throws IllegalStateException under customer-level numbering with no prefix for this customer
     */
    public String allocateNext(TenantId tenantId, CustomerId customerId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");

        InvoiceNumberFormat format = policy.formatFor(customerId);
        long sequence = allocator.nextValue(tenantId, policy.sequenceKeyFor(customerId), policy.startAt());
        return format.render(sequence);
    }

    /**
     * Returns an allocated number to its series after a finalization that issued nothing.
     *
     * <p>Best-effort and idempotent: it only rewinds when the value is still the newest handed out,
     * so a number that has since been allocated to a real document is never re-issued.
     *
     * @return true if the number was returned to the series
     */
    public boolean release(TenantId tenantId, CustomerId customerId, String renderedNumber) {
        if (renderedNumber == null || renderedNumber.isBlank()) {
            return false;
        }
        InvoiceNumberFormat format = policy.formatFor(customerId);
        String prefix = format.prefix() + format.separator();
        if (!renderedNumber.startsWith(prefix)) {
            // Not one of ours — an imported or manually-set number. Leave the series alone.
            return false;
        }
        String digits = renderedNumber.substring(prefix.length());
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            return allocator.restore(tenantId, policy.sequenceKeyFor(customerId), Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The next number that would be issued, without consuming it. */
    public String peekNext(TenantId tenantId, CustomerId customerId) {
        InvoiceNumberFormat format = policy.formatFor(customerId);
        long next = allocator.peek(tenantId, policy.sequenceKeyFor(customerId), policy.startAt()) + 1;
        return next <= InvoiceNumberFormat.MAX_SEQUENCE
            ? format.render(next)
            : "<sequence exhausted>";
    }
}