package com.saas.pricing.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transaction-level chaos against a real PostgreSQL server.
 *
 * <p>{@link PostgresMigrationTest} proves the schema guards fire. This proves the thing those
 * guards cannot: that when a transaction dies — because the code threw, because the constraint
 * was violated, because the connection was severed, or because two transactions deadlocked —
 * the database is left in a state a reader would call correct.
 *
 * <p>These failures cannot be simulated with an in-memory repository. A mock throws where the
 * code decides to throw; a real server throws where the engine decides to, including killing a
 * backend mid-statement. That distinction is the entire point of the file.
 *
 * <p>Skips itself when Docker is unavailable rather than reporting a pass it did not earn.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresTransactionChaosTest {

    private static final Timestamp T0 = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
    private static final Timestamp T1 = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pricing_chaos")
            .withUsername("pricing")
            .withPassword("pricing");

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static PlatformTransactionManager txManager;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
    }

    // ------------------------------------------------------------------
    // Atomicity: a transaction that dies leaves nothing behind
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a transaction that throws after writing leaves no rows behind")
    void exceptionAfterWriteRollsEverythingBack() {
        String invoiceId = "inv-chaos-throw";
        var tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            insertInvoice(invoiceId, "INV-ROLLBACK-1", 100);
            insertInvoice("inv-chaos-throw-2", "INV-ROLLBACK-2", 250);
            throw new IllegalStateException("business rule failed after writing");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countInvoicesLike("INV-ROLLBACK-%"))
            .as("both writes were in one transaction, so neither survives")
            .isZero();
    }

    @Test
    @DisplayName("a constraint violation late in a transaction discards the earlier valid writes")
    void constraintViolationDiscardsEarlierWrites() {
        var tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            insertInvoice("inv-chaos-partial", "INV-PARTIAL-1", 100);
            // A duplicate invoice number: the second write must abort the whole transaction,
            // not just itself, or a reader would see one invoice and a number used twice.
            insertInvoice("inv-chaos-dup", "INV-PARTIAL-1", 100);
            return null;
        })).isInstanceOf(DataAccessException.class);

        assertThat(countInvoicesLike("INV-PARTIAL-%"))
            .as("the first, individually-valid invoice must not survive the failed transaction")
            .isZero();
    }

    // ------------------------------------------------------------------
    // Connection severed mid-transaction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a backend killed mid-transaction leaves the database consistent")
    void backendKilledMidTransactionIsAtomic() {
        AtomicReference<Integer> backendPid = new AtomicReference<>();
        var tx = new TransactionTemplate(txManager);

        try {
            tx.execute(status -> {
                Integer pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                backendPid.set(pid);
                insertInvoice("inv-chaos-kill", "INV-KILLED-1", 100);
                // Force the row to disk inside the transaction before we die.
                jdbc.execute("SELECT txid_current()");
                // Ask the server to terminate this very backend while the transaction is open and
                // dirty. The write above is not committed. Postgres aborts the transaction and
                // drops the connection; nothing after this line runs.
                jdbc.update("SELECT pg_terminate_backend(?)", pid);
                jdbc.update("SELECT 1");
                return null;
            });
        } catch (RuntimeException expected) {
            // The server severed the connection. That IS the scenario under test.
        }

        assertThat(backendPid.get())
            .as("the transaction must actually have been open when the backend was killed")
            .isNotNull();
        assertThat(countInvoicesLike("INV-KILLED-%"))
            .as("an uncommitted write lost to a severed connection must not persist")
            .isZero();
    }

    // ------------------------------------------------------------------
    // Serialization failure and deadlock
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SERIALIZABLE isolation surfaces a genuine conflict rather than silently losing an update")
    void serializableConflictIsSurfaced() throws Exception {
        String tenant = "tx-serializable";
        seedSeries(tenant, "SERIES-A", 0, 1);

        var tx = new TransactionTemplate(txManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        AtomicReference<Throwable> conflict = new AtomicReference<>();
        var bothIn = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable readThenWrite = () -> {
            try {
                bothIn.countDown();
                go.await();
                tx.execute(status -> {
                    Long seen = jdbc.queryForObject(
                        "SELECT next_value FROM invoice_number_sequences "
                            + "WHERE tenant_id = ? AND sequence_key = ?",
                        Long.class, tenant, "SERIES-A");
                    jdbc.update("UPDATE invoice_number_sequences SET next_value = ? "
                            + "WHERE tenant_id = ? AND sequence_key = ?",
                        seen + 1, tenant, "SERIES-A");
                    return null;
                });
            } catch (Throwable t) {
                conflict.set(t);
            }
        };

        try {
            pool.submit(readThenWrite);
            pool.submit(readThenWrite);
            assertThat(bothIn.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Either serialization aborted one of them (correct: the conflict is surfaced and the
        // caller retries), or both serializable snapshots happened to be compatible. What must
        // never happen is a silent lost update leaving next_value behind the two increments.
        long finalValue = jdbc.queryForObject(
            "SELECT next_value FROM invoice_number_sequences WHERE tenant_id = ? AND sequence_key = ?",
            Long.class, tenant, "SERIES-A");

        if (conflict.get() == null) {
            assertThat(finalValue)
                .as("without a reported conflict both writes must be visible - no lost update")
                .isEqualTo(2);
        } else {
            assertThat(finalValue)
                .as("the aborted transaction must not have advanced the counter")
                .isBetween(1L, 2L);
            // PostgreSQL words this "could not serialize access due to concurrent update"
            // (SQLSTATE 40001). Assert the property that matters - the conflict was named
            // rather than surfacing as an opaque error - not one vendor's phrasing.
            assertThat(rootMessage(conflict.get()))
                .as("a serialization failure must name itself, not surface as a generic error")
                .containsIgnoringCase("serialize");
        }
    }

    @Test
    @DisplayName("a deadlock kills exactly one transaction and leaves the survivor committed")
    void deadlockKillsOneTransactionOnly() throws Exception {
        String tenant = "tx-deadlock";
        seedSeries(tenant, "D1", 0, 1);
        seedSeries(tenant, "D2", 0, 1);

        var tx = new TransactionTemplate(txManager);
        AtomicReference<Throwable> victim = new AtomicReference<>();
        AtomicInteger committed = new AtomicInteger();

        // Two transactions grab the same two rows in opposite order. One must be aborted.
        Runnable a = () -> tryLockInOrder(tx, tenant, "D1", "D2", victim, committed);
        Runnable b = () -> tryLockInOrder(tx, tenant, "D2", "D1", victim, committed);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        try {
            pool.submit(() -> { await(go); a.run(); });
            pool.submit(() -> { await(go); b.run(); });
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(victim.get())
            .as("locking two rows in opposite order must deadlock")
            .isNotNull();
        assertThat(rootMessage(victim.get())).containsIgnoringCase("deadlock");
        assertThat(committed.get())
            .as("exactly one transaction may survive a deadlock")
            .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Gapless allocation under genuine lock contention
    // ------------------------------------------------------------------

    @Test
    @DisplayName("32 threads allocating from one series produce 32 distinct, gapless numbers")
    void concurrentAllocationIsGaplessUnderRealLocks() throws Exception {
        String tenant = "tx-gapless";
        seedSeries(tenant, "GAP", 0, 1);

        int threads = 32;
        var tx = new TransactionTemplate(txManager);
        var results = java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    await(go);
                    Long v = tx.execute(status -> {
                        List<Long> current = jdbc.query(
                            "SELECT next_value FROM invoice_number_sequences "
                                + "WHERE tenant_id = ? AND sequence_key = ? FOR UPDATE",
                            (rs, n) -> rs.getLong("next_value"), tenant, "GAP");
                        long next = current.getFirst() + 1;
                        jdbc.update("UPDATE invoice_number_sequences SET next_value = ? "
                                + "WHERE tenant_id = ? AND sequence_key = ?",
                            next, tenant, "GAP");
                        return next;
                    });
                    results.add(v);
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Set<Long> distinct = new HashSet<>(results);
        assertThat(distinct)
            .as("two threads receiving the same number is duplicate invoice numbering")
            .hasSize(threads);
        assertThat(distinct.stream().min(Long::compareTo).orElseThrow()).isEqualTo(1L);
        assertThat(distinct.stream().max(Long::compareTo).orElseThrow()).isEqualTo((long) threads);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable t) {
        var sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private static void tryLockInOrder(TransactionTemplate tx, String tenant, String first,
                                       String second, AtomicReference<Throwable> victim,
                                       AtomicInteger committed) {
        try {
            tx.execute(status -> {
                jdbc.queryForObject("SELECT next_value FROM invoice_number_sequences "
                    + "WHERE tenant_id = ? AND sequence_key = ? FOR UPDATE", Long.class, tenant, first);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.queryForObject("SELECT next_value FROM invoice_number_sequences "
                    + "WHERE tenant_id = ? AND sequence_key = ? FOR UPDATE", Long.class, tenant, second);
                jdbc.update("UPDATE invoice_number_sequences SET next_value = next_value + 1 "
                    + "WHERE tenant_id = ? AND sequence_key = ?", tenant, first);
                return null;
            });
            committed.incrementAndGet();
        } catch (Throwable t) {
            victim.compareAndSet(null, t);
        }
    }

    private static void seedSeries(String tenant, String key, long next, long startAt) {
        jdbc.update("""
            INSERT INTO invoice_number_sequences (tenant_id, sequence_key, next_value, start_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, sequence_key) DO NOTHING
            """, tenant, key, next, startAt, T0);
    }

    private static void insertInvoice(String invoiceId, String number, int total) {
        jdbc.update("""
            INSERT INTO invoices (invoice_id, tenant_id, customer_id, plan_code, currency, status,
                                  invoice_number, period_start, period_end, issued_at,
                                  subtotal, tax_total, total, amount_paid, payload_json)
            VALUES (?, 'tenant-chaos', 'customer-chaos', 'PLAN', 'USD', 'OPEN',
                    ?, ?, ?, ?, ?, 0, ?, 0, '{}')
            """, invoiceId, number, T0, T1, T0, total, total);
    }

    private static int countInvoicesLike(String numberPrefix) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM invoices WHERE invoice_number LIKE ?", Integer.class, numberPrefix + "%");
        return count == null ? 0 : count;
    }
}