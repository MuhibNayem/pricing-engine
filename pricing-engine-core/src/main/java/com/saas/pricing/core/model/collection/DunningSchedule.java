package com.saas.pricing.core.model.collection;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The retry ladder an invoice follows when payment fails.
 *
 * <h2>Why this belongs in the engine</h2>
 * Collections is where a rating engine's correctness becomes visible to a customer. Two rules make
 * it different from ordinary retry logic:
 *
 * <ul>
 *   <li><strong>A failed collection must never charge twice.</strong> Each attempt carries a stable
 *       {@code attemptId} derived from the invoice and attempt number, so a retried request is
 *       recognisably the same charge rather than a second one.</li>
 *   <li><strong>Exhaustion is a decision, not an accident.</strong> When the ladder runs out the
 *       invoice must not sit in {@code OPEN} forever pretending money is coming. It goes to
 *       {@code UNCOLLECTIBLE}, which is still reported but is no longer expected to be collected -
 *       the difference between a forecast and a receivable.</li>
 * </ul>
 *
 * <p>The default ladder follows the shape most card processors expect (immediate, then roughly
 * daily), but it is data, not code, so a deployment can match its own retry policy.
 */
public record DunningSchedule(
    List<DunningStep> steps,
    int maxAttempts
) implements Serializable {

    /**
     * One rung of the ladder.
     *
     * @param delayAfterPrevious  how long to wait after the previous attempt before this one
     * @param action              what to do when this attempt also fails
     */
    public record DunningStep(Duration delayAfterPrevious, FailureAction action) {
        public DunningStep {
            Objects.requireNonNull(delayAfterPrevious, "delayAfterPrevious cannot be null");
            Objects.requireNonNull(action, "action cannot be null");
            if (delayAfterPrevious.isNegative()) {
                throw new IllegalArgumentException("A dunning delay cannot be negative");
            }
        }
    }

    /** What a failure escalates to. */
    public enum FailureAction {
        /** Try again after the delay. */
        RETRY,
        /** Notify the customer, then keep retrying. */
        NOTIFY_AND_RETRY,
        /** Stop; write the invoice off. */
        WRITE_OFF,
        /** Stop; hand to a collections process. */
        HAND_TO_COLLECTIONS
    }

    public DunningSchedule {
        Objects.requireNonNull(steps, "steps cannot be null");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("A dunning schedule needs at least one step");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (maxAttempts > steps.size()) {
            throw new IllegalArgumentException(
                "maxAttempts " + maxAttempts + " exceeds the " + steps.size()
                    + " steps the schedule defines; a schedule cannot retry more times than it has rungs");
        }
    }

    /**
     * The default ladder: retry immediately, then daily for a week, then write off.
     *
     * <p>Chosen to match the retry windows most card networks treat as non-abusive, because a
     * ladder that retries too hard gets the merchant blocked rather than paid.
     */
    public static DunningSchedule standard() {
        List<DunningStep> steps = new ArrayList<>();
        steps.add(new DunningStep(Duration.ZERO, FailureAction.RETRY));
        for (int day = 1; day <= 7; day++) {
            steps.add(new DunningStep(Duration.ofDays(day), FailureAction.NOTIFY_AND_RETRY));
        }
        steps.add(new DunningStep(Duration.ofDays(7), FailureAction.WRITE_OFF));
        return new DunningSchedule(steps, 9);
    }

    /**
     * The instant {@code attemptNumber}-th attempt is due, given the first attempt time.
     *
     * <p>{@code steps.get(i).delayAfterPrevious()} is the wait <em>before</em> attempt {@code i+1},
     * so the first rung's delay offsets attempt 1 and each later rung offsets the next attempt.
     * Getting this indexing wrong makes every retry fire immediately, which is how a retry ladder
     * turns into a payment-storm.
     *
     * @param attemptNumber 1-based
     * @throws IllegalArgumentException if the attempt is beyond the configured maximum
     */
    public Instant dueAt(Instant firstAttemptAt, int attemptNumber) {
        Objects.requireNonNull(firstAttemptAt, "firstAttemptAt cannot be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber is 1-based, got " + attemptNumber);
        }
        if (attemptNumber > maxAttempts) {
            throw new IllegalArgumentException(
                "Attempt " + attemptNumber + " exceeds the maximum of " + maxAttempts);
        }
        Instant due = firstAttemptAt;
        for (int i = 0; i < attemptNumber; i++) {
            due = due.plus(steps.get(i).delayAfterPrevious());
        }
        return due;
    }

    /** The action to take when {@code attemptNumber} has just failed. */
    public FailureAction actionAfterFailure(int attemptNumber) {
        if (attemptNumber < 1 || attemptNumber > steps.size()) {
            throw new IllegalArgumentException("No action defined for attempt " + attemptNumber);
        }
        return steps.get(attemptNumber - 1).action();
    }

    /** True when the ladder has run out and the invoice should stop being collected. */
    public boolean isExhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /** True when a failure should terminate collection rather than schedule another attempt. */
    public boolean terminatesOnFailure(int attemptsMade) {
        FailureAction action = actionAfterFailure(attemptsMade);
        return action == FailureAction.WRITE_OFF || action == FailureAction.HAND_TO_COLLECTIONS;
    }
}