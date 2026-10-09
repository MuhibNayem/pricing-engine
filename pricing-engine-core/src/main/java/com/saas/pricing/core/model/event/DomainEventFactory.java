package com.saas.pricing.core.model.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the outbox events this engine emits.
 *
 * <p>Centralised on purpose. Topics and payload shapes are a <em>contract with subscribers</em>, so
 * scattering string literals across the codebase is how a subscriber silently breaks when one call
 * site spells a topic differently. Every event id is deterministic - {@code topic:aggregate:id:seq}
 * - so a retried transaction re-queues the same id and the outbox recognises it as a duplicate
 * instead of announcing the same change twice.
 *
 * <p>Note that valid time ({@code occurredAt}) and system time ({@code createdAt}) are recorded
 * separately, so a consumer can tell when something happened from when this engine learned of it.
 */
public final class DomainEventFactory {

    private DomainEventFactory() {
        // static utility
    }

    // ---------------------------------------------------------------- invoice

    public static final String TOPIC_INVOICE_DRAFTED = "invoice.drafted";
    public static final String TOPIC_INVOICE_FINALIZED = "invoice.finalized";
    public static final String TOPIC_INVOICE_PAID = "invoice.paid";
    public static final String TOPIC_INVOICE_VOIDED = "invoice.voided";
    public static final String TOPIC_CREDIT_NOTE_ISSUED = "invoice.credit_note_issued";

    /**
     * A draft invoice was created.
     *
     * <p>Distinct from {@link #TOPIC_INVOICE_FINALIZED}: a draft is editable and carries no
     * document number, so announcing it as "finalized" would have a subscriber provision
     * entitlements or raise a receivable for something that may still change.
     */
    public static OutboxEvent invoiceDrafted(String tenantId, String invoiceId, String total,
                                             String currency, Instant at) {
        return invoiceEvent(TOPIC_INVOICE_DRAFTED, tenantId, invoiceId, 0L, at, Map.of(
            "invoiceId", invoiceId,
            "status", "DRAFT",
            "total", total,
            "currency", currency));
    }

    /** An invoice was finalized and is now a demand for payment. */
    public static OutboxEvent invoiceFinalized(String tenantId, String invoiceId, String invoiceNumber,
                                               String status, String total, String currency,
                                               Instant at) {
        return invoiceEvent(TOPIC_INVOICE_FINALIZED, tenantId, invoiceId, 1L, at, Map.of(
            "invoiceId", invoiceId,
            "invoiceNumber", invoiceNumber,
            "status", status,
            "total", total,
            "currency", currency));
    }

    /** An invoice was settled, in full or in part. */
    public static OutboxEvent invoicePaid(String tenantId, String invoiceId, String status,
                                          String amountPaid, String balanceDue, String currency,
                                          Instant at) {
        return invoiceEvent(TOPIC_INVOICE_PAID, tenantId, invoiceId, 2L, at, Map.of(
            "invoiceId", invoiceId,
            "status", status,
            "amountPaid", amountPaid,
            "balanceDue", balanceDue,
            "currency", currency));
    }

    /** An invoice was cancelled. Retained for audit; never collected. */
    public static OutboxEvent invoiceVoided(String tenantId, String invoiceId, String total,
                                            String currency, Instant at) {
        return invoiceEvent(TOPIC_INVOICE_VOIDED, tenantId, invoiceId, 3L, at, Map.of(
            "invoiceId", invoiceId,
            "status", "VOID",
            "total", total,
            "currency", currency));
    }

    /**
     * A credit note was issued against an invoice.
     *
     * <p>Announced as its own topic rather than as an invoice update, because a refund is a distinct
     * downstream concern: it triggers a customer notification, a revenue reversal, and often a
     * payment-processor action.
     */
    public static OutboxEvent creditNoteIssued(String tenantId, String creditNoteId, String invoiceId,
                                                String invoiceNumber, String total, String currency,
                                                String disposition, String reason, Instant at) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("creditNoteId", creditNoteId);
        payload.put("invoiceId", invoiceId);
        payload.put("invoiceNumber", invoiceNumber);
        payload.put("total", total);
        payload.put("currency", currency);
        payload.put("disposition", disposition);
        payload.put("reason", reason);
        return event(TOPIC_CREDIT_NOTE_ISSUED, tenantId, "CREDIT_NOTE", creditNoteId, 4L, payload, at);
    }

    // ------------------------------------------------------------- entitlement

    public static final String TOPIC_ENTITLEMENT_GRANTED = "entitlement.granted";
    public static final String TOPIC_ENTITLEMENT_REVOKED = "entitlement.revoked";

    public static OutboxEvent entitlementGranted(String tenantId, String customerId, String featureKey,
                                                 String reason, Instant at) {
        return event(TOPIC_ENTITLEMENT_GRANTED, tenantId, "ENTITLEMENT", featureKey, 5L,
            Map.of("customerId", customerId, "featureKey", featureKey, "reason", reason), at);
    }

    public static OutboxEvent entitlementRevoked(String tenantId, String customerId, String featureKey,
                                                 String reason, Instant at) {
        return event(TOPIC_ENTITLEMENT_REVOKED, tenantId, "ENTITLEMENT", featureKey, 6L,
            Map.of("customerId", customerId, "featureKey", featureKey, "reason", reason), at);
    }

    // ------------------------------------------------------------------ wallet

    public static final String TOPIC_WALLET_DRAWN_DOWN = "wallet.drawn_down";

    public static OutboxEvent walletDrawnDown(String tenantId, String walletId, String calculationId,
                                              String creditsDrawn, String moneyValue, String currency,
                                              Instant at) {
        return event(TOPIC_WALLET_DRAWN_DOWN, tenantId, "WALLET", walletId, 7L,
            Map.of("walletId", walletId,
                "calculationId", calculationId,
                "creditsDrawn", creditsDrawn,
                "moneyValue", moneyValue,
                "currency", currency), at);
    }

    // -------------------------------------------------------------- internals

    private static OutboxEvent invoiceEvent(String topic, String tenantId, String invoiceId,
                                            long sequence, Instant at, Map<String, String> payload) {
        return event(topic, tenantId, "INVOICE", invoiceId, sequence, payload, at);
    }

    private static OutboxEvent event(String topic, String tenantId, String aggregateType,
                                     String aggregateId, long sequence, Map<String, String> payload,
                                     Instant at) {
        return OutboxEvent.queued(
            eventId(topic, aggregateId, sequence),
            topic,
            tenantId,
            aggregateType,
            aggregateId,
            render(payload),
            at,
            at);
    }

    /**
     * Deterministic event id: the same logical change always produces the same id, so a retried
     * transaction cannot announce it twice.
     */
    public static String eventId(String topic, String aggregateId, long sequence) {
        return topic + ":" + aggregateId + ":" + sequence;
    }

    /** Random id, for callers with no natural key to derive from. */
    public static String randomEventId(String topic) {
        return topic + ":" + UUID.randomUUID();
    }

    private static String render(Map<String, String> payload) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"")
               .append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}