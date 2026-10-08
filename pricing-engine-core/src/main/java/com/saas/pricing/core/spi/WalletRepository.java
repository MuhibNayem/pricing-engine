package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.Wallet;

import java.util.List;
import java.util.Optional;

/**
 * SPI for persisting and retrieving customer wallets and recording drawdown transactions.
 */
public interface WalletRepository {

    /**
     * Finds the wallet for a given tenant and customer.
     */
    Optional<Wallet> findWallet(TenantId tenantId, CustomerId customerId);

    /**
     * Saves or updates a customer's wallet.
     */
    void save(Wallet wallet);

    /**
     * Records immutable drawdown ledger transactions.
     */
    void recordTransactions(List<DrawdownTransaction> transactions);

    /**
     * Retrieves transactions for a wallet.
     */
    List<DrawdownTransaction> findTransactions(String walletId);
}
