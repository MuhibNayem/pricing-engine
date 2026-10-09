package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.core.spi.SubscriptionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subscription commands: every state change persists AND announces itself.
 *
 * <p>The property under test is the one a REST endpoint can silently break: a caller must not be
 * able to change a subscription without both writing the row and queueing the event, and must not
 * be able to bring a cancelled subscription back.
 */
class SubscriptionCommandServiceTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-31T00:00:00Z");
    private static final Instant MID = Instant.parse("2026-10-16T00:00:00Z");

    // Web-aware: the controller is @ConditionalOnWebApplication, so a plain
    // ApplicationContextRunner would silently skip it and make this test assert nothing.
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
                  () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1");

    private SubscriptionCommandService serviceAt(SubscriptionRepository repo, OutboxRepository outbox,
                                               Instant now) {
        return new SubscriptionCommandService(repo, outbox,
            java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("cancelling through the API persists the change and announces it")
    void cancelPersistsAndAnnounces() {
        contextRunner.run(context -> {
            var repo = context.getBean(SubscriptionRepository.class);
            var outbox = context.getBean(OutboxRepository.class);
            var lifecycle = context.getBean(SubscriptionLifecycleService.class);

            repo.create(Subscription.active("sub-1", TENANT, CUSTOMER, PLAN, START, END, START));
            var commands = serviceAt(repo, outbox, MID);

            var outcome = commands.cancel(TENANT, "sub-1", false, lifecycle, "SEATS",
                com.saas.pricing.core.model.Money.of("100.00",
                    com.saas.pricing.core.model.CurrencyUnit.USD));

            assertThat(repo.find(TENANT, "sub-1").orElseThrow().status())
                .as("the state change must be persisted, not just returned")
                .isEqualTo(Subscription.Status.CANCELED);
            assertThat(outbox.findUndelivered("subscription.canceled", 10))
                .as("a persisted change nobody was told about is the failure mode being prevented")
                .hasSize(1);
            assertThat(outcome.producesInvoiceLines()).isTrue();
            assertThat(outbox.findUndelivered("subscription.credit", 10))
                .as("credit produced by a cancellation is money owed, so it is announced too")
                .hasSize(1);
        });
    }

    @Test
    @DisplayName("a cancelled subscription cannot be resumed through the API")
    void cannotResumeCancelled() {
        contextRunner.run(context -> {
            var repo = context.getBean(SubscriptionRepository.class);
            var outbox = context.getBean(OutboxRepository.class);
            var lifecycle = context.getBean(SubscriptionLifecycleService.class);

            repo.create(Subscription.active("sub-2", TENANT, CUSTOMER, PLAN, START, END, START));
            var commands = serviceAt(repo, outbox, MID);
            commands.cancel(TENANT, "sub-2", false, lifecycle, "SEATS",
                com.saas.pricing.core.model.Money.of("100.00",
                    com.saas.pricing.core.model.CurrencyUnit.USD));

            assertThatThrownBy(() -> commands.resume(TENANT, "sub-2"))
                .isInstanceOf(IllegalStateException.class);
            assertThat(repo.find(TENANT, "sub-2").orElseThrow().status())
                .isEqualTo(Subscription.Status.CANCELED);
        });
    }

    @Test
    @DisplayName("period-end cancellation persists the flag and announces the schedule")
    void periodEndCancellationPersists() {
        contextRunner.run(context -> {
            var repo = context.getBean(SubscriptionRepository.class);
            var outbox = context.getBean(OutboxRepository.class);
            var lifecycle = context.getBean(SubscriptionLifecycleService.class);
            repo.create(Subscription.active("sub-3", TENANT, CUSTOMER, PLAN, START, END, START));

            var outcome = serviceAt(repo, outbox, MID)
                .cancel(TENANT, "sub-3", true, lifecycle, "SEATS",
                    com.saas.pricing.core.model.Money.of("100.00",
                        com.saas.pricing.core.model.CurrencyUnit.USD));

            assertThat(repo.find(TENANT, "sub-3").orElseThrow().isCancellingAtPeriodEnd()).isTrue();
            assertThat(outcome.producesInvoiceLines())
                .as("the customer keeps the period they paid for")
                .isFalse();
            assertThat(outbox.findUndelivered("subscription.cancellation_scheduled", 10)).hasSize(1);
        });
    }

    /**
     * The renewal sweep is a batch job over rows it does not control.
     *
     * <p>Every defect these pin down shared a shape: the code looked correct, the subscription row
     * ended up correct, and something downstream was quietly wrong.
     */
@Nested
@DisplayName("Renewal sweep")
class RenewalSweep {

    private SubscriptionRepository repo;
    private OutboxRepository outbox;

    private SubscriptionCommandService commands(Instant now) {
        return serviceAt(repo, outbox, now);
    }

    @Test
    @DisplayName("renewal skips cancelled subscriptions")
    void renewalSkipsCancelled() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);

            repo.create(Subscription.active("sub-live", TENANT, CUSTOMER, PLAN, START, END, START));
            repo.create(Subscription.active("sub-dead", TENANT, CUSTOMER, PLAN, START, END, START)
                .cancelImmediately(MID));

            var outcomes = commands(END).renewDue(TENANT, END);

            assertThat(outcomes).filteredOn(o -> o.renewed())
                .extracting(o -> o.subscriptionId())
                .as("a cancelled subscription must not renew itself back to life")
                .containsExactly("sub-live");
            assertThat(repo.find(TENANT, "sub-dead").orElseThrow().status())
                .isEqualTo(Subscription.Status.CANCELED);
        });
    }

    /**
     * Every renewal is announced, not just the first.
     *
     * <p>The event id was built from a constant sequence number, so every renewal of a subscription
     * after the first produced an identical id. The outbox refuses a duplicate id carrying different
     * content and silently drops one carrying identical content — so renewals two, three and onward
     * were discarded without a word. The row was right; nobody downstream ever heard about it.
     */
    @Test
    @DisplayName("every renewal is announced, not only the first")
    void everyRenewalIsAnnounced() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);

            repo.create(Subscription.active("sub-repeat", TENANT, CUSTOMER, PLAN, START, END, START));

            // Run three times, each a month later, so the same subscription renews three times.
            commands(END).renewDue(TENANT, END);
            commands(END.plusSeconds(40 * 86400L)).renewDue(TENANT, END.plusSeconds(40 * 86400L));
            commands(END.plusSeconds(70 * 86400L)).renewDue(TENANT, END.plusSeconds(70 * 86400L));

            assertThat(outbox.findUndelivered("subscription.renewed", 10))
                .as("a billing period that advanced silently is a period nobody invoiced")
                .hasSize(3);
        });
    }

    /**
     * Repeating pause and resume produces distinct events.
     *
     * <p>Both transitions land on states the subscription has held before within one billing period,
     * so any event id derived from the state repeats — and the second pause is discarded.
     */
    @Test
    @DisplayName("repeated pause and resume each produce their own event")
    void repeatedPauseAndResumeAreAllAnnounced() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);
            repo.create(Subscription.active("sub-flip", TENANT, CUSTOMER, PLAN, START, END, START));

            var commands = commands(MID);
            commands.pause(TENANT, "sub-flip");
            commands.resume(TENANT, "sub-flip");
            commands.pause(TENANT, "sub-flip");

            assertThat(outbox.findUndelivered("subscription.paused", 10))
                .as("the second pause is a real operator action and must be announced")
                .hasSize(2);
            assertThat(outbox.findUndelivered("subscription.resumed", 10)).hasSize(1);
        });
    }

    /**
     * A batch renewal must not be taken down by one row it cannot renew.
     *
     * <p>The job used to throw. The first paused or past-due subscription in the batch aborted the
     * whole sweep, leaving every later subscription unbilled — and paused subscriptions are not rare.
     */
    @Test
    @DisplayName("one unrenewable subscription does not stop the rest of the batch")
    void oneUnrenewableRowDoesNotStopTheBatch() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);

            repo.create(Subscription.active("sub-a", TENANT, CustomerId.of("ca"), PLAN, START, END, START));
            repo.create(Subscription.active("sub-b", TENANT, CustomerId.of("cb"), PLAN, START, END, START)
                .pause(MID));
            repo.create(Subscription.active("sub-c", TENANT, CustomerId.of("cc"), PLAN, START, END, START)
                .markPastDue());
            repo.create(Subscription.active("sub-d", TENANT, CustomerId.of("cd"), PLAN, START, END, START));

            var outcomes = commands(END).renewDue(TENANT, END);

            assertThat(outcomes).hasSize(4);
            assertThat(outcomes).filteredOn(o -> o.renewed())
                .extracting(o -> o.subscriptionId())
                .as("sub-a and sub-d are perfectly renewable and must not be held hostage")
                .containsExactlyInAnyOrder("sub-a", "sub-d");
            assertThat(outcomes).filteredOn(o -> !o.renewed())
                .allSatisfy(o -> assertThat(o.skipReason())
                    .as("a skipped subscription must say why, or it is a silent billing outage")
                    .isNotBlank());

            // The paused row must be untouched, not advanced and not corrupted.
            assertThat(repo.find(TENANT, "sub-b").orElseThrow().currentPeriodStart()).isEqualTo(START);
            assertThat(repo.find(TENANT, "sub-b").orElseThrow().status())
                .isEqualTo(Subscription.Status.PAUSED);
        });
    }

    /**
     * Each subscription renews on its own cadence, whatever the batch was told.
     *
     * <p>The old signature took one period length for the whole tenant. An annual plan renewed with
     * it would be billed twelve times a year, and every row would have looked internally consistent.
     */
    @Test
    @DisplayName("monthly and annual subscriptions keep their own cadence in one sweep")
    void eachSubscriptionKeepsItsOwnCadence() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);

            var yearEnd = Instant.parse("2027-01-01T00:00:00Z");
            repo.create(Subscription.active("sub-monthly", TENANT, CustomerId.of("c1"), PLAN, START, END, START));
            repo.create(Subscription.active("sub-annual", TENANT, CustomerId.of("c2"), PLAN,
                Instant.parse("2026-01-01T00:00:00Z"), yearEnd, START));

            commands(yearEnd).renewDue(TENANT, yearEnd);

            // The monthly plan has three boundaries to cross by 1 Jan, so it advances three times;
            // the annual plan has exactly one, and that is the point.
            assertThat(repo.find(TENANT, "sub-monthly").orElseThrow().periodLength())
                .as("a monthly plan keeps a monthly cadence however many periods it catches up")
                .isEqualTo(Duration.ofDays(30));
            assertThat(repo.find(TENANT, "sub-annual").orElseThrow().currentPeriodStart())
                .isEqualTo(yearEnd);
            assertThat(repo.find(TENANT, "sub-annual").orElseThrow().currentPeriodEnd())
                .as("an annual plan must not be silently converted to monthly")
                .isEqualTo(Instant.parse("2028-01-01T00:00:00Z"));
        });
    }

    /**
     * A job that was down for several periods catches up rather than skipping the gap.
     *
     * <p>The customer really was subscribed for those months; advancing one period and stopping
     * would leave them unbilled for the rest.
     */
    @Test
    @DisplayName("a backlog is caught up, one announced period at a time")
    void backlogIsCaughtUp() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);
            repo.create(Subscription.active("sub-stale", TENANT, CUSTOMER, PLAN, START, END, START));

            // Four month-long boundaries have elapsed since the subscription was last renewed.
            var muchLater = END.plusSeconds(95 * 86400L);
            var outcomes = commands(muchLater).renewDue(TENANT, muchLater);

            assertThat(outcomes).singleElement()
                .satisfies(o -> assertThat(o.periodsAdvanced()).isEqualTo(4));
            assertThat(outbox.findUndelivered("subscription.renewed", 10))
                .as("each crossed period boundary is a billable period")
                .hasSize(4);
            assertThat(repo.find(TENANT, "sub-stale").orElseThrow().currentPeriodEnd())
                .isEqualTo(Instant.parse("2027-02-28T00:00:00Z"))
                .isAfter(muchLater);
        });
    }

    /** A queued cancellation takes effect at renewal, and the sweep then stops touching it. */
    @Test
    @DisplayName("a queued cancellation takes effect and is not renewed again")
    void queuedCancellationTakesEffectAtRenewal() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);
            repo.create(Subscription.active("sub-ending", TENANT, CUSTOMER, PLAN, START, END, START)
                .queueCancellationAtPeriodEnd());

            var outcomes = commands(END).renewDue(TENANT, END);

            assertThat(outcomes).singleElement().satisfies(o -> {
                assertThat(o.periodsAdvanced()).isEqualTo(1);
                assertThat(o.finalState().status()).isEqualTo(Subscription.Status.CANCELED);
            });
            assertThat(repo.find(TENANT, "sub-ending").orElseThrow().status())
                .isEqualTo(Subscription.Status.CANCELED);
        });
    }

    /** A trial converts to billable instead of blocking the sweep that met it. */
    @Test
    @DisplayName("a finished trial converts and does not stop the sweep")
    void finishedTrialConvertsAndDoesNotBlockTheSweep() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);

            repo.create(Subscription.trialing("sub-trial", TENANT, CustomerId.of("ct"), PLAN,
                START, END, END, START));
            repo.create(Subscription.active("sub-normal", TENANT, CustomerId.of("cn"), PLAN, START, END, START));

            var outcomes = commands(END).renewDue(TENANT, END);

            assertThat(outcomes).filteredOn(o -> o.renewed()).hasSize(2);
            assertThat(repo.find(TENANT, "sub-trial").orElseThrow().status())
                .as("a trial that survives its own boundary is a customer who is never charged")
                .isEqualTo(Subscription.Status.ACTIVE);
        });
    }

    /** Renewal advances the stored version, which is what makes its event id unique. */
    @Test
    @DisplayName("renewal advances the persisted version")
    void renewalAdvancesVersion() {
        contextRunner.run(context -> {
            repo = context.getBean(SubscriptionRepository.class);
            outbox = context.getBean(OutboxRepository.class);
            repo.create(Subscription.active("sub-ver", TENANT, CUSTOMER, PLAN, START, END, START));

            commands(END).renewDue(TENANT, END);

            assertThat(repo.find(TENANT, "sub-ver").orElseThrow().version()).isEqualTo(1);
        });
    }
}

    @Test
    @DisplayName("the controller and command service are registered as beans")
    void beansRegistered() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SubscriptionCommandService.class);
            assertThat(context)
                .hasSingleBean(com.saas.pricing.starter.web.SubscriptionController.class);
        });
    }
}