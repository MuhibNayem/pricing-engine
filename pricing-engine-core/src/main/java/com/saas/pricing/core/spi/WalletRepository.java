package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.LedgerEntry;
import com.saas.pricing.core.model.wallet.Wallet;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
     *
     * <p><strong>Warning:</strong> this is a blind last-write-wins overwrite. A read-modify-write
     * cycle built on {@link #findWallet} + {@link #save} loses updates under concurrency, which
     * for a prepaid-credit wallet means customers get more service than they paid for. Use
     * {@link #updateAtomically} for anything that moves money.
     */
    void save(Wallet wallet);

    /**
     * Atomically replaces the stored wallet with the result of applying {@code mutator} to the
     * wallet currently held, returning the updated wallet.
     *
     * <p>Implementations MUST serialise the read-modify-write for a given wallet so that two
     * concurrent draws cannot both read the same balance and both write it back. The in-memory
     * implementation does this with a per-key atomic map update; the JDBC implementation uses
     * {@code SELECT ... FOR UPDATE} inside a transaction.
     *
     * <p>The default implementation preserves source compatibility for third-party repositories
     * but is NOT concurrency-safe; implementations handling real money should override it.
     *
     * @return the updated wallet, or empty if no wallet exists for the tenant and customer
     */
    default Optional<Wallet> updateAtomically(TenantId tenantId, CustomerId customerId,
                                              UnaryOperator<Wallet> mutator) {
        Optional<Wallet> current = findWallet(tenantId, customerId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Wallet updated = mutator.apply(current.get());
        save(updated);
        return Optional.of(updated);
    }

    /**
     * Atomically replaces the stored wallet with the result of applying {@code mutator}, AND records
     * the resulting ledger entries in the same unit of work.
     *
     * <p>Splitting these into a wallet write followed by a separate ledger write means a crash
     * between them leaves money debited with no audit row explaining it - which is unreconcilable.
     * Implementations that can do so must commit both together.
     *
     * <p>The default implementation is NOT transactional; it simply performs the two calls in
     * order. Override this wherever the backend supports it.
     *
     * @return the updated wallet, or empty if no wallet exists for the tenant and customer
     */
    default Optional<Wallet> updateAtomicallyAndRecord(TenantId tenantId, CustomerId customerId,
                                                       UnaryOperator<Wallet> mutator,
                                                       List<DrawdownTransaction> transactions) {
        Optional<Wallet> updated = updateAtomically(tenantId, customerId, mutator);
        if (updated.isPresent() && transactions != null && !transactions.isEmpty()) {
            recordTransactions(transactions);
        }
        return updated;
    }

    /**
     * Records immutable drawdown ledger transactions.
     */
    void recordTransactions(List<DrawdownTransaction> transactions);

    /**
     * Appends entries to the wallet's ledger.
     *
     * <p><strong>Append-only.</strong> Implementations MUST NOT update or delete an existing entry.
     * A correction is a new {@link LedgerEntryType#REVERSAL} entry that negates the original, so the
     * history of what was charged, and when, survives any later correction.
     *
     * @throws IllegalArgumentException if an entry id is already present
     */
    default void appendLedgerEntries(List<LedgerEntry> entries) {
        throw new UnsupportedOperationException(
                "This WalletRepository does not support the append-only ledger");
    }

    /**
     * Returns the full ledger for a wallet, in insertion order.
     *
     * <p>The derived balance is {@link LedgerEntry#balanceOf(List)} of this list, which is what makes
     * a stored balance verifiable rather than merely readable.
     */
    default List<LedgerEntry> findLedgerEntries(String walletId) {
        throw new UnsupportedOperationException(
                "This WalletRepository does not support the append-only ledger");
    }

    /**
     * Reverses a previously recorded entry by appending its exact negation.
     *
     * <p>The original entry is left untouched. Reversing an already-reversed entry is rejected,
     * because double-reversing would credit a customer money that was never drawn.
     *
     * @return the newly appended reversal entry
     */
    default LedgerEntry reverseLedgerEntry(String entryId, String reason, java.time.Instant at) {
        throw new UnsupportedOperationException(
                "This WalletRepository does not support the append-only ledger");
    }

    /**
     * Verifies that the ledger replays to the wallet's currently stored balance.
     *
     * <p>Used as a reconciliation check. A mismatch means the stored balance and the entry stream
     * have diverged, which is exactly the condition an append-only ledger exists to detect.
     *
     * @return true when the derived balance equals the stored balance
     */
    default boolean reconcile(Wallet wallet, java.time.Instant at) {
        List<LedgerEntry> entries = findLedgerEntries(wallet.walletId());
        java.math.BigDecimal derived =
                entries.stream()
                        .filter(e -> e.createdAt().isBefore(at) || e.createdAt().equals(at))
                        .map(LedgerEntry::signedCredits)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return derived.compareTo(wallet.totalRemainingCredits(at)) == 0;
    }

    /**
     * Retrieves transactions for a wallet.
     */
    List<DrawdownTransaction> findTransactions(String walletId);
}
