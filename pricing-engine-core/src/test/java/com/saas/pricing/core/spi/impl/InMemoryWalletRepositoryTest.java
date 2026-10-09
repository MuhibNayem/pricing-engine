package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.LedgerEntry;
import com.saas.pricing.core.model.wallet.LedgerEntryType;
import com.saas.pricing.core.model.wallet.Wallet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In-memory wallet/ledger semantics that the JDBC adapters mirror.
 */
class InMemoryWalletRepositoryTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private InMemoryWalletRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWalletRepository();
    }

    private static Wallet walletFor(String walletId, TenantId tenantId) {
        return Wallet.of(walletId, tenantId, CustomerId.of("c1"), USD, List.of());
    }

    private static DrawdownTransaction transaction(String txId, String walletId) {
        return new DrawdownTransaction(txId, walletId, "g1", "Prepaid",
            "calc-1", Optional.empty(), BigDecimal.TEN,
            Money.of("10.00", USD), new BigDecimal("90.00"), T0);
    }

    @Test
    @DisplayName("the balance and its transactions are written together")
    void balanceAndTransactionsWrittenTogether() {
        var tenant = TenantId.of("Acme");
        repository.save(walletFor("w1", tenant));

        Optional<Wallet> result = repository.updateAtomicallyAndRecord(
            tenant, CustomerId.of("c1"), w -> w, List.of(transaction("tx-1", "w1")));

        assertThat(result).isPresent();
        assertThat(repository.findTransactions("w1")).hasSize(1);
    }

    @Test
    @DisplayName("a mutator that fails leaves no transaction behind")
    void failedMutatorLeavesNoTransaction() {
        var tenant = TenantId.of("Acme");
        repository.save(walletFor("w1", tenant));

        assertThatThrownBy(() -> repository.updateAtomicallyAndRecord(
            tenant, CustomerId.of("c1"),
            w -> {
                throw new IllegalStateException("rating failed");
            },
            List.of(transaction("tx-1", "w1"))))
            .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findTransactions("w1"))
            .as("a debited wallet with no row, or a row for a wallet that was never debited, "
                + "both mean the balance and the ledger disagree")
            .isEmpty();
    }

    @Test
    @DisplayName("a batch of ledger entries spanning wallets is appended per wallet")
    void batchAcrossWalletsIsGrouped() {
        LedgerEntry first = LedgerEntry.of("led-1", "w1", LedgerEntryType.GRANT_ISSUED,
            new BigDecimal("10.00"), Money.of("10.00", USD), "calc-1", T0);
        LedgerEntry second = LedgerEntry.of("led-2", "w2", LedgerEntryType.GRANT_ISSUED,
            new BigDecimal("20.00"), Money.of("20.00", USD), "calc-2", T0);

        repository.appendLedgerEntries(List.of(first, second));

        assertThat(repository.findLedgerEntries("w1")).extracting(LedgerEntry::entryId)
            .containsExactly("led-1");
        assertThat(repository.findLedgerEntries("w2")).extracting(LedgerEntry::entryId)
            .containsExactly("led-2");
    }

    @Test
    @DisplayName("tenant identity is case-sensitive, like the JDBC tables")
    void tenantIdentityIsCaseSensitive() {
        repository.save(walletFor("w-acme", TenantId.of("Acme")));

        assertThat(repository.findWallet(TenantId.of("Acme"), CustomerId.of("c1"))).isPresent();
        assertThat(repository.findWallet(TenantId.of("ACME"), CustomerId.of("c1")))
            .as("'Acme' and 'ACME' are different tenants; sharing a wallet is a tenant-isolation bug")
            .isEmpty();
    }
}
