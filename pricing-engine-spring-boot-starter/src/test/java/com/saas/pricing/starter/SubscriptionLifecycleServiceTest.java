package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.invoice.ProrationReason;
import com.saas.pricing.core.model.subscription.Subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lifecycle change and the money it implies are one decision.
 *
 * <p>Splitting them across services is how a cancellation ends up issuing a credit for time the
 * customer actually used, or a subscription that stops being billed without the customer being
 * credited.
 */
class SubscriptionLifecycleServiceTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");

    // A 30-day period running 1-30 Oct, so "halfway" is exactly 0.5.
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-31T00:00:00Z");
    private static final Instant MID = Instant.parse("2026-10-16T00:00:00Z");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
                  () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1");

    private SubscriptionLifecycleService serviceAt(Instant now) {
        return new SubscriptionLifecycleService(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    private static Subscription active() {
        return Subscription.active("sub-1", TENANT, CUSTOMER, PLAN, START, END, START);
    }

    @Test
    @DisplayName("cancelling immediately credits the unused half of the period")
    void immediateCancellationCreditsUnusedTime() {
        var outcome = serviceAt(MID).cancelImmediately(active(), "SEATS", Money.of("100.00", USD));

        assertThat(outcome.subscription().status()).isEqualTo(Subscription.Status.CANCELED);
        assertThat(outcome.reason()).isEqualTo(ProrationReason.CANCELLED_EARLY);
        assertThat(outcome.producesInvoiceLines()).isTrue();

        var credit = outcome.adjustments().getFirst();
        assertThat(credit.kind()).isEqualTo(com.saas.pricing.core.model.invoice.ProrationAdjustment.Kind.CREDIT);
        assertThat(credit.amount().amount())
            .as("$100 for the period, half unused, so $50 comes back")
            .isEqualByComparingTo("50.00");
        assertThat(outcome.netAdjustment()).contains(Money.of("-50.00", USD));
    }

    @Test
    @DisplayName("cancelling at the period end credits nothing")
    void periodEndCancellationCreditsNothing() {
        var outcome = serviceAt(MID).cancelAtPeriodEnd(active());

        assertThat(outcome.subscription().isCancellingAtPeriodEnd()).isTrue();
        assertThat(outcome.producesInvoiceLines())
            .as("the customer keeps the period they already paid for")
            .isFalse();
        assertThat(outcome.netAdjustment()).isEmpty();
    }

    @Test
    @DisplayName("cancelling after the period has run credits nothing")
    void lateCancellationCreditsNothing() {
        var outcome = serviceAt(END.plusSeconds(86400))
            .cancelImmediately(active(), "SEATS", Money.of("100.00", USD));

        assertThat(outcome.adjustments())
            .as("a negative credit would hand the customer money they already used")
            .isEmpty();
    }

    @Test
    @DisplayName("a trial ending is billed as a subscription start, not a proration")
    void trialEndIsNotAProration() {
        var trialing = Subscription.trialing("sub-t", TENANT, CUSTOMER, PLAN, START, END, END, START);

        var outcome = serviceAt(END).completeTrial(trialing, "SEATS", "PRO", Money.of("100.00", USD),
            START, END);

        var debit = outcome.adjustments().getFirst();
        assertThat(debit.kind())
            .isEqualTo(com.saas.pricing.core.model.invoice.ProrationAdjustment.Kind.DEBIT);
        assertThat(debit.reason())
            .as("a full-period charge for a new subscription is not a proration")
            .isEqualTo(ProrationReason.SUBSCRIPTION_STARTED);
        assertThat(debit.reason().isProration()).isFalse();
    }

    @Test
    @DisplayName("a mid-period plan change produces the credit/debit pair")
    void planChangeProducesPair() {
        var outcome = serviceAt(MID).changePlan(active(), "SEATS",
            Money.of("10.00", USD), "PRO", Money.of("20.00", USD), MID, START, END);

        assertThat(outcome.adjustments()).hasSize(2);
        assertThat(outcome.netAdjustment().orElseThrow().amount()).isEqualByComparingTo("5.00");
        assertThat(outcome.adjustments().getFirst().reason().isProration()).isTrue();
    }

    @Test
    @DisplayName("the lifecycle service is registered as a bean")
    void registeredAsBean() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(SubscriptionLifecycleService.class));
    }
}