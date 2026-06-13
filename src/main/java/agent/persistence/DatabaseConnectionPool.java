package agent.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Manages the single SQLite JDBC connection used by the agent.
 *
 * <h2>Single connection design</h2>
 * SQLite is an embedded database. We share one connection between the
 * persistence daemon (writes) and the HTTP thread (reads). WAL mode (enabled
 * by {@link SchemaInitializer}) allows concurrent reads and writes without
 * blocking. The connection is held open for the agent's lifetime — SQLite has
 * no per-connection overhead that would make pooling worthwhile here.
 *
 * <h2>Auto-commit</h2>
 * The connection is opened with auto-commit OFF so the repository can group
 * many INSERTs into one transaction (one disk flush per batch). The schema
 * initializer briefly re-enables it while applying pragmas; see that class.
 *
 * <h2>JDBC URL format</h2>
 * <pre>
 *   jdbc:sqlite:/path/to/file.db  → opens/creates the file at that path
 *   jdbc:sqlite::memory:           → in-memory database (used in tests)
 * </pre>
 *
 * <h2>Driver class name</h2>
 * The sqlite-jdbc driver class is {@code org.sqlite.JDBC}. Note it is NOT
 * relocated by the shade plugin: sqlite-jdbc's native library binds JNI symbols
 * to the hardcoded path {@code org/sqlite/core/NativeDB}, so relocating the
 * package would break native loading (xerial/sqlite-jdbc#145). JDBC 4 also
 * auto-registers the driver via {@code META-INF/services/java.sql.Driver}; we
 * keep an explicit Class.forName as a clear, fail-fast presence check.
 */
public final class DatabaseConnectionPool {

    private static final Logger log =
        Logger.getLogger(DatabaseConnectionPool.class.getName());

    /**
     * sqlite-jdbc driver class name. Intentionally the original (unrelocated)
     * package — see the class javadoc for why org.sqlite must not be shaded.
     */
    private static final String DRIVER_CLASS = "org.sqlite.JDBC";

    private final Connection connection;

    /**
     * Opens (or creates) a SQLite database.
     *
     * @param dbPath filesystem path to the database file, or {@code null} for
     *               an in-memory database (used by tests)
     */
    public DatabaseConnectionPool(String dbPath) throws SQLException {
        ensureDriverLoaded();

        // A null path means "in-memory" — this also keeps the inMemory() factory
        // correct (the spec's original "new DatabaseConnectionPool(null){}" would
        // otherwise have created a file literally named "null").
        String jdbcUrl = (dbPath == null)
            ? "jdbc:sqlite::memory:"
            : "jdbc:sqlite:" + dbPath;

        this.connection = DriverManager.getConnection(jdbcUrl);

        // Manage transactions manually for batched writes.
        this.connection.setAutoCommit(false);

        log.info("SQLite connection opened: " + (dbPath == null ? ":memory:" : dbPath));
    }

    /**
     * Test hook — opens a fresh, isolated in-memory database. Each call returns
     * an independent empty database with no leftover state.
     */
    public static DatabaseConnectionPool inMemory() throws SQLException {
        return new DatabaseConnectionPool(null);
    }

    /** @return the shared JDBC connection. */
    public Connection get() {
        return connection;
    }

    /**
     * Commits any pending work and closes the connection. Safe to call twice.
     */
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.commit();
                connection.close();
                log.info("SQLite connection closed");
            }
        } catch (SQLException e) {
            log.warning("Error closing SQLite connection: " + e.getMessage());
        }
    }

    /**
     * Loads the (relocated) SQLite driver, failing fast with a clear message if
     * the shade relocation is misconfigured.
     */
    private static void ensureDriverLoaded() throws SQLException {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                "SQLite JDBC driver '" + DRIVER_CLASS + "' not found on the classpath", e);
        }
    }
}
