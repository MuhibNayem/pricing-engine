package com.saas.pricing.metering.stream;

import com.saas.pricing.core.engine.DefaultPricingEngine;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.impl.InMemoryCurrencyExchangeProvider;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryWalletRepository;
import com.saas.pricing.core.spi.impl.RuleBasedTaxProvider;
import com.saas.pricing.metering.engine.DefaultUsageMeteringEngine;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Late events must be billed, and only once.
 *
 * <p>The previous rating idempotency key was the window itself: after a window had been charged,
 * a late event was accepted by ingestion but every later rating of the window returned the cached
 * completion, so the extra usage was never charged. The durable rating claim turns the second
 * rating into a delta charge, and a third rating into no charge at all.</p>
 */
class RatingDeltaBillingTest {

    private static final TenantId TENANT = TenantId.of("t-delta");
    private static final CustomerId CUSTOMER = CustomerId.of("c-delta");
    private static final PlanCode PLAN = PlanCode.of("USAGE");
    private static final Instant BASE = Instant.parse("2026-10-08T10:00:00Z");
    private static final TimeWindow WINDOW = TimeWindow.of(BASE, BASE.plus(Duration.ofHours(1)));

    private DefaultUsageMeteringEngine meteringEngine;
    private InMemoryWalletRepository wallets;
    private AsyncRatingTriggerService service;

    @BeforeEach
    void setUp() {
        var rateCards = new InMemoryRateCardRepository();
        rateCards.save(RateCard.of(
            "rc-delta", TENANT, PLAN, 1, BASE.minusSeconds(86_400),
            List.of(RatePlanItem.of("BYTES_SENT", "bytes",
                PricingModel.PerUnitModel.of(BigDecimal.ONE), CurrencyUnit.USD))));

        var pricingEngine = new DefaultPricingEngine(
            rateCards,
            new InMemoryCurrencyExchangeProvider(),
            new RuleBasedTaxProvider(),
            AuditSink.noOp(),
            null);

        meteringEngine = new DefaultUsageMeteringEngine(); // SUM, no lateness filter
        wallets = new InMemoryWalletRepository();
        wallets.save(Wallet.of("w-delta", TENANT, CUSTOMER, CurrencyUnit.USD, List.of(
            CreditGrant.prepaid("g-delta", "w-delta", "Prepaid",
                new BigDecimal("100.00"), BigDecimal.ONE, BASE))));

        service = new AsyncRatingTriggerService(meteringEngine, pricingEngine, wallets,
            new WalletDrawdownEngine());
    }

    private MeterEvent event(String eventId, String key, String value, Instant at) {
        return MeterEvent.builder()
            .eventId(eventId)
            .idempotencyKey(key)
            .tenantId(TENANT)
            .customerId(CUSTOMER)
            .meterCode("BYTES_SENT")
            .value(new BigDecimal(value))
            .timestamp(at)
            .build();
    }

    private BigDecimal remainingCredits() {
        return wallets.findWallet(TENANT, CUSTOMER).orElseThrow()
            .totalRemainingCredits(BASE.plusSeconds(3_600));
    }

    @Test
    @DisplayName("a late event charges only the difference and a retry charges nothing")
    void lateEventIsDeltaBilledExactlyOnce() {
        // First delivery: 10 units at $1 each.
        var first = service.ingestRateAndDrawdownAsync(
            event("e1", "k1", "10", BASE.plusSeconds(10)), PLAN, WINDOW, CurrencyUnit.USD).join();

        assertThat(first).isPresent();
        assertThat(first.get().totalCreditsDrawn()).isEqualByComparingTo("10");
        assertThat(remainingCredits()).isEqualByComparingTo("90");

        // A late event inside the same window: 15 units in total now.
        var second = service.ingestRateAndDrawdownAsync(
            event("e2", "k2", "5", BASE.plusSeconds(20)), PLAN, WINDOW, CurrencyUnit.USD).join();

        assertThat(second).isPresent();
        assertThat(second.get().totalCreditsDrawn())
            .as("only the extra 5 units are charged, not the whole window again")
            .isEqualByComparingTo("5");
        assertThat(remainingCredits()).isEqualByComparingTo("85");

        // Re-rating the same window without new usage must not charge again.
        var third = service.aggregateRateAndDrawdownAsync(TENANT, CUSTOMER, PLAN, WINDOW, CurrencyUnit.USD).join();

        assertThat(third.totalCreditsDrawn()).isEqualByComparingTo("0");
        assertThat(remainingCredits()).isEqualByComparingTo("85");
    }

    @Test
    @DisplayName("a retried delivery of the same event is a duplicate and charges nothing")
    void duplicateEventChargesNothing() {
        var first = service.ingestRateAndDrawdownAsync(
            event("e1", "k1", "10", BASE.plusSeconds(10)), PLAN, WINDOW, CurrencyUnit.USD).join();
        assertThat(first).isPresent();
        assertThat(remainingCredits()).isEqualByComparingTo("90");

        // Same idempotency key, same content: accepted as a duplicate, aggregation unchanged, delta
        // zero. Note ingestion returns empty Optional for a duplicate, so no second drawdown runs.
        var replay = service.ingestRateAndDrawdownAsync(
            event("e1", "k1", "10", BASE.plusSeconds(10)), PLAN, WINDOW, CurrencyUnit.USD).join();

        assertThat(replay).isEmpty();
        assertThat(remainingCredits()).isEqualByComparingTo("90");
    }
}
