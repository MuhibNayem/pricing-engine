package com.saas.pricing.metering.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Result of ingesting a meter event, including status and idempotency resolution.
 */
public record IngestionResult(
    Status status,
    String eventId,
    String idempotencyKey,
    String message
) implements Serializable {

    public enum Status {
        ACCEPTED,
        DUPLICATE,
        REJECTED_LATE,
        /** Same idempotency key, different content: a caller error, never a silent drop. */
        CONFLICT
    }

    public IngestionResult {
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public boolean isDuplicate() {
        return status == Status.DUPLICATE;
    }

    public boolean isRejectedLate() {
        return status == Status.REJECTED_LATE;
    }

    public boolean isConflict() {
        return status == Status.CONFLICT;
    }

    public static IngestionResult accepted(String eventId, String idempotencyKey) {
        return new IngestionResult(Status.ACCEPTED, eventId, idempotencyKey, "Event ingested successfully");
    }

    public static IngestionResult duplicate(String eventId, String idempotencyKey) {
        return new IngestionResult(Status.DUPLICATE, eventId, idempotencyKey, "Event skipped: duplicate idempotency key");
    }

    public static IngestionResult conflict(String eventId, String idempotencyKey, String reason) {
        return new IngestionResult(Status.CONFLICT, eventId, idempotencyKey,
            "Event rejected: idempotency key reused with different content - " + reason);
    }

    public static IngestionResult rejectedLate(String eventId, String idempotencyKey, String reason) {
        return new IngestionResult(Status.REJECTED_LATE, eventId, idempotencyKey, reason);
    }
}
