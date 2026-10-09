package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.SequenceAllocator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-memory allocator must agree with {@code JdbcSequenceAllocator} on what identifies a
 * series. The JDBC implementation binds tenant and sequence key as two SQL parameters, so the
 * in-memory one must key structurally rather than by concatenating a separator — otherwise a
 * control character in either component can make two tenants share one counter, and two tenants
 * sharing one counter means the same invoice number issued twice.
 */
class InMemorySequenceAllocatorTest {

    private static final TenantId ACME = new TenantId("acme");
    private static final TenantId GLOBEX = new TenantId("globex");

    private final SequenceAllocator allocator = new InMemorySequenceAllocator();

    @Test
    @DisplayName("first allocation returns startAt, subsequent ones increment")
    void issuesInOrder() {
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(1);
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(2);
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(3);
    }

    @Test
    @DisplayName("a non-1 startAt seeds the counter rather than being added to")
    void honoursConfiguredStart() {
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 500)).isEqualTo(500);
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 500)).isEqualTo(501);
    }

    @Test
    @DisplayName("tenants draw from independent series")
    void scopesByTenant() {
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(1);
        assertThat(allocator.nextValue(GLOBEX, "ACCOUNT", 1)).isEqualTo(1);
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(2);
        assertThat(allocator.nextValue(GLOBEX, "ACCOUNT", 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("a control character in the tenant id cannot forge another tenant's series")
    void controlCharactersDoNotCollide() {
        // Under a NUL-separated key these two both encode to "acme\0CUSTOMER:99\0CUSTOMER:1":
        //   tenant "acme\0CUSTOMER:99" with key "CUSTOMER:1"
        //   tenant "acme"               with key "CUSTOMER:99\0CUSTOMER:1"
        // which handed the second tenant 2 — the first tenant's number. Neither TenantId nor the
        // sequence key forbids control characters, so the key has to be injective on its own.
        TenantId crafted = new TenantId("acme\0CUSTOMER:99");
        String craftedKey = "CUSTOMER:1";
        String genuineKey = "CUSTOMER:99\0CUSTOMER:1";

        assertThat(allocator.nextValue(crafted, craftedKey, 1)).isEqualTo(1);
        assertThat(allocator.nextValue(ACME, genuineKey, 1))
            .as("a tenant id crafted to imitate another tenant's key must not share its counter")
            .isEqualTo(1);
        assertThat(allocator.nextValue(crafted, craftedKey, 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("peek reports the last issued number without consuming anything")
    void peekDoesNotConsume() {
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(1);
        assertThat(allocator.peek(ACME, "ACCOUNT", 1))
            .as("peek reports the newest issued value; the caller adds 1 to get the next")
            .isEqualTo(1);
        assertThat(allocator.peek(ACME, "ACCOUNT", 1)).isEqualTo(1);
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("peek on an unseen series reports startAt - 1")
    void peekOnUnseenSeries() {
        assertThat(allocator.peek(ACME, "ACCOUNT", 10)).isEqualTo(9);
    }

    @Test
    @DisplayName("a released number is reissued")
    void releasedNumberIsReissued() {
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(1);
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(2);
        assertThat(allocator.restore(ACME, "ACCOUNT", 2)).isTrue();
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("restore refuses to rewind a counter that has moved on")
    void doesNotRewindPastIssuedNumbers() {
        allocator.nextValue(ACME, "ACCOUNT", 1);
        allocator.nextValue(ACME, "ACCOUNT", 1);
        allocator.nextValue(ACME, "ACCOUNT", 1);
        assertThat(allocator.restore(ACME, "ACCOUNT", 1)).isFalse();
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(4);
    }

    @Test
    @DisplayName("restore on an unseen series reports false rather than creating one")
    void restoreOnUnseenSeries() {
        assertThat(allocator.restore(ACME, "ACCOUNT", 1)).isFalse();
        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("a start below 1 is refused")
    void refusesInvalidStart() {
        assertThatThrownBy(() -> allocator.nextValue(ACME, "ACCOUNT", 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null tenant or key is refused")
    void refusesNulls() {
        assertThatThrownBy(() -> allocator.nextValue(null, "ACCOUNT", 1))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> allocator.nextValue(ACME, null, 1))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("resetForTesting returns the allocator to a freshly constructed state")
    void resetForTestingClearsSeries() {
        allocator.nextValue(ACME, "ACCOUNT", 1);
        allocator.nextValue(ACME, "ACCOUNT", 1);
        allocator.nextValue(GLOBEX, "ACCOUNT", 1);

        ((InMemorySequenceAllocator) allocator).resetForTesting();

        assertThat(allocator.nextValue(ACME, "ACCOUNT", 1)).isEqualTo(1);
        assertThat(allocator.nextValue(GLOBEX, "ACCOUNT", 1)).isEqualTo(1);
    }
}