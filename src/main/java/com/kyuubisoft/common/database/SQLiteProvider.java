package com.kyuubisoft.common.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.logging.Logger;

/**
 * SQLite implementation of DatabaseProvider.
 * Uses a single persistent connection with WAL journal mode.
 * Standalone module — no Hytale imports.
 */
public class SQLiteProvider implements DatabaseProvider {

    private static final Logger LOGGER = Logger.getLogger("DatabaseProvider");

    private final Path dbPath;
    private Connection connection;
    private final Object lock = new Object();

    public SQLiteProvider(Path dbPath) {
        this.dbPath = dbPath;
    }

    @Override
    public void initialize() throws Exception {
        Files.createDirectories(dbPath.getParent());

        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());

        // WAL mode for concurrent read access
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("PRAGMA synchronous=NORMAL");
        }

        LOGGER.info("SQLite initialized: " + dbPath.toAbsolutePath());
    }

    @Override
    public Connection getConnection() throws SQLException {
        synchronized (lock) {
            if (connection == null || connection.isClosed()) {
                try {
                    connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute("PRAGMA journal_mode=WAL");
                        stmt.execute("PRAGMA busy_timeout=5000");
                        stmt.execute("PRAGMA synchronous=NORMAL");
                    }
                } catch (SQLException e) {
                    throw e;
                }
            }
            return connection;
        }
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    LOGGER.warning("Failed to close SQLite connection: " + e.getMessage());
                }
                connection = null;
            }
        }
        LOGGER.info("SQLite shutdown complete");
    }

    @Override
    public int executeUpdate(String sql, Object... params) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                bindParams(ps, params);
                return ps.executeUpdate();
            }
        }
    }

    @Override
    public ResultSet executeQuery(String sql, Object... params) throws SQLException {
        synchronized (lock) {
            PreparedStatement ps = getConnection().prepareStatement(sql);
            bindParams(ps, params);
            return ps.executeQuery();
        }
    }

    @Override
    public boolean isMySQL() {
        return false;
    }

    private void bindParams(PreparedStatement ps, Object... params) throws SQLException {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }
    }
}
