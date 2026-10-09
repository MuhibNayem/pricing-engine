package com.saas.pricing.core.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Audit ledger detailing the full calculation process for transparency and auditing.
 *
 * <p>Mutable while a rating is being assembled ({@link #addStep}), then frozen into an immutable
 * value by the result that carries it. Without the freeze, two structurally identical pricing
 * results could never compare equal - the trace compared by identity - and a stored trace could be
 * mutated after the fact.
 */
public final class EvaluationTrace implements Serializable {

    private final String traceId;
    private final Instant timestamp;
    private final List<TraceStep> steps;
    private final boolean mutable;

    public EvaluationTrace() {
        this(UUID.randomUUID().toString(), Instant.now(), new ArrayList<>());
    }

    public EvaluationTrace(String traceId, Instant timestamp, List<TraceStep> steps) {
        this(traceId, timestamp, new ArrayList<>(Objects.requireNonNull(steps, "steps cannot be null")), true);
    }

    private EvaluationTrace(String traceId, Instant timestamp, List<TraceStep> steps, boolean mutable) {
        this.traceId = Objects.requireNonNull(traceId, "traceId cannot be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
        this.steps = steps;
        this.mutable = mutable;
    }

    /** An immutable snapshot of this trace, safe to carry on a published result. */
    public EvaluationTrace frozen() {
        return new EvaluationTrace(traceId, timestamp, List.copyOf(steps), false);
    }

    public void addStep(TraceStep step) {
        if (!mutable) {
            throw new IllegalStateException(
                "This trace is frozen; a published result's audit trail cannot be mutated");
        }
        this.steps.add(Objects.requireNonNull(step, "step cannot be null"));
    }

    public void addStep(String stepName, String description) {
        addStep(TraceStep.of(stepName, description));
    }

    public String traceId() {
        return traceId;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public List<TraceStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof EvaluationTrace trace
            && traceId.equals(trace.traceId)
            && timestamp.equals(trace.timestamp)
            && steps.equals(trace.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(traceId, timestamp, steps);
    }

    @Override
    public String toString() {
        return "EvaluationTrace[traceId=%s, stepsCount=%d]".formatted(traceId, steps.size());
    }
}
