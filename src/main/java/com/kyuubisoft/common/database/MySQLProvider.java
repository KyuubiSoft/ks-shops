package com.kyuubisoft.common.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.logging.Logger;

/**
 * MySQL implementation of DatabaseProvider using HikariCP connection pool.
 * Standalone module — no Hytale imports.
 */
public class MySQLProvider implements DatabaseProvider {

    private static final Logger LOGGER = Logger.getLogger("DatabaseProvider");

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    private HikariDataSource dataSource;

    public MySQLProvider(String host, int port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    @Override
    public void initialize() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000);      // 5 minutes
        config.setMaxLifetime(600000);      // 10 minutes
        config.setConnectionTimeout(10000); // 10 seconds
        config.setPoolName("KS-Shops-MySQL");

        // Performance tuning
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        dataSource = new HikariDataSource(config);

        LOGGER.info("MySQL pool initialized: " + host + ":" + port + "/" + database);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("MySQL DataSource is not initialized or has been closed");
        }
        return dataSource.getConnection();
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        LOGGER.info("MySQL pool shutdown complete");
    }

    @Override
    public int executeUpdate(String sql, Object... params) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    @Override
    public ResultSet executeQuery(String sql, Object... params) throws SQLException {
        // For MySQL with connection pooling, we need to keep the connection open
        // until the caller is done with the ResultSet.
        Connection conn = getConnection();
        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            bindParams(ps, params);
            return ps.executeQuery();
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    @Override
    public boolean isMySQL() {
        return true;
    }

    private void bindParams(PreparedStatement ps, Object... params) throws SQLException {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }
    }
}
