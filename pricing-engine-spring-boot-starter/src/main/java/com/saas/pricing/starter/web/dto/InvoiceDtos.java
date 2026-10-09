package com.saas.pricing.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTOs for the invoice and reconciliation endpoints.
 *
 * <p>Kept separate from {@code PricingDtos} because invoices are a different concern from rating:
 * a rating produces an amount, an invoice is a document with a lifecycle.
 */
public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    /** Request to turn a rating into a draft invoice. */
    public record CreateInvoiceRequest(
        String invoiceId,
        String customerId,
        String tenantId,
        String planCode,
        String calculationId,
        String invoiceNumber,
        Instant periodStart,
        Instant periodEnd,
        String taxCode
    ) {
    }

    /** One invoice line. */
    public record InvoiceLineDto(
        String itemCode,
        String description,
        BigDecimal quantity,
        String unitPrice,
        String amount,
        String taxCode
    ) {
    }

    /** An invoice, rendered for an operator or a downstream system. */
    public record InvoiceDto(
        String invoiceId,
        String tenantId,
        String customerId,
        String planCode,
        String currency,
        String status,
        String invoiceNumber,
        Instant periodStart,
        Instant periodEnd,
        Instant issuedAt,
        String subtotal,
        String taxTotal,
        String total,
        String amountPaid,
        String balanceDue,
        List<InvoiceLineDto> lineItems,
        Map<String, String> metadata
    ) {
    }

    /** A page of invoices with an opaque cursor. */
    public record InvoicePageDto(List<InvoiceDto> invoices, String nextPageToken) {
    }

    /** Request to record a payment against an invoice. */
    public record RecordPaymentRequest(String amount, String currency) {
    }

    /** Request to issue a credit note against an invoice. */
    public record CreateCreditNoteRequest(
        String creditNoteId,
        String reason,
        String disposition,
        String tenantId
    ) {
    }

    /** A credit note, rendered for an operator. */
    public record CreditNoteDto(
        String creditNoteId,
        String invoiceId,
        String invoiceNumber,
        String currency,
        String status,
        String disposition,
        String total,
        String reason,
        Instant issuedAt
    ) {
    }

    /** Request to draft an invoice for a mid-period plan change. */
    public record PlanChangeRequest(
        String invoiceId,
        String tenantId,
        String customerId,
        String planCode,
        String currency,
        String itemCode,
        String oldPlanCode,
        String oldPrice,
        String newPlanCode,
        String newPrice,
        Instant periodStart,
        Instant periodEnd,
        Instant effectiveAt,
        Instant recordedAt
    ) {
    }

    /** A subscription, rendered for an operator or a client. */
    public record SubscriptionDto(
        String subscriptionId,
        String tenantId,
        String customerId,
        String planCode,
        String status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant createdAt,
        java.util.Optional<Instant> trialEndsAt,
        java.util.Optional<Instant> canceledAt,
        boolean producedInvoiceLines,
        String netAdjustment
    ) {
    }

    /** Request to cancel a subscription. */
    public record CancelSubscriptionRequest(
        String tenantId,
        String itemCode,
        String fullPeriodPrice,
        String currency,
        boolean atPeriodEnd
    ) {
    }

    /** The verdict for one feature in an entitlement reconciliation. */
    public record FeatureComparisonDto(
        String featureKey,
        String drift,
        String remediation,
        String derivedQuota,
        String storedQuota,
        String derivedUsage,
        String storedUsage
    ) {
    }

    /**
     * The result of reconciling derived entitlement state against stored rows.
     *
     * <p>Field order is intentional: {@code inSync} first so a monitoring check can read one boolean.
     */
    public record EntitlementReconciliationDto(
        boolean inSync,
        String tenantId,
        String customerId,
        Instant evaluatedAt,
        List<String> wronglyDenied,
        List<String> wronglyGranted,
        List<FeatureComparisonDto> features
    ) {
    }

    /**
     * What happened to one subscription during a renewal sweep.
     *
     * @param skipReason why it was left alone, or null when it renewed. Never omitted silently:
     *                   a subscription that quietly stopped renewing is a billing outage.
     */
    public record RenewalOutcomeDto(
        String subscriptionId,
        String status,
        int periodsAdvanced,
        Instant periodEnd,
        String skipReason
    ) {
    }

    /** The outcome of one tenant's renewal sweep. */
    public record RenewalReportDto(
        String tenantId,
        Instant ranAt,
        int renewed,
        int skipped,
        boolean hasBacklog,
        List<RenewalOutcomeDto> outcomes
    ) {
    }
}