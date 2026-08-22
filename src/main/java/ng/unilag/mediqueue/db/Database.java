package ng.unilag.mediqueue.db;

import ng.unilag.mediqueue.config.AppConfig;
import ng.unilag.mediqueue.exception.MediQueueException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Hands out JDBC connections and runs units of work against them.
 *
 * <p>Every connection is opened per request and closed by try-with-resources. That is
 * slower than pooling, but it is correct and it cannot leak; a health centre queue board
 * has nothing like the traffic where pooling would matter.
 *
 * <p>Spring Boot port: this class disappears entirely. Boot auto-configures a HikariCP
 * DataSource from the same db.* properties, and {@code @Transactional} replaces
 * {@link #inTransaction}.
 */
public final class Database {

    /** A unit of work that returns a value and may fail with a SQLException. */
    @FunctionalInterface
    public interface Work<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final String url;
    private final String user;
    private final String password;

    public Database(AppConfig config) {
        this.url = config.databaseUrl();
        this.user = config.databaseUser();
        this.password = config.databasePassword();
        try {
            // Makes the failure mode obvious if lib/postgresql-*.jar is missing from
            // the classpath, rather than a confusing "No suitable driver" much later.
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new MediQueueException(
                    "PostgreSQL JDBC driver not found. Is lib/postgresql-42.7.4.jar on the classpath?", e);
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** Runs read-only work on a fresh auto-commit connection. */
    public <T> T query(Work<T> work) {
        try (Connection connection = open()) {
            return work.execute(connection);
        } catch (SQLException e) {
            throw new MediQueueException("Database read failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs work inside a transaction, committing on success and rolling back on any
     * failure. Booking depends on this: the SELECT ... FOR UPDATE that reserves a queue
     * number is only meaningful if it and the INSERT share one transaction.
     */
    public <T> T inTransaction(Work<T> work) {
        Connection connection = null;
        try {
            connection = open();
            connection.setAutoCommit(false);
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new MediQueueException("Transaction failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // A validation error thrown by the caller must still roll back the transaction,
            // but it should reach the caller unchanged so the correct HTTP status is sent.
            rollbackQuietly(connection);
            throw e;
        } finally {
            closeQuietly(connection);
        }
    }

    /** Confirms the database is reachable, so startup fails loudly rather than on first use. */
    public void verifyConnection() {
        try (Connection connection = open()) {
            if (!connection.isValid(5)) {
                throw new MediQueueException("Database connection is not valid: " + url);
            }
        } catch (SQLException e) {
            throw new MediQueueException(
                    "Cannot reach PostgreSQL at " + url + " (" + e.getMessage() + ")."
                    + "\n  If you are running the local Docker database: docker start mediqueue-db"
                    + "\n  Otherwise check MEDIQUEUE_DB_URL / DATABASE_URL and the credentials.", e);
        }
    }

    private void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException suppressed) {
            System.err.println("WARN: rollback failed: " + suppressed.getMessage());
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.setAutoCommit(true);
            connection.close();
        } catch (SQLException suppressed) {
            System.err.println("WARN: closing connection failed: " + suppressed.getMessage());
        }
    }
}
