package com.saas.pricing.persistence.jdbc;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * Runs an INSERT that may collide with an existing primary key, without destroying the caller's
 * transaction on PostgreSQL.
 *
 * <h2>The problem this exists for</h2>
 * An append-only store treats a duplicate id as "already recorded" and then reads the stored row to
 * compare its content. Catching the duplicate-key exception and running that read is not enough:
 * PostgreSQL aborts the whole transaction on the failed statement, so the follow-up SELECT fails
 * with "current transaction is aborted" (SQLSTATE 25P02) - and so does every statement after it.
 * H2 does not abort, which is why this pattern passed the portable suite while being unusable on a
 * real PostgreSQL deployment.
 *
 * <p>A savepoint is the standard remedy: the failed INSERT is rolled back to the savepoint, leaving
 * the enclosing transaction healthy, and the recovery read runs normally. When no transaction is
 * active the statement autocommits and its implicit rollback is already isolated, so a plain catch
 * is sufficient (and savepoints are not even legal in auto-commit mode on some drivers).
 */
final class JdbcDuplicateGuard {

    private JdbcDuplicateGuard() {
        // static utility
    }

    /**
     * Executes {@code insert}, returning whether the row was written.
     *
     * @return {@code true} when the row was inserted; {@code false} when a unique/primary key
     *         already exists for it and the caller should treat the write as an existing record
     */
    static boolean insertOrIgnore(JdbcTemplate jdbcTemplate, Runnable insert) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                insert.run();
                return true;
            } catch (DuplicateKeyException e) {
                return false;
            }
        }

        DataSource dataSource = jdbcTemplate.getDataSource();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint;
        try {
            savepoint = connection.setSavepoint("pricing_duplicate_guard");
        } catch (SQLException e) {
            // No savepoint means the recovery read could poison the transaction; fail loudly rather
            // than writing code that silently only works on H2.
            throw new IllegalStateException(
                "Cannot open a savepoint for duplicate-key recovery on connection "
                    + connection.getClass().getName(), e);
        }

        try {
            insert.run();
        } catch (DuplicateKeyException e) {
            rollbackTo(connection, savepoint, e);
            return false;
        } finally {
            releaseQuietly(connection, savepoint);
        }
        return true;
    }

    private static void rollbackTo(Connection connection, Savepoint savepoint, Exception cause) {
        try {
            connection.rollback(savepoint);
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void releaseQuietly(Connection connection, Savepoint savepoint) {
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException ignored) {
            // A savepoint that cannot be released is cleaned up when the transaction completes;
            // failing the successful insert because of it would be worse than the leak.
        }
    }
}
