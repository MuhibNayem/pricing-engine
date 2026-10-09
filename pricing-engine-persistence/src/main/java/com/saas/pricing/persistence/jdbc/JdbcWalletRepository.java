package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.LedgerEntry;
import com.saas.pricing.core.model.wallet.LedgerEntryType;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.persistence.json.PricingJsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
        return queryWallet(sql, tenantId, customerId)
                .map(payload -> PricingJsonMapper.fromJson(payload, Wallet.class));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs in one transaction and takes a row-level write lock with {@code SELECT ... FOR UPDATE}
     * before reading the balance, so two concurrent drawdowns serialise: the second waits and then
     * reads the balance the first committed. A non-locking read followed by {@link #save} cannot
     * prevent a lost update, which lets a customer consume far more than they are charged for.
     */
    @Override
    @Transactional
    public Optional<Wallet> updateAtomically(TenantId tenantId, CustomerId customerId,
                                             UnaryOperator<Wallet> mutator) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(mutator, "mutator cannot be null");

        Optional<String> payload = queryWallet(
                "SELECT payload_json FROM wallets WHERE tenant_id = ? AND customer_id = ? FOR UPDATE",
                tenantId, customerId);
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        Wallet updated = mutator.apply(PricingJsonMapper.fromJson(payload.get(), Wallet.class));
        writeWallet(updated);
        return Optional.of(updated);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Balance update and ledger insert share one transaction, so a failure rolls back both and a
     * customer can never be left debited with no audit row.
     */
    @Override
    @Transactional
    public Optional<Wallet> updateAtomicallyAndRecord(TenantId tenantId, CustomerId customerId,
                                                       UnaryOperator<Wallet> mutator,
                                                       List<DrawdownTransaction> transactions) {
        Optional<Wallet> updated = updateAtomically(tenantId, customerId, mutator);
        if (updated.isPresent() && transactions != null && !transactions.isEmpty()) {
            recordTransactions(transactions);
        }
        return updated;
    }

    private static final String INSERT_LEDGER = """
            INSERT INTO wallet_ledger_entries (
                entry_id, wallet_id, entry_type, signed_credits, signed_money_amount,
                currency, calculation_id, reverses_entry_id, reason, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_LEDGER = """
            SELECT entry_id, wallet_id, entry_type, signed_credits, signed_money_amount,
                   currency, calculation_id, reverses_entry_id, reason, created_at
            FROM wallet_ledger_entries
            """;

    /**
     * {@inheritDoc}
     *
     * <p>Insert only. There is deliberately no update or delete path: a correction is a REVERSAL
     * row, and migration V5 rejects UPDATE and DELETE at the database level as defence in depth.
     *
     * <p>A re-appended identical entry is a no-op (a retry after a serialization failure), and a
     * reused id with different content is refused. The collision is absorbed with a savepoint
     * ({@link JdbcDuplicateGuard}) because catching the duplicate-key exception and reading on only
     * works on H2 - PostgreSQL aborts the transaction on the failed INSERT.
     */
    @Override
    @Transactional
    public void appendLedgerEntries(List<LedgerEntry> entries) {
        Objects.requireNonNull(entries, "entries cannot be null");
        for (LedgerEntry entry : entries) {
            boolean inserted = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate, () ->
                jdbcTemplate.update(INSERT_LEDGER,
                    entry.entryId(),
                    entry.walletId(),
                    entry.type().name(),
                    entry.signedCredits(),
                    entry.signedMoney().amount(),
                    entry.moneyValue().currency().code(),
                    entry.calculationId(),
                    entry.reversesEntryId().orElse(null),
                    entry.reason().orElse(null),
                    Timestamp.from(entry.createdAt())));

            if (inserted) {
                continue;
            }

            LedgerEntry existing = jdbcTemplate.query(SELECT_LEDGER + " WHERE entry_id = ?",
                    (rs, rowNum) -> readLedgerEntry(rs), entry.entryId())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Ledger entry " + entry.entryId()
                        + " vanished between the conflicting insert and the read"));

            if (!sameContent(existing, entry)) {
                throw new IllegalArgumentException(
                    "Ledger entry id " + entry.entryId()
                        + " already exists with different content; the ledger is append-only");
            }
            // Identical re-append: already recorded, nothing to do.
        }
    }

    /**
     * Business-content equality, deliberately ignoring {@code createdAt} sub-microsecond precision.
     *
     * <p>PostgreSQL stores {@code TIMESTAMP WITH TIME ZONE} at microsecond resolution while
     * {@link Instant#now()} carries nanoseconds, so an exact record equality would report a
     * perfectly identical retry as a conflict after the timestamp round-trip.
     */
    private static boolean sameContent(LedgerEntry existing, LedgerEntry candidate) {
        return existing.walletId().equals(candidate.walletId())
            && existing.type() == candidate.type()
            && existing.signedCredits().compareTo(candidate.signedCredits()) == 0
            && existing.signedMoney().compareTo(candidate.signedMoney()) == 0
            && existing.calculationId().equals(candidate.calculationId())
            && existing.reversesEntryId().equals(candidate.reversesEntryId())
            && existing.reason().equals(candidate.reason());
    }

    @Override
    public List<LedgerEntry> findLedgerEntries(String walletId) {
        Objects.requireNonNull(walletId, "walletId cannot be null");
        return jdbcTemplate.query(SELECT_LEDGER + " WHERE wallet_id = ? ORDER BY created_at, entry_id",
                (rs, rowNum) -> readLedgerEntry(rs), walletId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rejects reversing an entry twice: the derived balance would otherwise credit a customer
     * money that was never drawn.
     */
    @Override
    @Transactional
    public LedgerEntry reverseLedgerEntry(String entryId, String reason, Instant at) {
        Objects.requireNonNull(entryId, "entryId cannot be null");
        Objects.requireNonNull(at, "at cannot be null");

        Integer already = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger_entries WHERE entry_type = 'REVERSAL' AND reverses_entry_id = ?",
                Integer.class, entryId);
        if (already != null && already > 0) {
            throw new IllegalStateException("Ledger entry " + entryId + " has already been reversed");
        }

        LedgerEntry original = jdbcTemplate.query(SELECT_LEDGER + " WHERE entry_id = ?",
                (rs, rowNum) -> readLedgerEntry(rs), entryId)
            .stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown ledger entry: " + entryId));

        LedgerEntry reversal = LedgerEntry.reversalOf(original, reason, at);
        appendLedgerEntries(List.of(reversal));
        return reversal;
    }

    private static LedgerEntry readLedgerEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerEntry(
                rs.getString("entry_id"),
                rs.getString("wallet_id"),
                LedgerEntryType.valueOf(rs.getString("entry_type")),
                rs.getBigDecimal("signed_credits"),
                new Money(rs.getBigDecimal("signed_money_amount"),
                        CurrencyUnit.of(rs.getString("currency"))),
                rs.getString("calculation_id"),
                Optional.ofNullable(rs.getString("reverses_entry_id")),
                Optional.ofNullable(rs.getString("reason")),
                rs.getTimestamp("created_at").toInstant(),
                Map.of());
    }

    private Optional<String> queryWallet(String sql, TenantId tenantId, CustomerId customerId) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("payload_json"),
                tenantId.value(), customerId.value()).stream().findFirst();
    }

    @Override
    @Transactional
    public void save(Wallet wallet) {
        writeWallet(Objects.requireNonNull(wallet, "wallet cannot be null"));
    }

    private void writeWallet(Wallet wallet) {
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
