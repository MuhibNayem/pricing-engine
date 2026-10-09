package com.saas.pricing.persistence.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The duplicate-key recovery used by every append-only repository.
 *
 * <p>The property under test is not "the duplicate is detected" - it is that the enclosing
 * transaction survives detecting it. On PostgreSQL a failed INSERT aborts the transaction (25P02),
 * so the recovery read that follows would fail unless the failure was rolled back to a savepoint.
 */
class JdbcDuplicateGuardTest extends BaseJdbcRepositoryTest {

    private static final String INSERT_WALLET = """
        INSERT INTO wallets (wallet_id, tenant_id, customer_id, currency, payload_json)
        VALUES (?, 't-guard', ?, 'USD', '{}')
        """;

    @Test
    @DisplayName("a duplicate insert inside a transaction does not poison the transaction")
    void duplicateInsideTransactionKeepsItUsable() {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        Boolean secondInserted = transactions.execute(status -> {
            assertThat(JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate,
                () -> jdbcTemplate.update(INSERT_WALLET, "w-dup", "c-dup"))).isTrue();

            boolean duplicate = JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate,
                () -> jdbcTemplate.update(INSERT_WALLET, "w-dup", "c-dup"));

            // If the savepoint rollback did not restore the transaction, this statement fails on
            // PostgreSQL with "current transaction is aborted" - which H2 would never reveal.
            jdbcTemplate.update(INSERT_WALLET, "w-after", "c-after");
            return duplicate;
        });

        assertThat(secondInserted).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM wallets WHERE wallet_id = 'w-after'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate insert outside a transaction is reported, not raised")
    void duplicateOutsideTransactionIsReported() {
        assertThat(JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate,
            () -> jdbcTemplate.update(INSERT_WALLET, "w-plain", "c-plain"))).isTrue();
        assertThat(JdbcDuplicateGuard.insertOrIgnore(jdbcTemplate,
            () -> jdbcTemplate.update(INSERT_WALLET, "w-plain", "c-plain"))).isFalse();
    }
}
