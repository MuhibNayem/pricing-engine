package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWalletRepositoryTest extends BaseJdbcRepositoryTest {

    private JdbcWalletRepository repository;
    private final TenantId tenantId = TenantId.of("tenant_wallet");
    private final CustomerId customerId = CustomerId.of("cust_wallet");

    @BeforeEach
    void setUp() {
        repository = new JdbcWalletRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Should save, retrieve, and record transactions on customer wallet")
    void testWalletAndTransactions() {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        CreditGrant grant1 = CreditGrant.prepaid(
            "grant_1", "wal_100", "Purchased Credits",
            new BigDecimal("500.00"), BigDecimal.ONE, now
        );
        CreditGrant grant2 = CreditGrant.promotional(
            "grant_2", "wal_100", "Promo Q4",
            new BigDecimal("100.00"), BigDecimal.ONE, now, now.plusSeconds(86400 * 30)
        );

        Wallet wallet = Wallet.of("wal_100", tenantId, customerId, CurrencyUnit.USD, List.of(grant1, grant2));
        repository.save(wallet);

        // Retrieve
        Optional<Wallet> found = repository.findWallet(tenantId, customerId);
        assertThat(found).isPresent();
        assertThat(found.get().walletId()).isEqualTo("wal_100");
        assertThat(found.get().grants()).hasSize(2);
        assertThat(found.get().totalRemainingCredits(now)).isEqualByComparingTo("600.00");

        // Record drawdown transactions
        DrawdownTransaction tx = new DrawdownTransaction(
            "tx_1",
            "wal_100",
            "grant_1",
            "Purchased Credits",
            "calc_999",
            Optional.of("API_CALLS"),
            new BigDecimal("50.00"),
            Money.of("50.00", CurrencyUnit.USD),
            new BigDecimal("450.00"),
            now.plusSeconds(3600)
        );

        repository.recordTransactions(List.of(tx));

        List<DrawdownTransaction> transactions = repository.findTransactions("wal_100");
        assertThat(transactions).hasSize(1);
        assertThat(transactions.getFirst().creditsDrawn()).isEqualByComparingTo("50.00");
        assertThat(transactions.getFirst().calculationId()).isEqualTo("calc_999");
    }
}
