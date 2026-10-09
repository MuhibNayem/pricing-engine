package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.LedgerEntry;
import com.saas.pricing.core.model.wallet.LedgerEntryType;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.spi.WalletRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Thread-safe in-memory wallet and ledger transaction repository.
 */
public class InMemoryWalletRepository implements WalletRepository, ResettableForTesting {

    private final Map<String, Wallet> walletStore = new ConcurrentHashMap<>();
    private final Map<String, List<DrawdownTransaction>> transactionStore = new ConcurrentHashMap<>();

    /**
     * Append-only ledger. Entries are never mutated or removed; a correction is a new reversal
     * entry. Insertion order is preserved so a replay is deterministic.
     */
    private final Map<String, List<LedgerEntry>> ledgerStore = new ConcurrentHashMap<>();

    private String key(TenantId tenantId, CustomerId customerId) {
        return tenantId.value() + "::" + customerId.value();
    }

    @Override
    public Optional<Wallet> findWallet(TenantId tenantId, CustomerId customerId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        return Optional.ofNullable(walletStore.get(key(tenantId, customerId)));
    }

    @Override
    public void save(Wallet wallet) {
        Objects.requireNonNull(wallet, "wallet cannot be null");
        walletStore.put(key(wallet.tenantId(), wallet.customerId()), wallet);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link ConcurrentHashMap#compute} holds the bin lock for the key across the whole
     * read-modify-write, so two concurrent drawsdowns of the same wallet are serialised and the
     * second observes the first's balance. Without this, N concurrent drawdowns of a $100 wallet
     * each read $100, each deduct, and only the last write survives - the customer consumes far
     * more than they are charged for.
     */
    @Override
    public Optional<Wallet> updateAtomically(TenantId tenantId, CustomerId customerId,
                                             UnaryOperator<Wallet> mutator) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(mutator, "mutator cannot be null");

        String walletKey = key(tenantId, customerId);
        AtomicReference<Wallet> updated = new AtomicReference<>();
        walletStore.compute(walletKey, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            Wallet next = mutator.apply(existing);
            updated.set(next);
            return next;
        });
        return Optional.ofNullable(updated.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The transaction append happens inside the same {@code compute} as the balance update, so a
     * caller can never observe (or leave behind) a debited wallet with no corresponding row. Doing
     * the append after {@link #updateAtomically} returned - as an earlier version did - left exactly
     * that window, despite the Javadoc claiming otherwise.
     */
    @Override
    public Optional<Wallet> updateAtomicallyAndRecord(TenantId tenantId, CustomerId customerId,
                                                       UnaryOperator<Wallet> mutator,
                                                       List<DrawdownTransaction> transactions) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(mutator, "mutator cannot be null");

        String walletKey = key(tenantId, customerId);
        AtomicReference<Wallet> updated = new AtomicReference<>();
        walletStore.compute(walletKey, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            Wallet next = mutator.apply(existing);
            if (transactions != null && !transactions.isEmpty()) {
                for (DrawdownTransaction tx : transactions) {
                    transactionStore.computeIfAbsent(tx.walletId(), x -> new CopyOnWriteArrayList<>()).add(tx);
                }
            }
            updated.set(next);
            return next;
        });
        return Optional.ofNullable(updated.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rejects a duplicate entry id rather than overwriting: silently replacing an entry would
     * make the ledger mutable by accident, which is the failure this design exists to prevent.
     *
     * <p>Entries are grouped by wallet. An earlier version keyed the whole batch on the first
     * entry's wallet, so a mixed batch appended every entry into one wallet's ledger.
     */
    @Override
    public void appendLedgerEntries(List<LedgerEntry> entries) {
        Objects.requireNonNull(entries, "entries cannot be null");
        if (entries.isEmpty()) {
            return;
        }
        Map<String, List<LedgerEntry>> byWallet = entries.stream()
            .collect(java.util.stream.Collectors.groupingBy(LedgerEntry::walletId,
                java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));

        for (Map.Entry<String, List<LedgerEntry>> walletBatch : byWallet.entrySet()) {
            List<LedgerEntry> ledger = ledgerStore.computeIfAbsent(
                walletBatch.getKey(), k -> new CopyOnWriteArrayList<>());
            synchronized (ledger) {
                for (LedgerEntry entry : walletBatch.getValue()) {
                    var existing = ledger.stream()
                            .filter(e -> e.entryId().equals(entry.entryId()))
                            .findFirst();
                    if (existing.isPresent()) {
                        // Re-appending an identical entry is what a retry after a serialization
                        // failure looks like, and must be a no-op rather than an error. Re-using an
                        // id for DIFFERENT content is a genuine conflict and is refused.
                        if (existing.get().equals(entry)) {
                            continue;
                        }
                        throw new IllegalArgumentException(
                                "Ledger entry id " + entry.entryId()
                                    + " already exists with different content; the ledger is append-only");
                    }
                    ledger.add(entry);
                }
            }
        }
    }

    @Override
    public List<LedgerEntry> findLedgerEntries(String walletId) {
        Objects.requireNonNull(walletId, "walletId cannot be null");
        List<LedgerEntry> ledger = ledgerStore.get(walletId);
        return ledger == null ? List.of() : List.copyOf(ledger);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rejects reversing an entry that has already been reversed, so a replay or a double click
     * cannot credit a customer money that was never drawn.
     */
    @Override
    public LedgerEntry reverseLedgerEntry(String entryId, String reason, Instant at) {
        Objects.requireNonNull(entryId, "entryId cannot be null");
        List<LedgerEntry> ledger = ledgerStore.values().stream()
                .filter(list -> list.stream().anyMatch(e -> e.entryId().equals(entryId)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown ledger entry: " + entryId));

        synchronized (ledger) {
            boolean alreadyReversed = ledger.stream()
                    .filter(e -> e.type() == LedgerEntryType.REVERSAL)
                    .anyMatch(e -> e.reversesEntryId().filter(id -> id.equals(entryId)).isPresent());
            if (alreadyReversed) {
                throw new IllegalStateException(
                        "Ledger entry " + entryId + " has already been reversed");
            }
            LedgerEntry original = ledger.stream()
                    .filter(e -> e.entryId().equals(entryId)).findFirst().orElseThrow();
            LedgerEntry reversal = LedgerEntry.reversalOf(original, reason, at);
            ledger.add(reversal);
            return reversal;
        }
    }

    @Override
    public void recordTransactions(List<DrawdownTransaction> transactions) {
        Objects.requireNonNull(transactions, "transactions cannot be null");
        for (DrawdownTransaction tx : transactions) {
            transactionStore.computeIfAbsent(tx.walletId(), k -> new CopyOnWriteArrayList<>()).add(tx);
        }
    }

    @Override
    public List<DrawdownTransaction> findTransactions(String walletId) {
        Objects.requireNonNull(walletId, "walletId cannot be null");
        List<DrawdownTransaction> txs = transactionStore.get(walletId);
        return txs != null ? new ArrayList<>(txs) : List.of();
    }

    @Override
    public void resetForTesting() {
        walletStore.clear();
        transactionStore.clear();
        ledgerStore.clear();
    }
}
