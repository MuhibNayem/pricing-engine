package com.saas.pricing.core.model.invoice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proration classification is by <em>operation</em>, never by period length.
 *
 * <p>The distinction that matters: a full-period debit caused by moving the billing cycle anchor
 * IS a proration, while the identical full-period debit caused by simply starting a subscription is
 * NOT. Nothing in the amount or the dates distinguishes them - only the operation.
 *
 * <p>Classify by amount and every mid-cycle anchor move gets booked as a new subscription, which
 * breaks revenue recognition for the affected period.
 */
class ProrationReasonTest {

    @Test
    @DisplayName("an anchor change is a proration even though it bills a whole period")
    void anchorChangeIsAProration() {
        assertThat(ProrationReason.ANCHOR_CHANGED.isProration())
            .as("the period already ran and was re-cut, so revenue must be adjusted")
            .isTrue();
    }

    @Test
    @DisplayName("a subscription's first full-period charge is NOT a proration")
    void subscriptionStartIsNotAProration() {
        assertThat(ProrationReason.SUBSCRIPTION_STARTED.isProration())
            .as("a new charge opens a period rather than adjusting one that already ran")
            .isFalse();
        assertThat(ProrationReason.SUBSCRIPTION_STARTED.producesDebit()).isTrue();
        assertThat(ProrationReason.SUBSCRIPTION_STARTED.producesCredit()).isFalse();
    }

    @Test
    @DisplayName("ending a trial legitimately yields both a credit and a debit")
    void trialEndYieldsBoth() {
        assertThat(ProrationReason.TRIAL_ENDED.isProration()).isTrue();
        assertThat(ProrationReason.TRIAL_ENDED.producesCredit())
            .as("unused trial time is refunded as a proration")
            .isTrue();
        assertThat(ProrationReason.TRIAL_ENDED.producesDebit())
            .as("the next complete period is a plain charge that accompanies the credit")
            .isTrue();
        assertThat(ProrationReason.TRIAL_ENDED.producesBoth())
            .as("the calculator must be able to emit both sides for a trial end")
            .isTrue();
    }

    @Test
    @DisplayName("each operation declares the sides it can carry")
    void operationsDeclareTheirSides() {
        assertThat(ProrationReason.PLAN_CHANGED.producesBoth()).isTrue();
        assertThat(ProrationReason.CANCELLED_EARLY.producesCredit()).isTrue();
        assertThat(ProrationReason.CANCELLED_EARLY.producesDebit()).isFalse();
        assertThat(ProrationReason.RESUMED.producesDebit()).isTrue();
        assertThat(ProrationReason.RESUMED.producesCredit()).isFalse();
        assertThat(ProrationReason.MANUAL_ADJUSTMENT.producesBoth()).isTrue();
    }

    @Test
    @DisplayName("an adjustment whose side contradicts its operation is refused")
    void mismatchedSideRefused() {
        var credit = Money.of("5.00", CurrencyUnit.USD);

        // A subscription start produces a debit and no credit, so a credit line under it is wrong.
        // (A credit under PLAN_CHANGED is legitimate - it is one half of the pair.)
        assertThatThrownBy(() -> new ProrationAdjustment(ProrationAdjustment.Kind.CREDIT, "S", "BASIC",
            credit, new java.math.BigDecimal("0.5"),
            Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"),
            false, ProrationReason.SUBSCRIPTION_STARTED))
            .isInstanceOf(IllegalArgumentException.class);

        // Cancelling early only ever refunds, so a debit under it would overstate the refund.
        assertThatThrownBy(() -> new ProrationAdjustment(ProrationAdjustment.Kind.DEBIT, "S", "PRO",
            credit, new java.math.BigDecimal("0.5"),
            Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"),
            false, ProrationReason.CANCELLED_EARLY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only produces a credit");
    }

    @Test
    @DisplayName("the classification reaches the invoice line for downstream accounting")
    void classificationReachesTheLine() {
        var adjustments = prorationPair(ProrationReason.PLAN_CHANGED);

        var line = adjustments.get(0).toLineItem();

        assertThat(line.metadata())
            .containsEntry("prorationReason", "PLAN_CHANGED")
            .containsEntry("isProration", "true");
    }

    @Test
    @DisplayName("a non-proration operation is labelled as such on the line")
    void nonProrationIsLabelled() {
        // Built directly rather than through the calculator, which only produces credit/debit pairs.
        var line = new ProrationAdjustment(ProrationAdjustment.Kind.DEBIT, "S", "PRO",
            Money.of("20.00", CurrencyUnit.USD), new java.math.BigDecimal("1.0"),
            Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"),
            false, ProrationReason.SUBSCRIPTION_STARTED)
            .toLineItem();

        assertThat(line.metadata())
            .containsEntry("prorationReason", "SUBSCRIPTION_STARTED")
            .containsEntry("isProration", "false");
    }

    private static List<ProrationAdjustment> prorationPair(ProrationReason reason) {
        return ProrationCalculator.forPlanChange("S", "BASIC",
            Money.of("10.00", CurrencyUnit.USD), "PRO",
            Money.of("20.00", CurrencyUnit.USD),
            Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"),
            Instant.parse("2026-10-16T00:00:00Z"), CurrencyUnit.USD, reason);
    }
}