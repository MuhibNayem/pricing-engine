package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.core.spi.SubscriptionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The renewal job, and the endpoint that runs it.
 *
 * <p>These exist because renewal used to be correct, fully tested, and never invoked. A batch job
 * with no production caller is indistinguishable from a working one: the tests passed, the row
 * looked right, and in a deployed system nothing was ever renewed.
 *
 * <p>The report is the other half. A subscription that quietly stops renewing is a silent billing
 * outage, so a skipped row must say why rather than vanish from the results.
 */
class SubscriptionRenewalServiceTest {

    private static final TenantId TENANT = TenantId.of("t1");
    // Deliberately in the past. The endpoint runs the sweep against the real clock, so a period that
    // has not ended yet is correctly NOT due — which is why these fixtures must already be past
    // their boundary for the endpoint test to mean anything.
    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant MID = Instant.parse("2025-01-16T00:00:00Z");

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
            () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1");

    private void seedRenewableAndUnrenewable(org.springframework.context.ApplicationContext context) {
        var repo = context.getBean(SubscriptionRepository.class);
        repo.create(Subscription.active("sub-due", TENANT, CustomerId.of("c1"),
            PlanCode.of("PRO"), START, END, START));
        repo.create(Subscription.active("sub-paused", TENANT, CustomerId.of("c2"),
            PlanCode.of("PRO"), START, END, START).pause(MID));
    }

    @Test
    @DisplayName("the renewal service and its endpoint are both reachable")
    void renewalIsReachable() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SubscriptionRenewalService.class);
            assertThat(context)
                .hasSingleBean(com.saas.pricing.starter.web.SubscriptionController.class);
        });
    }

    @Test
    @DisplayName("a run reports what renewed and what did not, with reasons")
    void runReportsRenewedAndSkipped() {
        contextRunner.run(context -> {
            seedRenewableAndUnrenewable(context);
            var renewal = context.getBean(SubscriptionRenewalService.class);

            var report = renewal.run(TENANT, END);

            assertThat(report.renewed()).extracting(o -> o.subscriptionId())
                .containsExactly("sub-due");
            assertThat(report.skipped()).singleElement().satisfies(o -> {
                assertThat(o.subscriptionId()).isEqualTo("sub-paused");
                assertThat(o.skipReason())
                    .as("a subscription that quietly stopped renewing is a silent billing outage")
                    .contains("PAUSED");
            });
            assertThat(report.outcomes()).hasSize(2);
        });
    }

    /**
     * The endpoint is the production caller.
     *
     * <p>It exists so an operator can answer "did everyone get billed this month" without a deploy —
     * and, incidentally, so renewal is reachable at all.
     */
    @Test
    @DisplayName("the operator endpoint runs the sweep and returns the report")
    void endpointRunsTheSweep() {
        contextRunner.run(context -> {
            seedRenewableAndUnrenewable(context);
            var controller = context.getBean(com.saas.pricing.starter.web.SubscriptionController.class);

            var report = controller.runRenewals("t1");

            assertThat(report.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(report.getBody().tenantId()).isEqualTo("t1");
            assertThat(report.getBody().renewed()).isEqualTo(1);
            assertThat(report.getBody().skipped()).isEqualTo(1);
            assertThat(report.getBody().outcomes()).hasSize(2);
            assertThat(report.getBody().outcomes())
                .filteredOn(o -> o.skipReason() != null)
                .singleElement()
                .satisfies(o -> assertThat(o.subscriptionId()).isEqualTo("sub-paused"));
        });
    }

    /** The endpoint is tenant-guarded like every other, so a sweep cannot be aimed cross-tenant. */
    @Test
    @DisplayName("the renewal endpoint refuses a tenant the caller is not authenticated as")
    void endpointIsTenantGuarded() {
        contextRunner.run(context -> {
            var controller = context.getBean(com.saas.pricing.starter.web.SubscriptionController.class);

            assertThatThrownBy(() -> controller.runRenewals("someone-else"))
                .isInstanceOf(RuntimeException.class);
        });
    }

    /** Re-running after everything has renewed must be a no-op, not a second period. */
    @Test
    @DisplayName("a second run renews nothing further")
    void rerunningIsANoOp() {
        contextRunner.run(context -> {
            seedRenewableAndUnrenewable(context);
            var renewal = context.getBean(SubscriptionRenewalService.class);
            var outbox = context.getBean(OutboxRepository.class);

            renewal.run(TENANT, END);
            var second = renewal.run(TENANT, END);

            assertThat(second.renewed())
                .as("a subscription whose period has advanced is no longer due")
                .isEmpty();
            assertThat(outbox.findUndelivered("subscription.renewed", 10)).hasSize(1);
        });
    }

    /**
     * Catch-up is bounded, so a long outage cannot turn one run into thousands of writes.
     *
     * <p>The backlog is still there afterwards and the report says so, which is what lets an
     * operator watch it drain instead of discovering it later.
     */
    @Test
    @DisplayName("a very large backlog is bounded and reported as still owing")
    void backlogIsBoundedAndReported() {
        contextRunner.run(context -> {
            var repo = context.getBean(SubscriptionRepository.class);
            repo.create(Subscription.active("sub-ancient", TENANT, CustomerId.of("c1"),
                PlanCode.of("PRO"), START, END, START));
            var renewal = context.getBean(SubscriptionRenewalService.class);

            // Twelve years of monthly periods: far beyond the per-run ceiling.
            var twelveYearsOn = END.plusSeconds(12L * 365 * 86400);
            var report = renewal.run(TENANT, twelveYearsOn);

            assertThat(report.renewed()).singleElement()
                .satisfies(o -> assertThat(o.periodsAdvanced())
                    .isEqualTo(SubscriptionCommandService.MAX_CATCH_UP_PERIODS));
            assertThat(report.hasBacklog())
                .as("an operator must be able to see the job has not finished")
                .isTrue();
        });
    }
}