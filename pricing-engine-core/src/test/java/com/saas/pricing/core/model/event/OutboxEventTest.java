package com.saas.pricing.core.model.event;

import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transactional outbox and event signing.
 *
 * <p>The properties that matter: an event survives until delivered, a receiver can prove an event
 * is authentic, a replayed signature is distinguishable, and the retry budget terminates.
 */
class OutboxEventTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    private static final String SECRET = "whsec_test_secret";

    private static OutboxEvent event(String id) {
        return OutboxEvent.queued(id, "invoice.finalized", "t1", "INVOICE",
            "inv-1", "{\"invoiceId\":\"inv-1\"}", T0, T0);
    }

    @Nested
    @DisplayName("Signing")
    class Signing {

        @Test
        @DisplayName("a signature verifies for the same secret, timestamp and payload")
        void signatureVerifies() {
            String signature = EventSigner.sign(SECRET, T0, "{\"a\":1}");

            assertThat(EventSigner.verify(SECRET, T0, "{\"a\":1}", signature, Duration.ofMinutes(5)))
                .isTrue();
        }

        @Test
        @DisplayName("a tampered payload does not verify")
        void tamperedPayloadFails() {
            String signature = EventSigner.sign(SECRET, T0, "{\"a\":1}");

            assertThat(EventSigner.verify(SECRET, T0, "{\"a\":2}", signature, Duration.ofMinutes(5)))
                .as("a receiver must be able to reject a payload edited in flight")
                .isFalse();
        }

        @Test
        @DisplayName("a different secret does not verify")
        void wrongSecretFails() {
            String signature = EventSigner.sign(SECRET, T0, "{\"a\":1}");

            assertThat(EventSigner.verify("other-secret", T0, "{\"a\":1}", signature, Duration.ofMinutes(5)))
                .isFalse();
        }

        @Test
        @DisplayName("the timestamp is inside the signed material, so a replay is detectable")
        void timestampIsSigned() {
            String signature = EventSigner.sign(SECRET, T0, "{\"a\":1}");

            assertThat(EventSigner.verify(SECRET, T0.plusSeconds(60), "{\"a\":1}", signature, Duration.ofMinutes(5)))
                .as("reusing the signature with a different timestamp must not verify")
                .isFalse();
        }

        @Test
        @DisplayName("a missing signature is rejected rather than delivered")
        void missingSignatureRejected() {
            assertThat(EventSigner.verify(SECRET, T0, "{\"a\":1}", null, Duration.ofMinutes(5)))
                .isFalse();
            assertThat(EventSigner.verify(SECRET, T0, "{\"a\":1}", "", Duration.ofMinutes(5)))
                .isFalse();
        }

        @Test
        @DisplayName("a blank signing secret is refused")
        void blankSecretRefused() {
            assertThatThrownBy(() -> EventSigner.sign("  ", T0, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Retry ladder")
    class Retry {

        @Test
        @DisplayName("a fresh event is due immediately")
        void freshEventIsDue() {
            assertThat(event("e1").isDue(T0)).isTrue();
        }

        @Test
        @DisplayName("a failed event waits for its next attempt")
        void failedEventWaits() {
            var next = T0.plusSeconds(30);
            var failed = event("e1").failed("connection refused", next);

            assertThat(failed.attempts()).isEqualTo(1);
            assertThat(failed.isDue(T0)).isFalse();
            assertThat(failed.isDue(next)).isTrue();
        }

        @Test
        @DisplayName("a delivered event is never due again")
        void deliveredIsNotDue() {
            var delivered = event("e1").delivered(T0.plusSeconds(1));

            assertThat(delivered.isDelivered()).isTrue();
            assertThat(delivered.isDue(T0.plusSeconds(3600))).isFalse();
        }

        @Test
        @DisplayName("the retry budget terminates rather than retrying forever")
        void retryBudgetTerminates() {
            var event = event("e1");
            for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
                event = event.failed("still failing", T0.plusSeconds(60));
            }

            assertThat(event.attempts()).isEqualTo(OutboxEvent.MAX_ATTEMPTS);
            assertThat(event.isExhausted())
                .as("an event that can never be delivered must stop consuming attempts")
                .isTrue();
            assertThat(event.nextAttemptAt())
                .as("no further attempt is scheduled once exhausted")
                .isEmpty();
        }

        @Test
        @DisplayName("backoff is bounded so a dead subscriber is not hammered")
        void backoffIsBounded() {
            for (int attempts = 0; attempts < 30; attempts++) {
                assertThat(EventSigner.nextAttemptAt(attempts, T0))
                    .isAfterOrEqualTo(T0)
                    .isBefore(T0.plus(Duration.ofHours(1)));
            }
        }
    }

    @Nested
    @DisplayName("Repository")
    class Repository {

        @Test
        @DisplayName("an identical re-enqueue is a no-op, a conflicting one is refused")
        void enqueueSemantics() {
            var outbox = new InMemoryOutboxRepository();
            outbox.enqueue(event("e1"));

            outbox.enqueue(event("e1"));

            assertThat(outbox.findUndelivered(null, 10)).hasSize(1);

            var conflicting = OutboxEvent.queued("e1", "invoice.finalized", "t1", "INVOICE",
                "inv-1", "{\"different\":true}", T0, T0);
            assertThatThrownBy(() -> outbox.enqueue(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different content");
        }

        @Test
        @DisplayName("delivered events leave the due queue and remain in history")
        void deliveryClearsDueButKeepsHistory() {
            var outbox = new InMemoryOutboxRepository();
            outbox.enqueue(event("e1"));
            assertThat(outbox.findDue(T0, 10)).hasSize(1);

            outbox.recordDelivery(event("e1").delivered(T0.plusSeconds(1)));

            assertThat(outbox.findDue(T0.plusSeconds(60), 10)).isEmpty();
            assertThat(outbox.findByTenant(TENANT, 10))
                .as("an operator must still be able to see that it went out")
                .hasSize(1);
        }

        @Test
        @DisplayName("the due query honours its limit")
        void dueRespectsLimit() {
            var outbox = new InMemoryOutboxRepository();
            for (int i = 0; i < 10; i++) {
                outbox.enqueue(event("e-" + i));
            }

            assertThat(outbox.findDue(T0, 3)).hasSize(3);
            assertThat(outbox.findDue(T0, 100)).hasSize(10);
        }

        @Test
        @DisplayName("undelivered events can be found for operator follow-up")
        void undeliveredIsFindable() {
            var outbox = new InMemoryOutboxRepository();
            outbox.enqueue(event("e1"));
            outbox.enqueue(event("e2"));
            outbox.recordDelivery(event("e1").delivered(T0));

            assertThat(outbox.findUndelivered(null, 10))
                .extracting(OutboxEvent::eventId)
                .containsExactly("e2");
            assertThat(outbox.findUndelivered("invoice.finalized", 10)).hasSize(1);
            assertThat(outbox.findUndelivered("other.topic", 10)).isEmpty();
        }
    }
}