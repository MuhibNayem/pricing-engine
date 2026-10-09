package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;

import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.model.retention.ErasureDecision;
import com.saas.pricing.core.model.retention.RetentionCapabilities;
import com.saas.pricing.core.model.retention.RetentionClass;
import com.saas.pricing.core.model.retention.RetentionReport;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes a retention or erasure plan.
 *
 * <p>A {@link ErasureDecision} says what must happen; this says what happened. The gap between
 * them is where a compliance control quietly stops being a control, so the execution is what gets
 * recorded and announced.
 *
 * <p>Every action is announced, including the ones that retain nothing — an erasure request that
 * results in "everything was legally retained" is a reportable outcome, and silently doing nothing
 * would be indistinguishable from never having received the request.
 */
public class RetentionService {

    public static final String TOPIC_RETENTION_EXECUTED = "retention.executed";
    public static final String TOPIC_RETENTION_DOWNGRADED = "retention.downgraded";

    private final Clock clock;
    private final OutboxRepository outboxRepository;
    private final RetentionActions actions;

    /** The operations retention may actually perform, per record class. */
    public interface RetentionActions {
        /**
         * Deletes records of {@code recordClass} created before {@code createdBefore}.
         *
         * @return how many rows were removed
         */
        int erase(RetentionClass.RecordClass recordClass, TenantId tenantId, Instant createdBefore);

        /**
         * Strips personal data while keeping the record's substantive content.
         *
         * @return how many rows were anonymised
         */
        int anonymise(RetentionClass.RecordClass recordClass, TenantId tenantId, Instant createdBefore);
    }

    public RetentionService(Clock clock, OutboxRepository outboxRepository, RetentionActions actions) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.outboxRepository = outboxRepository;
        this.actions = Objects.requireNonNull(actions, "actions cannot be null");
    }

    /**
     * Applies a plan for one customer.
     *
     * @param plan        the decision to execute
     * @param tenantId    tenant the request came from
     * @param customerId  subject of the erasure, when the plan is customer-scoped
     */
    public RetentionReport execute(ErasureDecision.Plan plan, TenantId tenantId, CustomerId customerId) {
        Objects.requireNonNull(plan, "plan cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");

        Instant now = plan.evaluatedAt();
        List<RetentionReport.Result> results = new ArrayList<>();
        int erased = 0;
        int anonymised = 0;

        for (var perClass : plan.perClass()) {
            var resolution = RetentionCapabilities.resolve(perClass.recordClass(), perClass.outcome());

            int affected = switch (resolution.performed()) {
                case ERASE -> actions.erase(perClass.recordClass(), tenantId, now);
                case ANONYMISE -> actions.anonymise(perClass.recordClass(), tenantId, now);
                case RETAIN -> 0;
            };
            erased += resolution.performed() == ErasureDecision.Outcome.ERASE ? affected : 0;
            anonymised += resolution.performed() == ErasureDecision.Outcome.ANONYMISE ? affected : 0;

            results.add(new RetentionReport.Result(
                perClass.recordClass(), perClass.outcome(), resolution.performed(),
                resolution.downgraded(), resolution.note() + " (" + affected + " record(s))"));
        }

        RetentionReport report = new RetentionReport(now, results);
        announce(tenantId, customerId, report, erased, anonymised);
        return report;
    }

    private void announce(TenantId tenantId, CustomerId customerId, RetentionReport report,
                          int erased, int anonymised) {
        if (outboxRepository == null) {
            return;
        }
        String summary = "erased=" + erased + ",anonymised=" + anonymised
            + ",retained=" + report.retained().size()
            + (report.isFullySatisfied() ? "" : ",downgraded=" + report.downgraded());

        outboxRepository.enqueue(
            com.saas.pricing.core.model.event.OutboxEvent.queued(
                com.saas.pricing.core.model.event.DomainEventFactory.eventId(TOPIC_RETENTION_EXECUTED, tenantId.value(), 8L),
                report.isFullySatisfied() ? TOPIC_RETENTION_EXECUTED : TOPIC_RETENTION_DOWNGRADED,
                tenantId.value(),
                "RETENTION_REQUEST",
                customerId == null ? tenantId.value() : customerId.value(),
                "{\"summary\":\"" + summary + "\"}",
                report.evaluatedAt(), report.evaluatedAt()));
    }

    /** Convenience: build the plan from a policy and the age of each class, then apply it. */
    public RetentionReport applyPolicy(Map<RetentionClass.RecordClass, RetentionClass> policy,
                                       Map<RetentionClass.RecordClass, Instant> oldestRecord,
                                       TenantId tenantId, CustomerId customerId) {
        return execute(ErasureDecision.evaluate(policy, oldestRecord, clock.instant()),
            tenantId, customerId);
    }

    /** Exposes the factory's random-id helper so callers without a natural key can still emit. */
    public static String newRetentionRequestId(String topic) {
        return com.saas.pricing.core.model.event.DomainEventFactory.randomEventId(topic);
    }
}