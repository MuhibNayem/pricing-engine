package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.MeterLockRegistry;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * PostgreSQL advisory-lock implementation of {@link MeterLockRegistry}.
 *
 * <p>The in-JVM stripe registry cannot stop two application instances from aggregating, invalidating
 * and re-saving the same window: node A scans, node B saves and invalidates, then A writes its stale
 * aggregate back and the window is under-billed permanently. A session-scoped advisory lock makes
 * both nodes serialise on the same {@code (tenant, meter)} key.</p>
 *
 * <p>A connection is held for the duration of the lock. The engine's critical sections do I/O
 * (event scan, aggregation save), so the connection is genuinely needed for their duration; the
 * lock's scope is one meter, so a busy meter cannot stall an unrelated one beyond stripe collisions
 * that do not exist here.</p>
 */
public class JdbcAdvisoryMeterLockRegistry implements MeterLockRegistry {

    private final DataSource dataSource;

    public JdbcAdvisoryMeterLockRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
    }

    @Override
    public Lock lockFor(TenantId tenantId, String meterCode) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        return new AdvisoryLock(dataSource, advisoryKey(tenantId.value(), meterCode));
    }

    /**
     * Stable 64-bit key for a tenant+meter pair.
     *
     * <p>FNV-1a rather than {@link String#hashCode()} (32-bit, and different JVMs agree but a widened
     * 32-bit value wastes most of the space) so any node derives the same lock number.</p>
     */
    static long advisoryKey(String tenantId, String meterCode) {
        String key = tenantId + "::" + meterCode.toUpperCase(Locale.ROOT);
        long hash = 0xcbf29ce484222325L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** A non-reentrant lock backed by one advisory-locked connection. */
    static final class AdvisoryLock implements Lock {

        private final DataSource dataSource;
        private final long key;
        private final AtomicReference<Connection> held = new AtomicReference<>();

        private AdvisoryLock(DataSource dataSource, long key) {
            this.dataSource = dataSource;
            this.key = key;
        }

        @Override
        public void lock() {
            if (!acquire(false)) {
                throw new IllegalStateException(
                    "pg_try_advisory_lock unexpectedly failed for key " + key);
            }
        }

        @Override
        public void lockInterruptibly() {
            lock();
        }

        @Override
        public boolean tryLock() {
            return acquire(true);
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) {
            // Advisory locks are not timed; a bounded wait would have to poll and could starve.
            return tryLock();
        }

        @Override
        public void unlock() {
            Connection connection = held.getAndSet(null);
            if (connection == null) {
                throw new IllegalStateException("Advisory lock was not held");
            }
            try {
                try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    statement.setLong(1, key);
                    try (ResultSet ignored = statement.executeQuery()) {
                        // Result ignored: a false value would mean the session no longer held it.
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not release meter advisory lock " + key, e);
            } finally {
                closeQuietly(connection);
            }
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException("Advisory locks do not support conditions");
        }

        private boolean acquire(boolean nonBlocking) {
            if (held.get() != null) {
                throw new IllegalStateException(
                    "Advisory lock is not reentrant and this instance is already held");
            }
            Connection connection = null;
            try {
                connection = dataSource.getConnection();
                connection.setAutoCommit(true);
                String sql = nonBlocking
                    ? "SELECT pg_try_advisory_lock(?)"
                    : "SELECT pg_advisory_lock(?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, key);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (nonBlocking) {
                            boolean acquired = resultSet.next() && resultSet.getBoolean(1);
                            if (!acquired) {
                                closeQuietly(connection);
                                return false;
                            }
                        } else {
                            resultSet.next();
                        }
                    }
                }
                held.set(connection);
                return true;
            } catch (SQLException e) {
                closeQuietly(connection);
                throw new IllegalStateException("Could not acquire meter advisory lock " + key, e);
            }
        }

        private static void closeQuietly(Connection connection) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing is best effort; the connection returns to the pool on its own.
            }
        }
    }
}
