package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.DomainEventFactory;
import com.saas.pricing.core.model.event.OutboxEvent;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.core.spi.SubscriptionRepository;

import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Subscription commands: the only supported way to change a subscription's state.
 *
 * <h2>Why transitions go through here</h2>
 * A subscription change has three consequences that must not be separated: the new state, the
 * money it implies, and the announcement of both. A controller that writes the row directly can
 * skip the announcement, and the customer is then in a state nobody downstream knows about.
 *
 * <p>So every method persists <em>and</em> enqueues. There is no code path that changes a
 * subscription without telling anyone.
 */
public class SubscriptionCommandService {

    /**
     * Ceiling on periods advanced for one subscription in a single run.
     *
     * <p>A job that has been down for years would otherwise try to catch up in one call and hold a
     * transaction open for thousands of writes. The run stops here, the subscription stays due, and
     * the next run continues — which is also what an operator watching a backlog drain wants to see.
     */
    static final int MAX_CATCH_UP_PERIODS = 120;

    private final SubscriptionRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    public SubscriptionCommandService(SubscriptionRepository repository, OutboxRepository outbox,
                                      Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /** Persists a new subscription and announces it. */
    @Transactional
    public Subscription create(Subscription subscription) {
        repository.create(subscription);
        announce(subscription, "subscription.created");
        return subscription;
    }

    /**
     * Cancels immediately, or at the period boundary, and announces the outcome including any
     * credit the cancellation produced.
     */
    @Transactional
    public SubscriptionLifecycleService.Outcome cancel(TenantId tenantId, String subscriptionId,
                                                     boolean atPeriodEnd,
                                                     SubscriptionLifecycleService lifecycle,
                                                     String itemCode,
                                                     Money fullPeriodPrice) {
        Subscription existing = require(tenantId, subscriptionId);

        SubscriptionLifecycleService.Outcome outcome = atPeriodEnd
            ? lifecycle.cancelAtPeriodEnd(existing)
            : lifecycle.cancelImmediately(existing, itemCode, fullPeriodPrice);

        repository.update(outcome.subscription());
        announce(outcome.subscription(), atPeriodEnd
            ? "subscription.cancellation_scheduled"
            : "subscription.canceled");

        // The credit lines a cancellation produced are money the customer is owed, so they are
        // announced separately from the state change.
        for (int i = 0; i < outcome.adjustments().size(); i++) {
            var adjustment = outcome.adjustments().get(i);
            // Keyed on the new version and the line's position, not on its amount: two credits for
            // the same subscription of the same size are two distinct facts, and an amount-derived
            // id would let the outbox silently drop the second one.
            outbox.enqueue(OutboxEvent.queued(
                DomainEventFactory.eventId("subscription.credit", subscriptionId,
                    outcome.subscription().version() * 100L + i),
                "subscription.credit",
                tenantId.value(),
                "SUBSCRIPTION",
                subscriptionId,
                "{\"amount\":\"" + adjustment.signedAmount().amount().toPlainString() + "\"}",
                clock.instant(), clock.instant()));
        }
        return outcome;
    }

    /** Pauses a subscription. Refused once terminal, or if already paused, by the aggregate. */
    @Transactional
    public Subscription pause(TenantId tenantId, String subscriptionId) {
        Subscription paused = require(tenantId, subscriptionId).pause(clock.instant());
        repository.update(paused);
        announce(paused, "subscription.paused");
        return paused;
    }

    @Transactional
    public Subscription resume(TenantId tenantId, String subscriptionId) {
        Subscription resumed = require(tenantId, subscriptionId).resume();
        repository.update(resumed);
        announce(resumed, "subscription.resumed");
        return resumed;
    }

    /** Marks billing as failed so collection can take over. */
    @Transactional
    public Subscription markPastDue(TenantId tenantId, String subscriptionId) {
        Subscription pastDue = require(tenantId, subscriptionId).markPastDue();
        repository.update(pastDue);
        announce(pastDue, "subscription.past_due");
        return pastDue;
    }

    /**
     * Renews every subscription whose period has ended.
     *
     * <h4>Each subscription renews on its own cadence</h4>
     * There is deliberately no period-length parameter. A single length applied to the whole batch
     * silently re-bills an annual plan twelve times a year, and anchoring the new period on "now"
     * instead of on the subscription's own boundary drags that boundary forward by the time of day
     * the job runs, on every cycle, forever. Both are answered by {@link Subscription#renew}.
     *
     * <h4>One subscription cannot stop the rest</h4>
     * A batch renewal that throws on its first problem leaves every later subscription unbilled, and
     * the most common problem is not exotic: a single paused or past-due row would take the entire
     * tenant's billing down with it. Each subscription is renewed inside its own guard, and the
     * reason is reported rather than thrown.
     *
     * <h4>A backlog is caught up, not skipped</h4>
     * A job that was down for three months renews three times, announcing each period boundary it
     * crossed — the customer really was subscribed for those months. Bounded by
     * {@link #MAX_CATCH_UP_PERIODS}.
     *
     * @param at the instant the job is running
     * @return one outcome per due subscription, including those that were skipped and why
     */
    @Transactional
    public List<RenewalOutcome> renewDue(TenantId tenantId, Instant at) {
        List<RenewalOutcome> outcomes = new ArrayList<>();
        for (Subscription due : repository.findDueForRenewal(tenantId, at)) {
            outcomes.add(renewOne(due, at));
        }
        return outcomes;
    }

    private RenewalOutcome renewOne(Subscription due, Instant at) {
        Subscription current = due;
        int advanced = 0;

        while (current.isRenewalDue(at)) {
            Subscription next;
            try {
                next = current.renew(at);
            } catch (RuntimeException notRenewable) {
                // Isolation. The row is reported, not thrown: this subscription is not billable,
                // and the rest of the tenant's renewals must still go through.
                return new RenewalOutcome(due.subscriptionId(), advanced, current,
                    notRenewable.getMessage());
            }

            repository.update(next);
            announce(next, "subscription.renewed");
            current = next;
            advanced++;

            // A queued cancellation took effect, so there is nothing further to advance.
            if (current.status().isTerminal() || advanced >= MAX_CATCH_UP_PERIODS) {
                break;
            }
        }

        return new RenewalOutcome(due.subscriptionId(), advanced, current, null);
    }

    /** Every subscription a customer holds, live or terminal. */
    public List<Subscription> listForCustomer(TenantId tenantId, CustomerId customerId) {
        return repository.findByCustomer(tenantId, customerId);
    }

    public Subscription require(TenantId tenantId, String subscriptionId) {
        return repository.find(tenantId, subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown subscription " + subscriptionId));
    }

    /**
     * Announces a state change.
     *
     * <p>The event id is keyed on the subscription's <strong>version</strong>, which advances on
     * every transition. A constant sequence number was the original defect here and it was
     * invisible: the outbox refuses a duplicate id carrying <em>different</em> content and silently
     * drops one carrying identical content, so every announcement after the first for a given
     * topic was discarded. The subscription row was correct, the version was right, and no
     * subscriber ever heard about it.
     */
    private void announce(Subscription subscription, String topic) {
        outbox.enqueue(OutboxEvent.queued(
            DomainEventFactory.eventId(topic, subscription.subscriptionId(), subscription.version()),
            topic,
            subscription.tenantId().value(),
            "SUBSCRIPTION",
            subscription.subscriptionId(),
            "{\"status\":\"" + subscription.status().name()
                + "\",\"customerId\":\"" + subscription.customerId().value()
                + "\",\"periodEnd\":\"" + subscription.currentPeriodEnd() + "\""
                + ",\"version\":" + subscription.version() + "}",
            clock.instant(), clock.instant()));
    }

    /**
     * What happened to one subscription during a renewal run.
     *
     * @param subscriptionId  the subscription considered
     * @param periodsAdvanced how many billing periods were advanced; 0 when skipped
     * @param finalState      the state the subscription is now in
     * @param skipReason      why it was not renewed, or null when it was
     */
    public record RenewalOutcome(
        String subscriptionId,
        int periodsAdvanced,
        Subscription finalState,
        String skipReason
    ) {

        public RenewalOutcome {
            Objects.requireNonNull(subscriptionId, "subscriptionId cannot be null");
            Objects.requireNonNull(finalState, "finalState cannot be null");
            if (periodsAdvanced < 0) {
                throw new IllegalArgumentException("periodsAdvanced cannot be negative");
            }
        }

        public boolean renewed() {
            return skipReason == null;
        }
    }
}