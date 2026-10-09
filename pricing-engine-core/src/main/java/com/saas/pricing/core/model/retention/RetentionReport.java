package com.saas.pricing.core.model.retention;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Executes an {@link ErasureDecision.Plan}: what was actually done to each class of record.
 *
 * <h2>Why a decision is not the same as an action</h2>
 * {@link ErasureDecision} answers "what must happen". This answers "what did happen", which
 * matters for two reasons:
 *
 * <ul>
 *   <li><strong>Append-only tables cannot be deleted.</strong> The financial ledger and the
 *       entitlement stream reject {@code DELETE} at the database level, so an {@code ERASE}
 *       decision on them is <em>downgraded to anonymisation</em> rather than silently failing or,
 *       worse, quietly succeeding against a table that should never have lost a row.</li>
 *   <li><strong>The downgrade must be visible.</strong> A record class that was told to be erased
 *       and could only be anonymised is a finding an operator needs, not a detail to swallow.</li>
 * </ul>
 *
 * @param evaluatedAt when the plan was executed
 * @param results     one entry per record class considered
 */
public record RetentionReport(Instant evaluatedAt, List<Result> results) implements Serializable {

    /**
     * What was done to one record class.
     *
     * @param recordClass   the class considered
     * @param planned       what the decision asked for
     * @param performed     what was actually done
     * @param downgraded    true when the plan asked for erasure but the store is append-only, so
     *                      the record was anonymised instead
     * @param note          human-readable explanation, suitable for a DPO response
     */
    public record Result(
        RetentionClass.RecordClass recordClass,
        ErasureDecision.Outcome planned,
        ErasureDecision.Outcome performed,
        boolean downgraded,
        String note
    ) implements Serializable {
        public Result {
            Objects.requireNonNull(recordClass, "recordClass cannot be null");
            Objects.requireNonNull(planned, "planned cannot be null");
            Objects.requireNonNull(performed, "performed cannot be null");
            Objects.requireNonNull(note, "note cannot be null");
        }
    }

    public RetentionReport {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt cannot be null");
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** Classes actually erased outright. */
    public java.util.Set<RetentionClass.RecordClass> erased() {
        return collectWhere(r -> r.performed() == ErasureDecision.Outcome.ERASE);
    }

    /** Classes whose personal data was stripped but whose financial content was kept. */
    public java.util.Set<RetentionClass.RecordClass> anonymised() {
        return collectWhere(r -> r.performed() == ErasureDecision.Outcome.ANONYMISE);
    }

    /** Classes retained in full. */
    public java.util.Set<RetentionClass.RecordClass> retained() {
        return collectWhere(r -> r.performed() == ErasureDecision.Outcome.RETAIN);
    }

    /**
     * Classes that were meant to be erased but could only be anonymised.
     *
     * <p>Non-empty means an erasure request is only partially satisfied, which is a reportable
     * condition rather than a silent compromise.
     */
    public java.util.Set<RetentionClass.RecordClass> downgraded() {
        return results.stream()
            .filter(Result::downgraded)
            .map(Result::recordClass)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public boolean isFullySatisfied() {
        return downgraded().isEmpty();
    }

    private java.util.Set<RetentionClass.RecordClass> collectWhere(
            java.util.function.Predicate<Result> predicate) {
        return results.stream()
            .filter(predicate)
            .map(Result::recordClass)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}