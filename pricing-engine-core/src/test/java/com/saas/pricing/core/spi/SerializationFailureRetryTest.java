package com.saas.pricing.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retry behaviour for serialization failures.
 *
 * <p>PostgreSQL aborts transactions that cannot be serialised (SQLSTATE 40001) rather than letting
 * them corrupt data. That is correct database behaviour, but it means the application has to retry,
 * and it equally means the retry must <em>not</em> swallow permanent failures.
 */
class SerializationFailureRetryTest {

    /** Stands in for a framework's retryable concurrency exception without depending on one. */
    static class FakeConcurrencyFailureException extends RuntimeException {
        FakeConcurrencyFailureException(String message) {
            super(message);
        }
    }

    /** A permanent failure that must never be retried. */
    static class FakeValidationException extends RuntimeException {
        FakeValidationException(String message) {
            super(message);
        }
    }

    @Test
    @DisplayName("succeeds without retrying when the first attempt works")
    void noRetryWhenFirstAttemptSucceeds() {
        var attempts = new AtomicInteger();

        String result = SerializationFailureRetry.execute("op", () -> {
            attempts.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("retries a serialization failure and returns the eventual success")
    void retriesTransientFailures() {
        var attempts = new AtomicInteger();

        String result = SerializationFailureRetry.execute("op", 4, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new FakeConcurrencyFailureException("could not serialize access due to concurrent update");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).as("two failures then success").hasValue(3);
    }

    @Test
    @DisplayName("gives up after the attempt budget and reports the cause")
    void exhaustsAttempts() {
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> SerializationFailureRetry.execute("wallet-drawdown", 3, () -> {
            attempts.incrementAndGet();
            throw new FakeConcurrencyFailureException("deadlock detected");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("wallet-drawdown")
            .hasMessageContaining("3 attempts")
            .hasRootCauseInstanceOf(FakeConcurrencyFailureException.class);

        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("does not retry a permanent failure")
    void doesNotRetryPermanentFailures() {
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> SerializationFailureRetry.execute("op", 5, () -> {
            attempts.incrementAndGet();
            throw new FakeValidationException("duplicate key value violates unique constraint");
        }))
            .isInstanceOf(FakeValidationException.class);

        assertThat(attempts)
            .as("a constraint violation is not transient; retrying only turns a fast failure into a slow one")
            .hasValue(1);
    }

    @Test
    @DisplayName("recognises retryable failures through a wrapped cause chain")
    void recognisesNestedFailures() {
        var wrapped = new IllegalStateException("drawdown failed",
            new RuntimeException("transaction aborted",
                new FakeConcurrencyFailureException("could not serialize access")));

        assertThat(SerializationFailureRetry.isSerializationFailure(wrapped)).isTrue();
    }

    @Test
    @DisplayName("does not treat an ordinary failure as retryable")
    void ordinaryFailuresNotRetried() {
        assertThat(SerializationFailureRetry.isSerializationFailure(new IllegalArgumentException("bad input")))
            .isFalse();
        assertThat(SerializationFailureRetry.isSerializationFailure(new FakeValidationException("nope")))
            .isFalse();
    }

    @Test
    @DisplayName("survives a self-referencing cause without looping forever")
    void selfReferencingCauseTerminates() {
        var failure = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(SerializationFailureRetry.isSerializationFailure(failure)).isFalse();
    }

    @Test
    @DisplayName("backoff grows but stays within the configured ceiling")
    void backoffIsBoundedAndJittered() {
        long first = SerializationFailureRetry.backoffMillis(1);
        long deep = SerializationFailureRetry.backoffMillis(20);

        assertThat(first).isBetween(0L, 20L);
        assertThat(deep).isBetween(0L, 500L);
    }

    @Test
    @DisplayName("rejects a nonsensical attempt budget")
    void rejectsBadAttemptBudget() {
        assertThatThrownBy(() -> SerializationFailureRetry.execute("op", 0, () -> "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}