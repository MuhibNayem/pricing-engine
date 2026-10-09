package com.saas.pricing.core.model.marketplace;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The result of one marketplace metering submission.
 *
 * <p><strong>Partial failure is the normal case.</strong> A batched metering API returns a status per
 * item: most accepted, some rejected because the record is too old, references an unknown
 * dimension, or carries a quantity the listing does not allow. A boolean "did the call succeed"
 * cannot represent that, and treating a partial failure as success is how a seller quietly loses
 * revenue that the provider has already billed.
 *
 * <p>So the result distinguishes accepted, rejected and not-yet-submitted, and carries the provider's
 * own message for each rejection rather than a generic failure.
 */
public record MeteringResult(
    Instant submittedAt,
    List<Outcome> outcomes,
    String providerMessage
) implements Serializable {

    /** What the provider did with one record. */
    public record Outcome(
        String idempotencyKey,
        Status status,
        String dimension,
        String providerErrorCode,
        String providerMessage
    ) implements Serializable {
        public Outcome {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
            Objects.requireNonNull(status, "status cannot be null");
            Objects.requireNonNull(dimension, "dimension cannot be null");
        }
    }

    /** Per-record disposition. */
    public enum Status {
        /** The provider accepted and will bill this usage. */
        ACCEPTED("Success"),
        /** The provider refused this record; it will not be billed. */
        REJECTED("CustomerInput"),
        /** The provider's own fault, not the record's; retrying is reasonable. */
        ERROR("ServiceFailure"),
        /** Not yet sent - the batch never reached the provider. */
        PENDING("Pending");

        private final String awsStatus;

        Status(String awsStatus) {
            this.awsStatus = awsStatus;
        }

        /** The status token used by the AWS Metering API response. */
        public String providerStatus() {
            return awsStatus;
        }
    }

    public MeteringResult {
        Objects.requireNonNull(submittedAt, "submittedAt cannot be null");
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        providerMessage = providerMessage == null ? "" : providerMessage;
    }

    /** Builds a result from the provider's per-record status tokens. */
    public static MeteringResult fromProviderStatuses(Instant submittedAt,
                                                      List<MarketplaceUsageRecord> submitted,
                                                      List<String> providerStatuses,
                                                      List<String> errorMessages,
                                                      String providerMessage) {
        Objects.requireNonNull(submitted, "submitted cannot be null");
        Objects.requireNonNull(providerStatuses, "providerStatuses cannot be null");
        errorMessages = errorMessages == null ? List.of() : errorMessages;

        java.util.List<Outcome> outcomes = new java.util.ArrayList<>();
        for (int i = 0; i < submitted.size(); i++) {
            var record = submitted.get(i);
            String token = i < providerStatuses.size() ? providerStatuses.get(i) : "Pending";
            String message = i < errorMessages.size() ? errorMessages.get(i) : null;
            Status status = parseStatus(token);
            outcomes.add(new Outcome(record.idempotencyKey(), status, record.dimension(),
                status == Status.ACCEPTED ? null : token,
                message == null ? providerMessage : message));
        }
        return new MeteringResult(submittedAt, outcomes, providerMessage);
    }

    private static Status parseStatus(String token) {
        for (Status status : Status.values()) {
            if (status.providerStatus().equalsIgnoreCase(token)) {
                return status;
            }
        }
        return Status.PENDING;
    }

    public List<Outcome> accepted() {
        return byStatus(Status.ACCEPTED);
    }

    public List<Outcome> rejected() {
        return byStatus(Status.REJECTED);
    }

    public List<Outcome> errors() {
        return byStatus(Status.ERROR);
    }

    public List<Outcome> pending() {
        return byStatus(Status.PENDING);
    }

    private List<Outcome> byStatus(Status status) {
        return outcomes.stream().filter(o -> o.status() == status).toList();
    }

    /** True only when every record was accepted; a partial success is not a success. */
    public boolean isFullyAccepted() {
        return !outcomes.isEmpty() && rejected().isEmpty() && errors().isEmpty() && pending().isEmpty();
    }

    /** True when something failed that the provider blamed on the record itself. */
    public boolean hasRejected() {
        return !rejected().isEmpty();
    }

    /** A one-line summary an operator can act on, naming the failing dimensions. */
    public String summary() {
        if (isFullyAccepted()) {
            return "All " + outcomes.size() + " record(s) accepted";
        }
        return "accepted=" + accepted().size()
            + ", rejected=" + rejected().size()
            + ", errors=" + errors().size()
            + ", pending=" + pending().size()
            + (providerMessage.isBlank() ? "" : " (" + providerMessage + ")");
    }
}