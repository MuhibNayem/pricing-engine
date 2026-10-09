package com.saas.pricing.core.model.retention;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what happens to each class of record when a customer exercises GDPR Art. 17 erasure.
 *
 * <h2>The decision this replaces</h2>
 * The naive implementation of a right-to-erasure request is a blanket delete. For a billing system
 * that is actively harmful: it destroys the invoice numbers, ledger postings and contract history
 * that a tax authority requires and that a court needs in order to defend a claim. GDPR itself says
 * so — Art. 17(3)(b), (d) and (e) exempt processing carried out to comply with a legal obligation or
 * for legal claims.
 *
 * <p>So the correct answer is per-class, and this class produces exactly one of three outcomes per
 * class:
 *
 * <ul>
 *   <li>{@link Outcome#ERASE} — no statutory basis and retention expired: delete outright.</li>
 *   <li>{@link Outcome#ANONYMISE} — must be kept, but personal data can be stripped: blank the
 *       identifying fields and keep the financial facts.</li>
 *   <li>{@link Outcome#RETAIN} — must be kept in full, and the refusal must be explainable.</li>
 * </ul>
 *
 * <p>The point of {@link Outcome#RETAIN} carrying a {@link RetentionClass.LegalBasis} is that the
 * refusal is traceable to a specific article, which is what makes it defensible rather than
 * arbitrary.
 */
public final class ErasureDecision {

    /** What must happen to one class of record. */
    public enum Outcome {
        /** Delete the record outright. */
        ERASE,
        /** Keep the financial content; strip the personal data. */
        ANONYMISE,
        /** Keep in full. The refusal cites the statutory basis. */
        RETAIN
    }

    /**
     * The verdict for a single record class.
     *
     * @param recordClass which class was considered
     * @param outcome     what must happen to it
     * @param retention   the rule that produced the outcome
     * @param reason      a human-readable explanation, suitable for a data-subject response
     */
    public record PerClass(RetentionClass.RecordClass recordClass, Outcome outcome,
                            RetentionClass retention, String reason) {
        public PerClass {
            Objects.requireNonNull(recordClass, "recordClass cannot be null");
            Objects.requireNonNull(outcome, "outcome cannot be null");
            Objects.requireNonNull(retention, "retention cannot be null");
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /** The full verdict for one erasure request. */
    public record Plan(Instant evaluatedAt, List<PerClass> perClass) {
        public Plan {
            Objects.requireNonNull(evaluatedAt, "evaluatedAt cannot be null");
            perClass = perClass == null ? List.of() : List.copyOf(perClass);
        }

        public Optional<PerClass> forClass(RetentionClass.RecordClass recordClass) {
            return perClass.stream().filter(p -> p.recordClass() == recordClass).findFirst();
        }

        /** Classes that will be deleted outright. */
        public java.util.Set<RetentionClass.RecordClass> erased() {
            return collect(Outcome.ERASE);
        }

        /** Classes that keep financial content but lose personal data. */
        public java.util.Set<RetentionClass.RecordClass> anonymised() {
            return collect(Outcome.ANONYMISE);
        }

        /** Classes retained in full, with the statutory basis for the refusal. */
        public java.util.Set<RetentionClass.RecordClass> retained() {
            return collect(Outcome.RETAIN);
        }

        private java.util.Set<RetentionClass.RecordClass> collect(Outcome outcome) {
            return perClass.stream()
                .filter(p -> p.outcome() == outcome)
                .map(PerClass::recordClass)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }

        /** True when nothing at all is deleted, i.e. the request is refused in full. */
        public boolean isFullyRetained() {
            return erased().isEmpty();
        }

        /** A map suitable for a data-subject response or an article 30 record. */
        public Map<RetentionClass.RecordClass, String> asResponse() {
            Map<RetentionClass.RecordClass, String> response = new LinkedHashMap<>();
            perClass.forEach(p -> response.put(p.recordClass(), p.reason()));
            return response;
        }
    }

    private ErasureDecision() {
        // static utility
    }

    /**
     * Builds the default enterprise policy.
     *
     * <p>The financial periods are deliberately conservative and jurisdiction-agnostic; a deployment
     * should override them with its own statutory periods. What matters here is the <em>shape</em>:
     * the ledger is retained under a legal obligation, while telemetry and diagnostics expire and are
     * erased.
     */
    public static Map<RetentionClass.RecordClass, RetentionClass> defaultPolicy() {
        Map<RetentionClass.RecordClass, RetentionClass> policy = new LinkedHashMap<>();
        policy.put(RetentionClass.RecordClass.FINANCIAL_LEDGER,
            new RetentionClass(RetentionClass.RecordClass.FINANCIAL_LEDGER,
                java.time.Duration.ofDays(365 * 7), RetentionClass.LegalBasis.LEGAL_OBLIGATION,
                Optional.of(java.time.Duration.ofDays(365 * 7))));
        policy.put(RetentionClass.RecordClass.CONTRACT_HISTORY,
            new RetentionClass(RetentionClass.RecordClass.CONTRACT_HISTORY,
                java.time.Duration.ofDays(365 * 6), RetentionClass.LegalBasis.LEGAL_CLAIMS,
                Optional.of(java.time.Duration.ofDays(365 * 6))));
        policy.put(RetentionClass.RecordClass.AUDIT_TRAIL,
            new RetentionClass(RetentionClass.RecordClass.AUDIT_TRAIL,
                java.time.Duration.ofDays(365 * 3), RetentionClass.LegalBasis.LEGAL_OBLIGATION,
                Optional.of(java.time.Duration.ofDays(365 * 3))));
        policy.put(RetentionClass.RecordClass.ENTITLEMENT_EVENTS,
            new RetentionClass(RetentionClass.RecordClass.ENTITLEMENT_EVENTS,
                java.time.Duration.ofDays(365 * 2), RetentionClass.LegalBasis.OPERATIONAL_ONLY,
                Optional.empty()));
        policy.put(RetentionClass.RecordClass.USAGE_TELEMETRY,
            new RetentionClass(RetentionClass.RecordClass.USAGE_TELEMETRY,
                java.time.Duration.ofDays(400), RetentionClass.LegalBasis.OPERATIONAL_ONLY,
                Optional.empty()));
        policy.put(RetentionClass.RecordClass.DIAGNOSTIC,
            new RetentionClass(RetentionClass.RecordClass.DIAGNOSTIC,
                java.time.Duration.ofDays(30), RetentionClass.LegalBasis.OPERATIONAL_ONLY,
                Optional.empty()));
        return policy;
    }

    /**
     * Decides the outcome for every class, given the age of the oldest relevant record.
     *
     * @param policy        retention rule per class
     * @param oldestRecord  creation time of the oldest record in each class; a class with no records
     *                      is treated as absent rather than as erasable
     * @param now           evaluation time
     */
    public static Plan evaluate(Map<RetentionClass.RecordClass, RetentionClass> policy,
                                Map<RetentionClass.RecordClass, Instant> oldestRecord,
                                Instant now) {
        Objects.requireNonNull(policy, "policy cannot be null");
        Objects.requireNonNull(oldestRecord, "oldestRecord cannot be null");
        Objects.requireNonNull(now, "now cannot be null");

        List<PerClass> verdicts = new java.util.ArrayList<>();
        for (Map.Entry<RetentionClass.RecordClass, RetentionClass> entry : policy.entrySet()) {
            RetentionClass.RecordClass recordClass = entry.getKey();
            RetentionClass retention = entry.getValue();

            Instant createdAt = oldestRecord.get(recordClass);
            if (createdAt == null) {
                continue;
            }

            Outcome outcome;
            String reason;
            if (retention.isStatutorilyRetained()) {
                outcome = Outcome.RETAIN;
                reason = ("Retained under " + retention.legalBasis()
                    + " (GDPR Art. 17(3)). Statutory retention runs for "
                    + retention.minimumRetention().toDays() + " days from "
                    + createdAt.toString() + ".");
            } else if (retention.retentionExpired(createdAt, now)) {
                outcome = Outcome.ERASE;
                reason = "No statutory basis and retention of "
                    + retention.minimumRetention().toDays() + " days expired on "
                    + createdAt.plus(retention.minimumRetention()) + ".";
            } else {
                outcome = Outcome.RETAIN;
                reason = "Retention of " + retention.minimumRetention().toDays()
                    + " days has not yet expired.";
            }
            verdicts.add(new PerClass(recordClass, outcome, retention, reason));
        }
        return new Plan(now, verdicts);
    }

    /**
     * The subset of {@link Outcome#ANONYMISE} that a caller may act on: personal data stripped,
     * financial content kept.
     *
     * <p>Provided for deployments that want anonymisation to happen automatically once the
     * financial retention window permits it, without waiting for an erasure request.
     */
    public static boolean shouldAnonymise(RetentionClass retention, Instant createdAt, Instant now) {
        return retention.isStatutorilyRetained() && retention.erasable(createdAt, now);
    }
}