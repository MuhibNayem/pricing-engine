package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production-ready JDBC / PostgreSQL persistence adapter for WalletRepository.
 */
public class JdbcWalletRepository implements WalletRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public Optional<Wallet> findWallet(TenantId tenantId, CustomerId customerId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");

        String sql = "SELECT payload_json FROM wallets WHERE tenant_id = ? AND customer_id = ?";
        List<String> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getString("payload_json"),
            tenantId.value(), customerId.value()
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(PricingJsonMapper.fromJson(results.getFirst(), Wallet.class));
    }

    @Override
    @Transactional
    public void save(Wallet wallet) {
        Objects.requireNonNull(wallet, "wallet cannot be null");

        String payload = PricingJsonMapper.toJson(wallet);
        String updateSql = "UPDATE wallets SET currency = ?, payload_json = ? WHERE tenant_id = ? AND customer_id = ?";
        int updated = jdbcTemplate.update(updateSql, wallet.currency().code(), payload, wallet.tenantId().value(), wallet.customerId().value());

        if (updated == 0) {
            String insertSql = "INSERT INTO wallets (wallet_id, tenant_id, customer_id, currency, payload_json) VALUES (?, ?, ?, ?, ?)";
            jdbcTemplate.update(insertSql, wallet.walletId(), wallet.tenantId().value(), wallet.customerId().value(), wallet.currency().code(), payload);
        }
    }

    @Override
    @Transactional
    public void recordTransactions(List<DrawdownTransaction> transactions) {
        Objects.requireNonNull(transactions, "transactions cannot be null");
        if (transactions.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO wallet_transactions (
                transaction_id, wallet_id, grant_id, grant_name, calculation_id,
                line_item_code, credits_drawn, money_amount, currency, remaining_credits, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.batchUpdate(
            sql,
            transactions,
            transactions.size(),
            (ps, tx) -> {
                ps.setString(1, tx.transactionId());
                ps.setString(2, tx.walletId());
                ps.setString(3, tx.grantId());
                ps.setString(4, tx.grantName());
                ps.setString(5, tx.calculationId());
                ps.setString(6, tx.lineItemCode().orElse(null));
                ps.setBigDecimal(7, tx.creditsDrawn());
                ps.setBigDecimal(8, tx.moneyAmountDrawn().amount());
                ps.setString(9, tx.moneyAmountDrawn().currency().code());
                ps.setBigDecimal(10, tx.remainingGrantCredits());
                ps.setTimestamp(11, Timestamp.from(tx.timestamp()));
            }
        );
    }

    @Override
    public List<DrawdownTransaction> findTransactions(String walletId) {
        Objects.requireNonNull(walletId, "walletId cannot be null");

        String sql = """
            SELECT transaction_id, wallet_id, grant_id, grant_name, calculation_id,
                   line_item_code, credits_drawn, money_amount, currency, remaining_credits, created_at
            FROM wallet_transactions
            WHERE wallet_id = ?
            ORDER BY created_at ASC
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new DrawdownTransaction(
            rs.getString("transaction_id"),
            rs.getString("wallet_id"),
            rs.getString("grant_id"),
            rs.getString("grant_name"),
            rs.getString("calculation_id"),
            Optional.ofNullable(rs.getString("line_item_code")),
            rs.getBigDecimal("credits_drawn"),
            Money.of(rs.getBigDecimal("money_amount"), com.saas.pricing.core.model.CurrencyUnit.of(rs.getString("currency"))),
            rs.getBigDecimal("remaining_credits"),
            rs.getTimestamp("created_at").toInstant()
        ), walletId);
    }
}
