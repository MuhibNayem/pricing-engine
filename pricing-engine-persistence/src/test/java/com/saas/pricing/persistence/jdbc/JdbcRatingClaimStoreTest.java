package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.RatingClaimStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable per-window claim: what makes late events billable and retries free.
 */
class JdbcRatingClaimStoreTest extends BaseJdbcRepositoryTest {

    private static final TenantId TENANT = TenantId.of("t-claim");
    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    private static final String KEY = "c1::PRO::window";

    private JdbcRatingClaimStore store() {
        return new JdbcRatingClaimStore(jdbcTemplate);
    }

    private static RatingClaimStore.Charged charged(String amount, Instant at) {
        return new RatingClaimStore.Charged(new BigDecimal(amount), "USD", at);
    }

    @Test
    @DisplayName("a window has no claim until one is recorded")
    void emptyUntilRecorded() {
        var store = store();
        assertThat(store.find(TENANT, KEY)).isEmpty();

        store.record(TENANT, KEY, charged("10.00", T0));

        assertThat(store.find(TENANT, KEY)).contains(charged("10.00", T0));
    }

    @Test
    @DisplayName("compare-and-set succeeds once and fails on a stale expectation")
    void compareAndSetDetectsConcurrentWriters() {
        var store = store();
        var first = charged("10.00", T0);
        var second = charged("15.00", T0.plusSeconds(1));

        assertThat(store.compareAndSet(TENANT, KEY, Optional.empty(), first)).isTrue();
        assertThat(store.compareAndSet(TENANT, KEY, Optional.empty(), second))
            .as("another node already claimed the window from empty")
            .isFalse();
        assertThat(store.compareAndSet(TENANT, KEY, Optional.of(first), second)).isTrue();
        assertThat(store.find(TENANT, KEY)).contains(second);
    }

    @Test
    @DisplayName("record is an upsert, and remove only matches the exact claim")
    void upsertAndConditionalRemove() {
        var store = store();
        store.record(TENANT, KEY, charged("10.00", T0));
        store.record(TENANT, KEY, charged("12.50", T0.plusSeconds(1)));

        assertThat(store.find(TENANT, KEY)).contains(charged("12.50", T0.plusSeconds(1)));
        assertThat(store.remove(TENANT, KEY, charged("99.99", T0)))
            .as("a stale rollback must not remove a newer claim")
            .isFalse();
        assertThat(store.remove(TENANT, KEY, charged("12.50", T0.plusSeconds(1)))).isTrue();
        assertThat(store.find(TENANT, KEY)).isEmpty();
    }
}
