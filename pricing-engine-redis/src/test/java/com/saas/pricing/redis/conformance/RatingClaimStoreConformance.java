package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.RatingClaimStore;
import com.saas.pricing.metering.spi.RatingClaimStore.Charged;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behavioural contract every {@link RatingClaimStore} must satisfy.
 *
 * <p>{@code compareAndSet} is the reason this suite exists. It is the method that stops a rating
 * window being charged twice, and it is the one an implementation is most likely to get subtly
 * wrong — usually by comparing on amount alone, which succeeds when a concurrent re-charge happens
 * to reach the same total, and fails when two different claims coincidentally match.</p>
 */
@DisplayName("RatingClaimStore conformance")
abstract class RatingClaimStoreConformance {

    protected static final TenantId ACME = new TenantId("acme");
    protected static final TenantId GLOBEX = new TenantId("globex");
    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    protected static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

    protected abstract RatingClaimStore store();

    protected static Charged charged(String amount, Instant at) {
        return new Charged(new BigDecimal(amount), "USD", at);
    }

    @Test
    @DisplayName("an unclaimed window reports nothing")
    void unclaimedWindowIsEmpty() {
        assertThat(store().find(ACME, "w")).isEmpty();
    }

    @Test
    @DisplayName("record then find round-trips the amount")
    void recordThenFind() {
        RatingClaimStore store = store();
        store.record(ACME, "w", charged("10.00", T0));

        assertThat(store.find(ACME, "w")).isPresent();
        assertThat(store.find(ACME, "w").orElseThrow().amount()).isEqualByComparingTo("10.00");
        assertThat(store.find(ACME, "w").orElseThrow().currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("claims are scoped by tenant")
    void claimsAreTenantScoped() {
        RatingClaimStore store = store();
        store.record(ACME, "w", charged("10.00", T0));

        assertThat(store.find(GLOBEX, "w"))
            .as("one tenant's claim must not answer another's window")
            .isEmpty();
    }

    @Test
    @DisplayName("compareAndSet succeeds when expecting absence on an unclaimed window")
    void compareAndSetFromAbsent() {
        RatingClaimStore store = store();

        assertThat(store.compareAndSet(ACME, "w", Optional.empty(), charged("25.00", T0))).isTrue();
        assertThat(store.find(ACME, "w").orElseThrow().amount()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("compareAndSet fails when expecting absence on a claimed window")
    void compareAndSetFromAbsentOnClaimedWindowFails() {
        RatingClaimStore store = store();
        store.record(ACME, "w", charged("10.00", T0));

        assertThat(store.compareAndSet(ACME, "w", Optional.empty(), charged("25.00", T0)))
            .as("claiming an already-charged window would double-bill it")
            .isFalse();
        assertThat(store.find(ACME, "w").orElseThrow().amount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("compareAndSet succeeds on an exact match and fails on a stale one")
    void compareAndSetMatchesExactly() {
        RatingClaimStore store = store();
        store.record(ACME, "w", charged("10.00", T0));

        assertThat(store.compareAndSet(ACME, "w", Optional.of(charged("11.00", T0)), charged("99.00", T1)))
            .as("a different amount is not the claim the caller observed")
            .isFalse();

        assertThat(store.compareAndSet(ACME, "w", Optional.of(charged("10.00", T1)), charged("99.00", T1)))
            .as("same amount but a different chargedAt is a different claim")
            .isFalse();

        assertThat(store.compareAndSet(ACME, "w", Optional.of(charged("10.00", T0)), charged("20.00", T1)))
            .isTrue();
        assertThat(store.find(ACME, "w").orElseThrow().amount()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("remove is fenced on the expected claim")
    void removeIsFenced() {
        RatingClaimStore store = store();
        store.record(ACME, "w", charged("10.00", T0));

        assertThat(store.remove(ACME, "w", charged("11.00", T0))).isFalse();
        assertThat(store.find(ACME, "w")).isPresent();

        assertThat(store.remove(ACME, "w", charged("10.00", T0))).isTrue();
        assertThat(store.find(ACME, "w")).isEmpty();
    }

    @Test
    @DisplayName("a failed drawdown can hand its claim back so a retry may charge")
    void releaseAllowsRetry() {
        RatingClaimStore store = store();
        Charged claim = charged("10.00", T0);
        store.record(ACME, "w", claim);

        store.remove(ACME, "w", claim);

        assertThat(store.compareAndSet(ACME, "w", Optional.empty(), charged("10.00", T1)))
            .as("after releasing a failed drawdown the window must be chargeable again")
            .isTrue();
    }

    @Test
    @DisplayName("scale-insensitive amounts compare equal")
    void scaleInsensitiveAmountsCompareEqual() {
        RatingClaimStore store = store();
        // A claim read back through NUMERIC(24,8) arrives as 10.00000000. That must still match a
        // claim written as 10.00, or a drawdown would never find its own claim.
        store.record(ACME, "w", new Charged(new BigDecimal("10.00000000"), "USD", T0));

        assertThat(store.compareAndSet(ACME, "w", Optional.of(charged("10.00", T0)), charged("30.00", T1)))
            .isTrue();
        assertThat(store.find(ACME, "w").orElseThrow().amount()).isEqualByComparingTo("30.00");
    }
}