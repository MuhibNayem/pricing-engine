package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.PaymentAttempt;
import com.saas.pricing.persistence.jdbc.JdbcCollectionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Collection ledger persistence.
 *
 * <p>Note on coverage: migration V11's PostgreSQL rules are not exercised here because the H2 build
 * used by this suite cannot execute plpgsql.
 */
class JdbcCollectionRepositoryTest extends BaseJdbcRepositoryTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-09T00:00:00Z");

    private JdbcCollectionRepository repository() {
        return new JdbcCollectionRepository(jdbcTemplate);
    }

    private void seedInvoice(String invoiceId) {
        jdbcTemplate.update("""
            INSERT INTO invoices (
                invoice_id, tenant_id, customer_id, plan_code, currency, status, invoice_number,
                period_start, period_end, issued_at, subtotal, tax_total, total, amount_paid, payload_json
            ) VALUES (?, 't1', 'c1', 'PRO', 'USD', 'OPEN', ?,
                ?, ?, ?, 100, 0, 100, 0, '{}')
            """, invoiceId, "INV-" + invoiceId,
            java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0.plusSeconds(86400)),
            java.sql.Timestamp.from(T0));
    }

    @Test
    @DisplayName("attempts round-trip")
    void roundTrips() {
        seedInvoice("inv-1");
        var repo = repository();
        var attempt = PaymentAttempt.failed("inv-1", 1, Money.of("100.00", USD), T0,
            "card_declined", "insufficient funds", Optional.of(T1));

        assertThat(repo.record(attempt)).isTrue();
        var loaded = repo.findAttempts(TenantId.of("t1"), "inv-1");

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().attemptId()).isEqualTo("inv-1-1");
        assertThat(loaded.getFirst().status()).isEqualTo(PaymentAttempt.Status.FAILED_RETRYABLE);
        assertThat(loaded.getFirst().failureCode()).contains("card_declined");
        assertThat(loaded.getFirst().nextAttemptAt()).contains(T1);
    }

    @Test
    @DisplayName("an identical re-delivery is a no-op, not a second charge")
    void redeliveryIsIdempotent() {
        seedInvoice("inv-2");
        var repo = repository();
        var attempt = PaymentAttempt.succeeded("inv-2", 1, Money.of("100.00", USD), T0);

        assertThat(repo.record(attempt)).isTrue();
        assertThat(repo.record(attempt))
            .as("a timed-out collection agent re-sending must not take the money again")
            .isFalse();
        assertThat(repo.findAttempts(TenantId.of("t1"), "inv-2")).hasSize(1);
    }

    @Test
    @DisplayName("a conflicting attempt number is refused rather than overwriting")
    void conflictingAttemptRefused() {
        seedInvoice("inv-3");
        var repo = repository();

        assertThat(repo.record(PaymentAttempt.failed("inv-3", 1, Money.of("100.00", USD), T0,
            "declined", "first", Optional.of(T1)))).isTrue();

        // Different content, same (invoice, attempt number): the uniqueness constraint must hold.
        assertThatThrownBy(() -> repo.record(PaymentAttempt.failed("inv-3", 1,
                Money.of("50.00", USD), T0, "expired_card", "card expired", Optional.of(T1))))
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a failure without a processor code is refused by the schema")
    void failureCodeEnforcedBySchema() {
        seedInvoice("inv-4");

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO payment_attempts (
                attempt_id, invoice_id, attempt_number, amount, currency, status,
                attempted_at, next_attempt_at, payload_json
            ) VALUES ('bad-1', 'inv-4', 1, 10.00, 'USD', 'FAILED_RETRYABLE', ?, ?, '{}')
            """, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T1)))
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("the due query finds retries that are due and ignores those that are not")
    void dueQuery() {
        seedInvoice("inv-5");
        var repo = repository();

        repo.record(PaymentAttempt.failed("inv-5", 1, Money.of("100.00", USD), T0,
            "declined", "retry soon", Optional.of(T1)));

        assertThat(repo.findDue(T0)).isEmpty();
        assertThat(repo.findDue(T1.minusSeconds(1))).isEmpty();
        assertThat(repo.findDue(T1)).hasSize(1);
        assertThat(repo.findDue(T1.plusSeconds(60))).hasSize(1);
    }

    @Test
    @DisplayName("attempt reads are tenant-scoped through the invoice")
    void readsAreTenantScoped() {
        seedInvoice("inv-scoped");
        var repo = repository();
        repo.record(PaymentAttempt.succeeded("inv-scoped", 1, Money.of("100.00", USD), T0));

        assertThat(repo.findAttempts(TenantId.of("t1"), "inv-scoped")).hasSize(1);
        assertThat(repo.findAttempts(TenantId.of("other"), "inv-scoped")).isEmpty();
        assertThat(repo.findLatest(TenantId.of("other"), "inv-scoped")).isEmpty();
    }
}