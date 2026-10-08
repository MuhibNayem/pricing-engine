package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.spi.WalletRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory wallet and ledger transaction repository.
 */
public class InMemoryWalletRepository implements WalletRepository {

    private final Map<String, Wallet> walletStore = new ConcurrentHashMap<>();
    private final Map<String, List<DrawdownTransaction>> transactionStore = new ConcurrentHashMap<>();

    private String key(TenantId tenantId, CustomerId customerId) {
        return tenantId.value().toUpperCase() + "::" + customerId.value().toUpperCase();
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

    public void clear() {
        walletStore.clear();
        transactionStore.clear();
    }
}
