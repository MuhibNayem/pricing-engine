package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.PaymentAttempt;
import com.saas.pricing.core.spi.CollectionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory append-only collection ledger.
 *
 * <p>Mirrors the JDBC adapter's duplicate semantics: an identical re-delivery is a no-op returning
 * {@code false}, while the same id with different content is refused.
 */
public class InMemoryCollectionRepository implements CollectionRepository, ResettableForTesting {

    private final Map<String, List<PaymentAttempt>> attempts = new ConcurrentHashMap<>();

    private static String key(TenantId tenantId, String invoiceId) {
        return tenantId.value() + "::" + invoiceId;
    }

    @Override
    public boolean record(PaymentAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt cannot be null");
        // Invoice ids are unique per tenant, so keying on the invoice alone is sufficient here;
        // the tenant is carried for parity with the JDBC key.
        List<PaymentAttempt> ledger = attempts.computeIfAbsent(attempt.invoiceId(), k -> new ArrayList<>());
        synchronized (ledger) {
            var existing = ledger.stream()
                .filter(a -> a.attemptId().equals(attempt.attemptId()))
                .findFirst();
            if (existing.isPresent()) {
                if (existing.get().equals(attempt)) {
                    return false;
                }
                throw new IllegalArgumentException(
                    "Attempt id " + attempt.attemptId() + " exists with different content; the ledger is append-only");
            }
            ledger.add(attempt);
            ledger.sort(Comparator.comparingInt(PaymentAttempt::attemptNumber));
            return true;
        }
    }

    @Override
    public List<PaymentAttempt> findAttempts(TenantId tenantId, String invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        List<PaymentAttempt> ledger = attempts.get(invoiceId);
        if (ledger == null) {
            return List.of();
        }
        synchronized (ledger) {
            return List.copyOf(ledger);
        }
    }

    @Override
    public Optional<PaymentAttempt> findLatest(TenantId tenantId, String invoiceId) {
        List<PaymentAttempt> ledger = findAttempts(tenantId, invoiceId);
        return ledger.isEmpty() ? Optional.empty() : Optional.of(ledger.getLast());
    }

    @Override
    public List<PaymentAttempt> findDue(Instant at) {
        Objects.requireNonNull(at, "at cannot be null");
        List<PaymentAttempt> due = new ArrayList<>();
        attempts.values().forEach(ledger -> {
            synchronized (ledger) {
                ledger.stream()
                    .filter(a -> a.nextAttemptAt().isPresent())
                    .filter(a -> !a.nextAttemptAt().orElseThrow().isAfter(at))
                    .forEach(due::add);
            }
        });
        due.sort(Comparator.comparingInt(PaymentAttempt::attemptNumber));
        return due;
    }

    @Override
    public void resetForTesting() {
        attempts.clear();
    }
}