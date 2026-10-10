package com.saas.pricing.persistence.jdbc;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Network-level chaos: the connection dies, not the backend.
 *
 * <p>{@link PostgresTransactionChaosTest} kills a backend from <em>inside</em> PostgreSQL. That
 * proves the transaction is rolled back, but it is not the failure a client experiences. In
 * production the network is what breaks: the cable is cut, the LB drops an idle connection, a proxy
 * times out. The connection dies without the server being told, so the client gets a socket error
 * while the server still holds an open transaction that will eventually abort.</p>
 *
 * <p>That gap is exactly where money bugs live. The two behaviours worth proving:</p>
 * <ol>
 *   <li>A write whose connection dies must <em>not</em> be reported as successful. Silently
 *       swallowing a socket error after the server actually committed would let a caller retry a
 *       charge that already happened.</li>
 *   <li>An in-flight transaction must leave nothing behind, and the store must recover without
 *       manual intervention once the network returns.</li>
 * </ol>
 *
 * <p>Skips itself when Docker is unavailable rather than reporting a pass it did not earn.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class NetworkFaultChaosTest {

    private static final Timestamp T0 = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
    private static final Timestamp T1 = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));

    /** Not a @Container: Network is not Startable, so the JUnit extension must not try to launch it. */
    static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("pricing_netchaos")
            .withUsername("pricing")
            .withPassword("pricing");

    @Container
    static final ToxiproxyContainer TOXIPROXY =
        new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(NETWORK);

    @org.junit.jupiter.api.AfterAll
    static void tearDownNetwork() {
        NETWORK.close();
    }

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static PlatformTransactionManager txManager;
    static ToxiproxyContainer.ContainerProxy proxy;

    @BeforeAll
    static void routeThroughProxy() {
        // Toxiproxy resolves the upstream inside its own network namespace, so it must be given a
        // name that resolves there - the JDBC-visible "localhost" is Toxiproxy's own container.
        proxy = TOXIPROXY.getProxy("postgres", 5432);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + proxy.getProxyPort()
            + "/" + POSTGRES.getDatabaseName());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        // connectTimeout alone is not enough, and the reason is worth recording. Toxiproxy keeps
        // accepting TCP connections when the link is cut, so the TCP handshake succeeds and the
        // driver's connectTimeout never fires. The driver then blocks reading the server's startup
        // packet, which is a *read* with no deadline, so the OS TCP timeout is what finally ends it -
        // measured at 165s on this machine. A suite whose failure mode is a three-minute hang is a
        // suite people disable, so socketTimeout bounds it too.
        //
        // 10s is chosen against this class's actual statements: every probe here completes in single-
        // digit milliseconds on a healthy network and the only deliberately slow one is the 1.5s
        // latency toxic. It is a bound, not a target, and the DataSource is private to this class, so
        // it cannot leak a deadline onto any other test.
        ds.setUrl(ds.getUrl() + "?connectTimeout=5&socketTimeout=10");

        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    /**
     * Ceiling on how long any injected network fault may take to surface.
     *
     * <p>Ten times the configured socketTimeout, so a machine slower than this one still passes while
     * a regression back to unbounded blocking - the 165s behaviour - fails loudly and quickly instead
     * of quietly consuming the build.</p>
     */
    private static final long MAX_FAULT_SURFACE_MILLIS = 100_000L;

    @BeforeEach
    void restoreNetwork() throws Exception {
        proxy.setConnectionCut(false);
        // Clear every toxic, not just the ones this test knows about: a leftover latency toxic
        // would silently turn an unrelated test into a timeout test, which is how these pass for
        // the wrong reason.
        for (eu.rekawek.toxiproxy.model.Toxic toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    private static String newKey() {
        return "net-" + UUID.randomUUID();
    }

    private int countIdempotencyKey(String key) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND idem_key = ?",
            Integer.class, "acme", key);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a severed connection fails loudly instead of reporting a phantom success")
    void severedConnectionDoesNotReportSuccess() {
        String key = newKey();
        proxy.setConnectionCut(true);

        // The dangerous failure mode is the opposite of this one: the server commits, the socket
        // dies before the client sees the acknowledgement, and the code concludes "it worked".
        long start = System.nanoTime();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, recorded_at, expires_at) "
                    + "VALUES (?, ?, ?, 'IN_FLIGHT', ?, ?)",
                "acme", key, "fp", T0, T1))
            .as("a cut connection must surface as a failure to the caller")
            .isInstanceOf(DataAccessException.class);

        // The connection is cut at the proxy, which still completes the TCP handshake, so this
        // blocks in the driver's startup read rather than in connect. Assert the deadline holds: a
        // test that hangs for three minutes is worse than no test, because it trains people to
        // disable the suite it belongs to.
        assertThat((System.nanoTime() - start) / 1_000_000L)
            .as("a severed connection must surface promptly, not after the OS TCP timeout")
            .isLessThan(MAX_FAULT_SURFACE_MILLIS);
    }

    @Test
    @DisplayName("a connection cut mid-handshake is bounded, not left to the OS")
    void severedConnectionDoesNotHangOnAuthentication() {
        // The specific shape that cost 165s: a fresh connection whose peer accepts TCP and then says
        // nothing. Without a read deadline the driver waits on the kernel, not on anything this
        // suite controls, so the failure lands minutes later and on a schedule nobody chose.
        proxy.setConnectionCut(true);

        long start = System.nanoTime();
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT 1", Integer.class))
            .isInstanceOf(DataAccessException.class);

        assertThat((System.nanoTime() - start) / 1_000_000L)
            .as("the socket timeout must bound this; the OS TCP timeout must not")
            .isLessThan(MAX_FAULT_SURFACE_MILLIS);
    }

    @Test
    @DisplayName("an in-flight transaction leaves nothing behind when the connection dies")
    void inFlightTransactionIsDiscardedWhenConnectionDies() throws Exception {
        String key = newKey();
        CountDownLatch wrote = new CountDownLatch(1);
        CountDownLatch cut = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean committed = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            try {
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    jdbc.update(
                        "INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, recorded_at, expires_at) "
                            + "VALUES (?, ?, ?, 'IN_FLIGHT', ?, ?)",
                        "acme", key, "fp", T0, T1);
                    wrote.countDown();
                    awaitQuietly(cut);
                    status.setRollbackOnly();
                });
                committed.set(true);
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "network-chaos-writer");
        worker.start();

        try {
            assertThat(wrote.await(20, TimeUnit.SECONDS)).as("worker wrote its row").isTrue();
            proxy.setConnectionCut(true);
            cut.countDown();

            worker.join(30_000);
            assertThat(committed.get())
                .as("the transaction must not have committed")
                .isFalse();
        } finally {
            proxy.setConnectionCut(false);
            worker.join(10_000);
        }

        // The row must not exist: the write and its rollback happened together.
        assertThat(countIdempotencyKey(key))
            .as("a transaction killed by a severed connection must leave no partial row")
            .isZero();
    }

    @Test
    @DisplayName("the store recovers on its own once the network returns")
    void recoversAfterNetworkRestored() {
        proxy.setConnectionCut(true);
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT 1", Integer.class))
            .isInstanceOf(DataAccessException.class);

        proxy.setConnectionCut(false);

        String key = newKey();
        jdbc.update(
            "INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, recorded_at, expires_at) "
                + "VALUES (?, ?, ?, 'IN_FLIGHT', ?, ?)",
            "acme", key, "fp", T0, T1);

        assertThat(countIdempotencyKey(key))
            .as("no restart and no manual repair; the next write simply succeeds")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("latency beyond the socket timeout fails rather than hanging")
    void injectedLatencyIsObservable() throws Exception {
        // Hold the connection open first. With DriverManagerDataSource every statement opens a
        // fresh connection, so the delay would land on connect and a statement-level deadline
        // would never get its chance to fire.
        Connection connection = dataSource.getConnection();
        try {
            try (java.sql.Statement warm = connection.createStatement()) {
                warm.execute("SELECT 1");
            }

            proxy.toxics().latency("jdbc", ToxicDirection.DOWNSTREAM, 1_500);

            JdbcTemplate delayed = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));

            long start = System.nanoTime();
            assertThat(delayed.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs)
                .as("the toxic must actually apply, or every other assertion here is vacuous")
                .isGreaterThanOrEqualTo(1_500L);
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("a unique constraint still holds while the network is healthy")
    void uniquenessHoldsUnderNormalTraffic() {
        String key = newKey();
        jdbc.update(
            "INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, recorded_at, expires_at) "
                + "VALUES (?, ?, ?, 'IN_FLIGHT', ?, ?)",
            "acme", key, "fp", T0, T1);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO idempotency_keys (tenant_id, idem_key, fingerprint, status, recorded_at, expires_at) "
                    + "VALUES (?, ?, ?, 'IN_FLIGHT', ?, ?)",
                "acme", key, "fp", T0, T1))
            .as("the PK is the durable guarantee; network chaos must not erode it")
            .isInstanceOf(DataAccessException.class);

        assertThat(countIdempotencyKey(key)).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}