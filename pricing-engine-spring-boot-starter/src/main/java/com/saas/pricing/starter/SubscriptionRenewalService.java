package com.saas.pricing.starter;

import com.saas.pricing.core.model.TenantId;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The renewal job.
 *
 * <h2>Why this exists separately from the command service</h2>
 * {@link SubscriptionCommandService#renewDue} knows how to advance one tenant's subscriptions
 * correctly. What was missing was anything that actually <em>ran</em> it: renewal had a test caller
 * and no production caller, so in a deployed system no subscription was ever renewed. A capability
 * that is correct, fully tested and never invoked reads exactly like a working one.
 *
 * <h2>Why the library does not own the timer</h2>
 * Renewal has to run for every tenant on the platform, and this library deliberately does not know
 * what a platform's tenants are - {@code TenantResolver} is supplied by the host for exactly this
 * reason, and inventing a second, competing source of tenant identity would undermine the isolation
 * the rest of the code enforces. So the sweep is the host's:
 *
 * <pre>{@code
 * @Scheduled(fixedDelayString = "PT5M")
 * public void renewEverything() {
 *     for (TenantId tenant : myTenantSource.allTenants()) {
 *         renewalService.run(tenant, clock.instant());
 *     }
 * }
 * }</pre>
 *
 * <p>Running it more often than the shortest billing period is safe: a subscription whose period
 * has not ended is not due and is not touched. Running it too rarely is the real risk, which is why
 * {@link RenewalReport#skipped()} exists - a tenant whose subscriptions are all being skipped is a
 * silent billing outage otherwise.
 *
 * <p>Raising the invoice for a renewed period stays with the host's rating call. This job advances
 * the billing period and announces it; the amounts come from the same rating path as any other
 * invoice, and are issued through {@code POST /invoices} under an idempotency key.
 */
public class SubscriptionRenewalService {

    private final SubscriptionCommandService commands;
    private final Clock clock;

    public SubscriptionRenewalService(SubscriptionCommandService commands, Clock clock) {
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /** Runs renewal for one tenant as of now. */
    public RenewalReport run(TenantId tenantId) {
        return run(tenantId, clock.instant());
    }

    /**
     * Runs renewal for one tenant as of {@code at}.
     *
     * @param at the instant to evaluate the schedule against
     */
    public RenewalReport run(TenantId tenantId, Instant at) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(at, "at cannot be null");

        List<SubscriptionCommandService.RenewalOutcome> outcomes = commands.renewDue(tenantId, at);

        var renewed = outcomes.stream()
            .filter(SubscriptionCommandService.RenewalOutcome::renewed)
            .toList();
        var skipped = outcomes.stream()
            .filter(outcome -> !outcome.renewed())
            .toList();

        return new RenewalReport(tenantId, at, outcomes, renewed, skipped);
    }

    /**
     * The outcome of one tenant's renewal run.
     *
     * @param tenantId       the tenant processed
     * @param at             the instant the run evaluated
     * @param outcomes       every due subscription considered
     * @param renewed        those whose billing period advanced
     * @param skipped        those deliberately left alone, each with a reason
     */
    public record RenewalReport(
        TenantId tenantId,
        Instant at,
        List<SubscriptionCommandService.RenewalOutcome> outcomes,
        List<SubscriptionCommandService.RenewalOutcome> renewed,
        List<SubscriptionCommandService.RenewalOutcome> skipped
    ) {
        public RenewalReport {
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(at, "at cannot be null");
            outcomes = List.copyOf(outcomes);
            renewed = List.copyOf(renewed);
            skipped = List.copyOf(skipped);
        }

        /** Subscriptions still awaiting renewal after the run - a backlog the operator should see. */
        public boolean hasBacklog() {
            return outcomes.stream()
                .anyMatch(outcome -> outcome.renewed()
                    && outcome.finalState().isRenewalDue(at));
        }
    }
}