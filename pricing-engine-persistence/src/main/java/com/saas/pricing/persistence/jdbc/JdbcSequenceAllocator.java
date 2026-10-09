package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.SequenceAllocator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * JDBC sequence allocator.
 *
 * <p>Allocation takes a row lock with {@code SELECT ... FOR UPDATE}, then updates, <strong>inside a
 * transaction</strong>. Both halves matter and it is easy to get only one:
 *
 * <ul>
 *   <li>The lock is what makes two concurrent finalizations produce two different numbers. Without
 *       it, both read {@code next = 41} and both write 42 — a duplicate invoice number, which is
 *       both an audit failure and a duplicate-money problem.</li>
 *   <li>The <em>transaction</em> is what makes the lock mean anything. Under autocommit the row lock
 *       is released at the end of the {@code SELECT}, before the {@code UPDATE} runs, so the lock
 *       never overlaps anything and the race is exactly as open as if it were absent. This was not
 *       theoretical: with no transaction, sixteen concurrent callers produced four distinct
 *       numbers.</li>
 * </ul>
 *
 * <p>The lock's scope is one series, so a busy customer cannot stall an unrelated one.
 *
 * <p>{@code TransactionTemplate} rather than {@code @Transactional}: these repositories are built by
 * hand, so the annotation would be inert and quietly misleading about the guarantee.
 */
public class JdbcSequenceAllocator implements SequenceAllocator {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;

    public JdbcSequenceAllocator(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
        this.tx = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager cannot be null"));
    }

    @Override
    public long nextValue(TenantId tenantId, String sequenceKey, long startAt) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(sequenceKey, "sequenceKey cannot be null");
        if (startAt < 1) {
            throw new IllegalArgumentException("Sequence must start at 1 or later, got " + startAt);
        }

        // Created outside the transaction on purpose: a duplicate-key failure inside one aborts the
        // whole transaction on PostgreSQL, so the benign "someone else created it first" race must
        // not happen where it would poison the work that follows.
        ensureSeries(tenantId, sequenceKey, startAt);

        Long allocated = tx.execute(status -> {
            List<Long> current = jdbcTemplate.query("""
                SELECT next_value FROM invoice_number_sequences
                WHERE tenant_id = ? AND sequence_key = ?
                FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("next_value"), tenantId.value(), sequenceKey);

            long latest = current.getFirst();
            long next = Math.max(latest, startAt - 1) + 1;

            jdbcTemplate.update("""
                UPDATE invoice_number_sequences SET next_value = ?, updated_at = ?
                WHERE tenant_id = ? AND sequence_key = ?
                """, next, Timestamp.from(Instant.now()), tenantId.value(), sequenceKey);

            return next;
        });

        return Objects.requireNonNull(allocated, "sequence allocation returned no value");
    }

    @Override
    public boolean restore(TenantId tenantId, String sequenceKey, long value) {
        // A single compare-and-set UPDATE is already atomic, so no transaction is needed here.
        // Rewinding past a number that was already issued would re-issue it to a second customer,
        // which is worse than the gap this prevents.
        return jdbcTemplate.update("""
            UPDATE invoice_number_sequences SET next_value = next_value - 1, updated_at = ?
            WHERE tenant_id = ? AND sequence_key = ? AND next_value = ?
            """, Timestamp.from(Instant.now()), tenantId.value(), sequenceKey, value) == 1;
    }

    @Override
    public long peek(TenantId tenantId, String sequenceKey, long startAt) {
        List<Long> rows = jdbcTemplate.query("""
            SELECT next_value FROM invoice_number_sequences
            WHERE tenant_id = ? AND sequence_key = ?
            """, (rs, rowNum) -> rs.getLong("next_value"), tenantId.value(), sequenceKey);
        return rows.isEmpty() ? startAt - 1 : rows.getFirst();
    }

    /**
     * Creates the series on first use.
     *
     * <p>Seeded at {@code start_at - 1}, not 0: a migrated series starting at 500 must have a
     * counter already sitting just below it, and the schema forbids a counter before its own start.
     */
    private void ensureSeries(TenantId tenantId, String sequenceKey, long startAt) {
        try {
            jdbcTemplate.update("""
                INSERT INTO invoice_number_sequences (tenant_id, sequence_key, next_value, start_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId.value(), sequenceKey, startAt - 1, startAt, Timestamp.from(Instant.now()));
        } catch (org.springframework.dao.DuplicateKeyException alreadyCreated) {
            // Another thread created it between our lookup and this insert. Its settings stand.
        }
    }
}