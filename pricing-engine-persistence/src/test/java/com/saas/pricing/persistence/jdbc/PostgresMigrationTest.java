package com.saas.pricing.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the complete migration chain against a real PostgreSQL server.
 *
 * <p>The H2 fixture deliberately skips the {@code *_postgres_only} migrations, because H2 cannot
 * execute {@code CREATE RULE} or plpgsql. That exclusion is exactly where database-level
 * correctness hides; this test is where it is proven. It skips itself when Docker is unavailable
 * rather than pretending a pass.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pricing")
            .withUsername("pricing")
            .withPassword("pricing");

    static DataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @Test
    void everyMigrationAppliesSuccessfully() {
        Integer failed = jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE", Integer.class);
        assertThat(failed).isZero();
    }

    @Test
    void schemaContainsEveryDocumentedTable() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

        assertThat(tables).contains(
            "rate_cards", "contract_overrides", "wallets", "wallet_transactions",
            "wallet_ledger_entries", "meter_events", "meter_aggregations",
            "meter_idempotency_keys", "invoices", "invoice_line_items", "credit_notes",
            "payment_attempts", "entitlement_events", "entitlements",
            "subscriptions", "idempotency_keys", "outbox_events", "invoice_number_sequences");
    }

    @Test
    void ledgerSignAgreementConstraintRejectsZeroMoneyDrawdown() {
        Timestamp now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("""
            INSERT INTO wallets (wallet_id, tenant_id, customer_id, currency, payload_json)
            VALUES ('w-sign', 't-1', 'c-1', 'USD', '{}')
            """);

        // Negative credits with zero money is what a DRAWDOWN written by LedgerEntry.of used to look
        // like: negative credits, zero money. The constraint must reject it, because replaying the
        // two columns would otherwise produce two different answers.
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO wallet_ledger_entries (entry_id, wallet_id, entry_type, signed_credits,
                                               signed_money_amount, currency, calculation_id, created_at)
            VALUES ('led-bad', 'w-sign', 'DRAWDOWN', -10, 0, 'USD', 'calc-1', ?)
            """, now))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // The matched-sign entry a correct drawdown writes must be accepted.
        jdbc.update("""
            INSERT INTO wallet_ledger_entries (entry_id, wallet_id, entry_type, signed_credits,
                                               signed_money_amount, currency, calculation_id, created_at)
            VALUES ('led-good', 'w-sign', 'DRAWDOWN', -10, -10, 'USD', 'calc-1', ?)
            """, now);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM wallet_ledger_entries WHERE wallet_id = 'w-sign'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void flywayValidatesAppliedChecksums() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .validate();
    }
}
