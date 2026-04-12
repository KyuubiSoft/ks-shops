package com.kyuubisoft.common.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reusable database abstraction supporting SQLite and MySQL.
 * Standalone module — no Hytale imports.
 */
public interface DatabaseProvider {

    /**
     * Get a connection from the pool (MySQL) or the single connection (SQLite).
     */
    Connection getConnection() throws SQLException;

    /**
     * Initialize the provider (create file/pool, set pragmas, etc.).
     */
    void initialize() throws Exception;

    /**
     * Shutdown the provider (close pool/connection).
     */
    void shutdown();

    /**
     * Execute an update statement (INSERT, UPDATE, DELETE, CREATE TABLE, etc.).
     *
     * @param sql    SQL with ? placeholders
     * @param params parameters to bind
     * @return number of affected rows
     */
    int executeUpdate(String sql, Object... params) throws SQLException;

    /**
     * Execute a query and return the ResultSet.
     * IMPORTANT: The caller MUST close the returned ResultSet (and its Statement/Connection)
     * by calling {@code resultSet.getStatement().getConnection().close()} or using try-with-resources
     * on the wrapper returned here.
     *
     * @param sql    SQL with ? placeholders
     * @param params parameters to bind
     * @return ResultSet — caller must close
     */
    ResultSet executeQuery(String sql, Object... params) throws SQLException;

    /**
     * Whether this provider uses MySQL (true) or SQLite (false).
     */
    boolean isMySQL();
}
