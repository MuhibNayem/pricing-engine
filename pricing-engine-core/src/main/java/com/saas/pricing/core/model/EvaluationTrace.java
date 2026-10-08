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
 */
public final class EvaluationTrace implements Serializable {

    private final String traceId;
    private final Instant timestamp;
    private final List<TraceStep> steps;

    public EvaluationTrace() {
        this(UUID.randomUUID().toString(), Instant.now(), new ArrayList<>());
    }

    public EvaluationTrace(String traceId, Instant timestamp, List<TraceStep> steps) {
        this.traceId = Objects.requireNonNull(traceId, "traceId cannot be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
        this.steps = new ArrayList<>(Objects.requireNonNull(steps, "steps cannot be null"));
    }

    public void addStep(TraceStep step) {
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
    public String toString() {
        return "EvaluationTrace[traceId=%s, stepsCount=%d]".formatted(traceId, steps.size());
    }
}
