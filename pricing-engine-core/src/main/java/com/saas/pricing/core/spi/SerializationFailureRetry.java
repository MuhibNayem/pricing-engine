package com.saas.pricing.core.spi;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Bounded retry for transient database failures.
 *
 * <p><strong>Why this exists.</strong> PostgreSQL's default isolation level is Serializable
 * Snapshot Isolation. Under SERIALIZABLE, a transaction that would produce a non-serializable
 * result is <em>aborted</em> (SQLSTATE {@code 40001}, serialization failure) rather than allowed to
 * corrupt data. That is the correct database behaviour, but it means application code must retry:
 * without this, two concurrent wallet drawdowns occasionally surface a spurious error to a
 * customer instead of both succeeding.
 *
 * <p><strong>Where it must be applied.</strong> The retry has to sit <em>outside</em> the
 * transaction, so each attempt runs in a fresh one. Retrying inside an already-aborted transaction
 * cannot succeed, because the transaction is dead. Call it from the service layer, which invokes
 * the transactional repository method through its proxy, rather than from inside the repository's
 * own {@code @Transactional} method.
 *
 * <p><strong>What must not be retried.</strong> Permanent failures - constraint violations,
 * validation errors, authentication failures - are rethrown immediately. Blindly retrying a
 * duplicate-key error would turn a fast, correct failure into a slow one.
 *
 * <p>Backoff is exponential with full jitter, so that a burst of contending transactions does not
 * resynchronise into a second thundering herd on the same schedule.
 */
public final class SerializationFailureRetry {

    /** Default number of attempts, including the first. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** Base backoff in milliseconds; attempt {@code n} waits ~base * 2^n, jittered. */
    private static final long BASE_BACKOFF_MILLIS = 20L;

    private static final long MAX_BACKOFF_MILLIS = 500L;

    private SerializationFailureRetry() {
        // static utility
    }

    /**
     * Executes {@code action}, retrying transient serialization failures with exponential backoff
     * and jitter.
     *
     * @param operation a short description used in the thrown exception
     * @param action    the unit of work; MUST be idempotent, since it may run more than once
     * @throws RuntimeException the last failure, wrapped with the attempt count once attempts run out
     */
    public static <T> T execute(String operation, Supplier<T> action) {
        return execute(operation, DEFAULT_MAX_ATTEMPTS, action);
    }

    /**
     * @param maxAttempts total attempts including the first; must be at least 1
     */
    public static <T> T execute(String operation, int maxAttempts, Supplier<T> action) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException failure) {
                if (!isSerializationFailure(failure)) {
                    throw failure;
                }
                lastFailure = failure;
                if (attempt < maxAttempts) {
                    sleep(backoffMillis(attempt));
                }
            }
        }
        throw new IllegalStateException(
                "Operation '%s' failed after %d attempts due to repeated serialization failures"
                    .formatted(operation, maxAttempts), lastFailure);
    }

    /**
     * Recognises a serialization failure without taking a compile-time dependency on any
     * persistence framework.
     *
     * <p>Spring translates SQLSTATE 40001 (serialization failure) and 40P01 (deadlock detected) to
     * {@code ConcurrencyFailureException}; other frameworks use different names. Matching on the
     * simple type name keeps {@code core} free of Spring - the module is deliberately pure Java -
     * while still recognising the retryable class of failure. Deadlocks are included because they
     * are the same problem with the same remedy.
     */
    public static boolean isSerializationFailure(Throwable failure) {
        for (Throwable t = failure; t != null; ) {
            if (isRetryableType(t.getClass().getName())) {
                return true;
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return false;
    }

    private static boolean isRetryableType(String className) {
        if (className.endsWith("ConcurrencyFailureException")
                || className.endsWith("DeadlockLoserDataAccessException")
                || className.endsWith("PessimisticLockingFailureException")
                || className.endsWith("CannotAcquireLockException")
                || className.endsWith("SerializationFailure")
                || className.endsWith("OptimisticLockingFailure")) {
            return true;
        }
        // Spring's TransientDataAccessException subclasses that are retryable by contract.
        return className.endsWith("TransientDataAccessException")
                && (className.contains("Concurrency") || className.contains("Deadlock")
                    || className.contains("CannotSerialize") || className.contains("Lock"));
    }

    /** Exposed for testing: deterministic upper bound on the wait before attempt {@code n+1}. */
    static long backoffMillis(int attempt) {
        long exponential = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        long capped = Math.min(exponential, MAX_BACKOFF_MILLIS);
        // Full jitter: a uniform draw in [0, capped]. Without jitter, transactions aborted at the
        // same instant retry at the same instant and collide again.
        return capped == 0 ? 0 : ThreadLocalRandom.current().nextLong(capped + 1);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off; aborting retry", e);
        }
    }
}