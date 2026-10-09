package com.saas.pricing.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
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
 * Runs the complete migration chain and every PostgreSQL-only guard against a real server.
 *
 * <p>The H2 fixture deliberately skips the {@code *_postgres_only} migrations, because H2 cannot
 * execute plpgsql. That exclusion is exactly where database-level correctness hides; this test is
 * where it is proven. It skips itself when Docker is unavailable rather than pretending a pass.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    private static final Timestamp T0 = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
    private static final Timestamp T1 = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));

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
    void flywayValidatesAppliedChecksums() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .validate();
    }

    // ------------------------------------------------------------------
    // Wallet ledger
    // ------------------------------------------------------------------

    @Test
    void ledgerSignAgreementConstraintRejectsZeroMoneyDrawdown() {
        insertWallet("w-sign");

        assertThatThrownBy(() -> insertLedger("led-bad", "w-sign", "DRAWDOWN", null, -10, 0))
            .isInstanceOf(DataAccessException.class);

        insertLedger("led-good", "w-sign", "DRAWDOWN", null, -10, -10);

        assertThat(countWhere("wallet_ledger_entries", "wallet_id = 'w-sign'")).isEqualTo(1);
    }

    @Test
    void firstReversalIsAcceptedAndASecondIsRejected() {
        insertWallet("w-rev");
        insertLedger("orig-rev", "w-rev", "GRANT_ISSUED", null, 10, 10);

        // Regression: the single-reversal trigger is AFTER INSERT and used to match its own row,
        // so this first, legitimate reversal was rejected on every PostgreSQL deployment.
        insertLedger("rev-1", "w-rev", "REVERSAL", "orig-rev", -10, -10);

        assertThatThrownBy(() -> insertLedger("rev-2", "w-rev", "REVERSAL", "orig-rev", -10, -10))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("already been reversed");

        assertThat(countWhere("wallet_ledger_entries", "wallet_id = 'w-rev'")).isEqualTo(2);
    }

    @Test
    void ledgerUpdateAndDeleteAreRejectedNotSilentlyIgnored() {
        insertWallet("w-mut");
        insertLedger("orig-mut", "w-mut", "GRANT_ISSUED", null, 5, 5);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE wallet_ledger_entries SET reason = 'tampered' WHERE entry_id = 'orig-mut'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
            "DELETE FROM wallet_ledger_entries WHERE entry_id = 'orig-mut'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");

        assertThat(countWhere("wallet_ledger_entries", "entry_id = 'orig-mut'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Entitlement events
    // ------------------------------------------------------------------

    @Test
    void firstRevocationIsAcceptedAndASecondIsRejected() {
        insertEntitlementEvent("ent-grant", "GRANTED", "");
        insertEntitlementEvent("ent-revoke-1", "REVOKED", "customer churned");

        assertThatThrownBy(() ->
            insertEntitlementEvent("ent-revoke-2", "REVOKED", "duplicate delivery"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("already been revoked");

        assertThat(countWhere("entitlement_events", "feature_key = 'feature-x'")).isEqualTo(2);
    }

    @Test
    void entitlementEventUpdateAndDeleteAreRejected() {
        insertEntitlementEvent("ent-immutable", "GRANTED", "");

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE entitlement_events SET reason = 'rewritten' WHERE event_id = 'ent-immutable'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
            "DELETE FROM entitlement_events WHERE event_id = 'ent-immutable'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------
    // Outbox
    // ------------------------------------------------------------------

    @Test
    void outboxDeleteIsRejectedAndPayloadIsImmutableButBookkeepingMayChange() {
        jdbc.update("""
            INSERT INTO outbox_events (event_id, topic, tenant_id, aggregate_type, aggregate_id,
                                       payload, occurred_at)
            VALUES ('evt-1', 'invoice.created', 't-outbox', 'invoice', 'inv-1', '{"a":1}', ?)
            """, T0);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM outbox_events WHERE event_id = 'evt-1'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE outbox_events SET payload = '{\"b\":2}' WHERE event_id = 'evt-1'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("immutable");

        jdbc.update("""
            UPDATE outbox_events SET attempts = 1, delivered_at = ?, next_attempt_at = NULL
            WHERE event_id = 'evt-1'
            """, T1);

        assertThat(jdbc.queryForObject(
            "SELECT attempts FROM outbox_events WHERE event_id = 'evt-1'", Integer.class))
            .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Payment attempts
    // ------------------------------------------------------------------

    @Test
    void pendingAttemptIsAcceptedAndAttemptMutationsAreRejected() {
        insertOpenInvoice("inv-attempt");

        // Regression: V10's status CHECK omitted PENDING, the state every delayed-notification
        // charge starts in, and demanded a failure code for it.
        jdbc.update("""
            INSERT INTO payment_attempts (attempt_id, invoice_id, attempt_number, amount, currency,
                                          status, attempted_at)
            VALUES ('inv-attempt-1', 'inv-attempt', 1, 100, 'USD', 'PENDING', ?)
            """, T0);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE payment_attempts SET status = 'SUCCEEDED' WHERE attempt_id = 'inv-attempt-1'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
            "DELETE FROM payment_attempts WHERE attempt_id = 'inv-attempt-1'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------
    // Credit notes
    // ------------------------------------------------------------------

    @Test
    void fullCreditNoteIsAcceptedAndOverCreditIsRejected() {
        insertOpenInvoice("inv-credit");

        // Regression: the cap trigger counted the new row twice, so a full-value credit projected
        // 2x the invoice and was rejected - the cap was effectively half the invoice.
        insertCreditNote("cn-full", "inv-credit", -100);

        assertThatThrownBy(() -> insertCreditNote("cn-over", "inv-credit", -1))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("above the invoice total");
    }

    @Test
    void voidingACreditNoteReleasesTheCap() {
        insertOpenInvoice("inv-void");

        insertCreditNote("cn-void", "inv-void", -100);

        jdbc.update("UPDATE credit_notes SET status = 'VOID', voided_at = ? WHERE credit_note_id = 'cn-void'",
            T1);

        // The voided note frees the cap, and the status-change trigger sees VOID and returns early.
        insertCreditNote("cn-after-void", "inv-void", -50);

        assertThat(countWhere("credit_notes", "invoice_id = 'inv-void'")).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Subscriptions (V15)
    // ------------------------------------------------------------------

    @Test
    void subscriptionVersionMustAdvanceByExactlyOneAndTerminalStatesCannotResurrect() {
        jdbc.update("""
            INSERT INTO subscriptions (subscription_id, tenant_id, customer_id, plan_code, status,
                                       created_at, current_period_start, current_period_end,
                                       cancel_at_period_end, version, payload_json)
            VALUES ('sub-guard', 'tenant-pg', 'customer-pg', 'PLAN', 'ACTIVE', ?, ?, ?, FALSE, 0, '{}')
            """, T0, T0, T1);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE subscriptions SET version = 0 WHERE subscription_id = 'sub-guard'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("advance by exactly one");

        jdbc.update(
            "UPDATE subscriptions SET status = 'CANCELED', canceled_at = ?, version = 1 "
                + "WHERE subscription_id = 'sub-guard'", T1);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE subscriptions SET status = 'ACTIVE', version = 2 WHERE subscription_id = 'sub-guard'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("terminal");
    }

    // ------------------------------------------------------------------
    // Idempotency keys (V17)
    // ------------------------------------------------------------------

    @Test
    void aLiveIdempotencyClaimCannotBeRewrittenOrStolen() {
        Timestamp liveUntil = Timestamp.from(Instant.now().plusSeconds(3_600));
        jdbc.update("""
            INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, response_status,
                                          response_body, recorded_at, expires_at)
            VALUES ('t-idem', 'k-live', 'fp1', 'IN_FLIGHT', 0, '', ?, ?)
            """, T0, liveUntil);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE idempotency_keys SET fingerprint = 'fp2' WHERE tenant_id = 't-idem' AND idem_key = 'k-live'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("may only change");

        jdbc.update("""
            UPDATE idempotency_keys SET status = 'COMPLETED', response_status = 201, response_body = '{}'
            WHERE tenant_id = 't-idem' AND idem_key = 'k-live'
            """);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE idempotency_keys SET status = 'IN_FLIGHT' WHERE tenant_id = 't-idem' AND idem_key = 'k-live'"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("cannot be reclaimed");
    }

    @Test
    void anExpiredClaimIsReclaimable() {
        Timestamp expiredAt = Timestamp.from(Instant.now().minusSeconds(3_600));
        Timestamp recordedAt = Timestamp.from(Instant.now().minusSeconds(7_200));
        jdbc.update("""
            INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, response_status,
                                          response_body, recorded_at, expires_at)
            VALUES ('t-idem', 'k-expired', 'fp1', 'COMPLETED', 201, '{}', ?, ?)
            """, recordedAt, expiredAt);

        jdbc.update("""
            UPDATE idempotency_keys
            SET fingerprint = 'fp2', status = 'IN_FLIGHT', response_status = 0, response_body = '',
                recorded_at = ?, expires_at = ?
            WHERE tenant_id = 't-idem' AND idem_key = 'k-expired'
            """, T0, Timestamp.from(Instant.now().plusSeconds(3_600)));

        assertThat(jdbc.queryForObject(
            "SELECT fingerprint FROM idempotency_keys WHERE tenant_id = 't-idem' AND idem_key = 'k-expired'",
            String.class)).isEqualTo("fp2");
    }

    @Test
    void aClaimThatExpiresBeforeItIsRecordedIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, response_status,
                                          response_body, recorded_at, expires_at)
            VALUES ('t-idem', 'k-backwards', 'fp', 'IN_FLIGHT', 0, '', ?, ?)
            """, T1, T0))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("expires_at must be after recorded_at");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void insertWallet(String walletId) {
        jdbc.update("""
            INSERT INTO wallets (wallet_id, tenant_id, customer_id, currency, payload_json)
            VALUES (?, 'tenant-pg', ?, 'USD', '{}')
            """, walletId, "customer-" + walletId);
    }

    private static void insertLedger(String entryId, String walletId, String entryType,
                                     String reversesEntryId, double credits, double money) {
        jdbc.update("""
            INSERT INTO wallet_ledger_entries (entry_id, wallet_id, entry_type, signed_credits,
                                               signed_money_amount, currency, calculation_id,
                                               reverses_entry_id, created_at)
            VALUES (?, ?, ?, ?, ?, 'USD', 'calc-pg', ?, ?)
            """, entryId, walletId, entryType, credits, money, reversesEntryId, T0);
    }

    private static void insertEntitlementEvent(String eventId, String changeType, String reason) {
        jdbc.update("""
            INSERT INTO entitlement_events (event_id, tenant_id, customer_id, feature_key,
                                            change_type, feature_type, effective_at, recorded_at,
                                            reason, payload_json)
            VALUES (?, 'tenant-pg', 'customer-pg', 'feature-x', ?, 'BOOLEAN', ?, ?, ?, '{}')
            """, eventId, changeType, T0, T0, reason);
    }

    private static void insertOpenInvoice(String invoiceId) {
        jdbc.update("""
            INSERT INTO invoices (invoice_id, tenant_id, customer_id, plan_code, currency, status,
                                  invoice_number, period_start, period_end, issued_at,
                                  subtotal, tax_total, total, amount_paid, payload_json)
            VALUES (?, 'tenant-pg', 'customer-pg', 'PLAN', 'USD', 'OPEN',
                    ?, ?, ?, ?, 100, 0, 100, 0, '{}')
            """, invoiceId, "INV-" + invoiceId, T0, T1, T0);
    }

    private static void insertCreditNote(String creditNoteId, String invoiceId, double total) {
        jdbc.update("""
            INSERT INTO credit_notes (credit_note_id, tenant_id, invoice_id, invoice_number,
                                      currency, status, disposition, total, reason, issued_at,
                                      payload_json)
            VALUES (?, 'tenant-pg', ?, ?, 'USD', 'ISSUED', 'REFUND', ?, 'customer goodwill', ?, '{}')
            """, creditNoteId, invoiceId, "INV-" + invoiceId, total, T0);
    }

    private static int countWhere(String table, String predicate) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
        return count == null ? 0 : count;
    }
}
