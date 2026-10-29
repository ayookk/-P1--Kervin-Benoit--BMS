package com.example.banking.util;

import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.TransientDbException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs several SQL steps as ONE database transaction: all commit together, or all roll back.
 * The service decides WHAT to do; this class handles begin / commit / rollback (doc AD-3).
 */
public class JdbcTransaction {

    /** The steps to run. Every DAO call inside must use the given connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection conn) throws SQLException;
    }

    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

    private final ConnectionFactory connections;
    private final int maxAttempts;

    public JdbcTransaction(ConnectionFactory connections, int maxAttempts) {
        this.connections = connections;
        this.maxAttempts = maxAttempts;
    }

    /** Begin → work → commit. Any failure → rollback, then rethrow as a typed exception. */
    public <T> T execute(Work<T> work) {
        try (Connection conn = connections.getConnection()) {
            // explicit isolation level so MySQL and PostgreSQL behave the same (doc AD-5)
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Throwable t) {
                rollbackQuietly(conn, t);
                throw translate(t);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not run database transaction", e);
        }
    }

    /** Same, but retries the WHOLE unit of work on a deadlock or lock timeout (max attempts, short random wait). */
    public <T> T executeWithRetry(Work<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return execute(work);
            } catch (TransientDbException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    /** True if anything in the cause chain is a deadlock or lock-wait timeout. */
    static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql
                    && (sql.getErrorCode() == MYSQL_DEADLOCK
                        || sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
                        || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private static RuntimeException translate(Throwable t) {
        if (t instanceof Error err) {
            throw err;                                  // never swallow JVM errors
        }
        if (isTransient(t)) {
            return new TransientDbException(t);
        }
        if (t instanceof RuntimeException re) {
            return re;                                  // e.g. InsufficientFundsException: pass it through unchanged
        }
        return new PersistenceException("Database error during transaction", t);
    }

    private static void rollbackQuietly(Connection conn, Throwable original) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);    // keep both errors for the log
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // connection is being closed anyway
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(25, 100) * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PersistenceException("Interrupted while retrying", ie);
        }
    }
}