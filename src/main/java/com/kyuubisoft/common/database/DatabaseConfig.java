package com.kyuubisoft.common.database;

/**
 * Configuration POJO for database settings.
 * Standalone module — no Hytale imports.
 */
public class DatabaseConfig {

    private String type = "sqlite";
    private String sqlitePath = "data.db";
    private String mysqlHost = "localhost";
    private int mysqlPort = 3306;
    private String mysqlDatabase = "hytale";
    private String mysqlUsername = "root";
    private String mysqlPassword = "";

    public DatabaseConfig() {}

    public DatabaseConfig(String type, String sqlitePath) {
        this.type = type;
        this.sqlitePath = sqlitePath;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSqlitePath() { return sqlitePath; }
    public void setSqlitePath(String sqlitePath) { this.sqlitePath = sqlitePath; }

    public String getMysqlHost() { return mysqlHost; }
    public void setMysqlHost(String mysqlHost) { this.mysqlHost = mysqlHost; }

    public int getMysqlPort() { return mysqlPort; }
    public void setMysqlPort(int mysqlPort) { this.mysqlPort = mysqlPort; }

    public String getMysqlDatabase() { return mysqlDatabase; }
    public void setMysqlDatabase(String mysqlDatabase) { this.mysqlDatabase = mysqlDatabase; }

    public String getMysqlUsername() { return mysqlUsername; }
    public void setMysqlUsername(String mysqlUsername) { this.mysqlUsername = mysqlUsername; }

    public String getMysqlPassword() { return mysqlPassword; }
    public void setMysqlPassword(String mysqlPassword) { this.mysqlPassword = mysqlPassword; }

    public boolean isSQLite() {
        return "sqlite".equalsIgnoreCase(type);
    }

    public boolean isMySQL() {
        return "mysql".equalsIgnoreCase(type);
    }
}
