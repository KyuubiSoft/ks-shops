package com.kyuubisoft.shops.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kyuubisoft.common.database.DatabaseProvider;
import com.kyuubisoft.common.database.SQLiteProvider;
import com.kyuubisoft.common.database.MySQLProvider;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.mailbox.MailboxEntry;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Database layer for the Shop mod.
 * Uses the reusable DatabaseProvider for SQLite/MySQL abstraction.
 */
public class ShopDatabase {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final Gson GSON = new GsonBuilder().create();

    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Type UUID_LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Type RATINGS_MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();

    private DatabaseProvider provider;

    // ==================== LIFECYCLE ====================

    /**
     * Initializes the database provider and creates tables.
     */
    public void init(Path dataDir, ShopConfig config) {
        ShopConfig.ConfigData data = config.getData();
        ShopConfig.Database dbConfig = data.database;

        try {
            if ("mysql".equalsIgnoreCase(dbConfig.type)) {
                ShopConfig.MysqlConfig mysql = dbConfig.mysql;
                provider = new MySQLProvider(
                    mysql.host, mysql.port, mysql.database,
                    mysql.username, mysql.password
                );
            } else {
                provider = new SQLiteProvider(dataDir.resolve(dbConfig.sqlitePath));
            }

            provider.initialize();
            createTables();

            LOGGER.info("Database initialized (" + dbConfig.type + ")");
        } catch (Exception e) {
            LOGGER.severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shuts down the database provider.
     */
    public void shutdown() {
        if (provider != null) {
            provider.shutdown();
            provider = null;
        }
    }

    // ==================== SCHEMA ====================

    private void createTables() throws SQLException {
        String shopsTable;
        String shopItemsTable;
        String transactionsTable;
        String ratingsTable;
        String notificationsTable;
        String mailboxTable;

        if (provider.isMySQL()) {
            shopsTable = "CREATE TABLE IF NOT EXISTS shop_shops (" +
                "id VARCHAR(36) PRIMARY KEY," +
                "name VARCHAR(64) NOT NULL," +
                "description VARCHAR(256) DEFAULT ''," +
                "type VARCHAR(16) NOT NULL," +
                "owner_uuid VARCHAR(36)," +
                "owner_name VARCHAR(64)," +
                "world_name VARCHAR(64) NOT NULL," +
                "pos_x DOUBLE NOT NULL," +
                "pos_y DOUBLE NOT NULL," +
                "pos_z DOUBLE NOT NULL," +
                "npc_rot_y FLOAT DEFAULT 0," +
                "npc_entity_id VARCHAR(128)," +
                "npc_skin_username VARCHAR(64)," +
                "icon_item_id VARCHAR(128)," +
                "category VARCHAR(64) DEFAULT ''," +
                "tags TEXT DEFAULT '[]'," +
                "average_rating DOUBLE DEFAULT 0," +
                "total_ratings INT DEFAULT 0," +
                "total_revenue DOUBLE DEFAULT 0," +
                "total_tax_paid DOUBLE DEFAULT 0," +
                "shop_balance DOUBLE DEFAULT 0," +
                "rent_paid_until BIGINT DEFAULT 0," +
                "rent_cost_per_cycle DOUBLE DEFAULT 0," +
                "rent_cycle_days INT DEFAULT 0," +
                "featured BOOLEAN DEFAULT FALSE," +
                "featured_until BIGINT DEFAULT 0," +
                "open BOOLEAN DEFAULT TRUE," +
                "show_name_tag BOOLEAN DEFAULT TRUE," +
                "packed BOOLEAN DEFAULT FALSE," +
                "listed_until BIGINT DEFAULT 0," +
                "created_at BIGINT NOT NULL," +
                "last_activity BIGINT NOT NULL" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

            shopItemsTable = "CREATE TABLE IF NOT EXISTS shop_items (" +
                "shop_id VARCHAR(36) NOT NULL," +
                "item_id VARCHAR(128) NOT NULL," +
                "buy_price INT DEFAULT 0," +
                "sell_price INT DEFAULT 0," +
                "stock INT DEFAULT -1," +
                "max_stock INT DEFAULT -1," +
                "buy_enabled BOOLEAN DEFAULT TRUE," +
                "sell_enabled BOOLEAN DEFAULT TRUE," +
                "slot INT DEFAULT 0," +
                "category VARCHAR(64) DEFAULT ''," +
                "daily_buy_limit INT DEFAULT 0," +
                "daily_sell_limit INT DEFAULT 0," +
                "item_metadata TEXT," +
                "PRIMARY KEY (shop_id, item_id)," +
                "INDEX idx_shop_id (shop_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

            transactionsTable = "CREATE TABLE IF NOT EXISTS shop_transactions (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "shop_id VARCHAR(36) NOT NULL," +
                "buyer_uuid VARCHAR(36) NOT NULL," +
                "buyer_name VARCHAR(64) NOT NULL," +
                "seller_uuid VARCHAR(36)," +
                "item_id VARCHAR(128) NOT NULL," +
                "quantity INT NOT NULL," +
                "price_per_unit INT NOT NULL," +
                "total_price INT NOT NULL," +
                "tax_amount INT DEFAULT 0," +
                "type VARCHAR(16) NOT NULL," +
                "timestamp BIGINT NOT NULL," +
                "INDEX idx_shop_id (shop_id)," +
                "INDEX idx_buyer (buyer_uuid)," +
                "INDEX idx_seller (seller_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

            ratingsTable = "CREATE TABLE IF NOT EXISTS shop_ratings (" +
                "rater_uuid VARCHAR(36) NOT NULL," +
                "shop_id VARCHAR(36) NOT NULL," +
                "rater_name VARCHAR(64) NOT NULL," +
                "stars INT NOT NULL," +
                "comment VARCHAR(256) DEFAULT ''," +
                "timestamp BIGINT NOT NULL," +
                "PRIMARY KEY (rater_uuid, shop_id)," +
                "INDEX idx_shop_id (shop_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

            notificationsTable = "CREATE TABLE IF NOT EXISTS shop_notifications (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "owner_uuid VARCHAR(36) NOT NULL," +
                "buyer_name VARCHAR(64) NOT NULL," +
                "item_id VARCHAR(128) NOT NULL," +
                "quantity INT NOT NULL," +
                "earned INT NOT NULL," +
                "timestamp BIGINT NOT NULL," +
                "read_flag BOOLEAN DEFAULT FALSE," +
                "INDEX idx_owner (owner_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

            mailboxTable = "CREATE TABLE IF NOT EXISTS shop_mailbox (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "owner_uuid VARCHAR(36) NOT NULL," +
                "mail_type VARCHAR(16) NOT NULL," +
                "item_id VARCHAR(128)," +
                "quantity INT DEFAULT 0," +
                "amount DOUBLE DEFAULT 0," +
                "from_shop_id VARCHAR(36)," +
                "from_shop_name VARCHAR(64)," +
                "from_player_name VARCHAR(64)," +
                "created_at BIGINT NOT NULL," +
                "claimed INT DEFAULT 0," +
                "item_metadata TEXT," +
                "INDEX idx_mailbox_owner (owner_uuid, claimed)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        } else {
            // SQLite
            shopsTable = "CREATE TABLE IF NOT EXISTS shop_shops (" +
                "id VARCHAR(36) PRIMARY KEY," +
                "name VARCHAR(64) NOT NULL," +
                "description VARCHAR(256) DEFAULT ''," +
                "type VARCHAR(16) NOT NULL," +
                "owner_uuid VARCHAR(36)," +
                "owner_name VARCHAR(64)," +
                "world_name VARCHAR(64) NOT NULL," +
                "pos_x DOUBLE NOT NULL," +
                "pos_y DOUBLE NOT NULL," +
                "pos_z DOUBLE NOT NULL," +
                "npc_rot_y FLOAT DEFAULT 0," +
                "npc_entity_id VARCHAR(128)," +
                "npc_skin_username VARCHAR(64)," +
                "icon_item_id TEXT," +
                "category VARCHAR(64) DEFAULT ''," +
                "tags TEXT DEFAULT '[]'," +
                "average_rating DOUBLE DEFAULT 0," +
                "total_ratings INT DEFAULT 0," +
                "total_revenue DOUBLE DEFAULT 0," +
                "total_tax_paid DOUBLE DEFAULT 0," +
                "shop_balance REAL DEFAULT 0," +
                "rent_paid_until BIGINT DEFAULT 0," +
                "rent_cost_per_cycle DOUBLE DEFAULT 0," +
                "rent_cycle_days INT DEFAULT 0," +
                "featured BOOLEAN DEFAULT 0," +
                "featured_until BIGINT DEFAULT 0," +
                "open BOOLEAN DEFAULT 1," +
                "show_name_tag BOOLEAN DEFAULT 1," +
                "packed BOOLEAN DEFAULT 0," +
                "listed_until BIGINT DEFAULT 0," +
                "created_at BIGINT NOT NULL," +
                "last_activity BIGINT NOT NULL" +
                ")";

            shopItemsTable = "CREATE TABLE IF NOT EXISTS shop_items (" +
                "shop_id VARCHAR(36) NOT NULL," +
                "item_id VARCHAR(128) NOT NULL," +
                "buy_price INT DEFAULT 0," +
                "sell_price INT DEFAULT 0," +
                "stock INT DEFAULT -1," +
                "max_stock INT DEFAULT -1," +
                "buy_enabled BOOLEAN DEFAULT 1," +
                "sell_enabled BOOLEAN DEFAULT 1," +
                "slot INT DEFAULT 0," +
                "category VARCHAR(64) DEFAULT ''," +
                "daily_buy_limit INT DEFAULT 0," +
                "daily_sell_limit INT DEFAULT 0," +
                "item_metadata TEXT," +
                "PRIMARY KEY (shop_id, item_id)" +
                ")";

            transactionsTable = "CREATE TABLE IF NOT EXISTS shop_transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "shop_id VARCHAR(36) NOT NULL," +
                "buyer_uuid VARCHAR(36) NOT NULL," +
                "buyer_name VARCHAR(64) NOT NULL," +
                "seller_uuid VARCHAR(36)," +
                "item_id VARCHAR(128) NOT NULL," +
                "quantity INT NOT NULL," +
                "price_per_unit INT NOT NULL," +
                "total_price INT NOT NULL," +
                "tax_amount INT DEFAULT 0," +
                "type VARCHAR(16) NOT NULL," +
                "timestamp BIGINT NOT NULL" +
                ")";

            ratingsTable = "CREATE TABLE IF NOT EXISTS shop_ratings (" +
                "rater_uuid VARCHAR(36) NOT NULL," +
                "shop_id VARCHAR(36) NOT NULL," +
                "rater_name VARCHAR(64) NOT NULL," +
                "stars INT NOT NULL," +
                "comment VARCHAR(256) DEFAULT ''," +
                "timestamp BIGINT NOT NULL," +
                "PRIMARY KEY (rater_uuid, shop_id)" +
                ")";

            notificationsTable = "CREATE TABLE IF NOT EXISTS shop_notifications (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "owner_uuid VARCHAR(36) NOT NULL," +
                "buyer_name VARCHAR(64) NOT NULL," +
                "item_id VARCHAR(128) NOT NULL," +
                "quantity INT NOT NULL," +
                "earned INT NOT NULL," +
                "timestamp BIGINT NOT NULL," +
                "read_flag BOOLEAN DEFAULT 0" +
                ")";

            mailboxTable = "CREATE TABLE IF NOT EXISTS shop_mailbox (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "owner_uuid TEXT NOT NULL," +
                "mail_type TEXT NOT NULL," +
                "item_id TEXT," +
                "quantity INTEGER DEFAULT 0," +
                "amount REAL DEFAULT 0," +
                "from_shop_id TEXT," +
                "from_shop_name TEXT," +
                "from_player_name TEXT," +
                "created_at INTEGER NOT NULL," +
                "claimed INTEGER DEFAULT 0," +
                "item_metadata TEXT" +
                ")";
        }

        provider.executeUpdate(shopsTable);
        provider.executeUpdate(shopItemsTable);
        provider.executeUpdate(transactionsTable);
        provider.executeUpdate(ratingsTable);
        provider.executeUpdate(notificationsTable);
        provider.executeUpdate(mailboxTable);

        // Index for mailbox lookups (SQLite needs explicit CREATE INDEX)
        try {
            provider.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_mailbox_owner ON shop_mailbox(owner_uuid, claimed)");
        } catch (SQLException ignored) {
            // MySQL already declared the index inline; ignore duplicate-key errors
        }

        // Migration: add shop_balance column if missing (existing databases)
        try {
            String alterSql = provider.isMySQL()
                ? "ALTER TABLE shop_shops ADD COLUMN shop_balance DOUBLE DEFAULT 0"
                : "ALTER TABLE shop_shops ADD COLUMN shop_balance REAL DEFAULT 0";
            provider.executeUpdate(alterSql);
            LOGGER.info("Migrated: added shop_balance column to shop_shops");
        } catch (SQLException ignored) {
            // Column already exists - expected after first migration
        }

        // Migration: add icon_item_id column if missing (existing databases)
        try {
            String alterSql = provider.isMySQL()
                ? "ALTER TABLE shop_shops ADD COLUMN icon_item_id VARCHAR(128)"
                : "ALTER TABLE shop_shops ADD COLUMN icon_item_id TEXT";
            provider.executeUpdate(alterSql);
            LOGGER.info("Migrated: added icon_item_id column to shop_shops");
        } catch (SQLException ignored) {
            // Column already exists - expected after first migration
        }

        // Migration: add show_name_tag column if missing (existing databases)
        try {
            String alterSql = provider.isMySQL()
                ? "ALTER TABLE shop_shops ADD COLUMN show_name_tag BOOLEAN DEFAULT TRUE"
                : "ALTER TABLE shop_shops ADD COLUMN show_name_tag BOOLEAN DEFAULT 1";
            provider.executeUpdate(alterSql);
            LOGGER.info("Migrated: added show_name_tag column to shop_shops");
        } catch (SQLException ignored) {
            // Column already exists - expected after first migration
        }

        // Migration: add packed column if missing (existing databases)
        try {
            String alterSql = provider.isMySQL()
                ? "ALTER TABLE shop_shops ADD COLUMN packed BOOLEAN DEFAULT FALSE"
                : "ALTER TABLE shop_shops ADD COLUMN packed BOOLEAN DEFAULT 0";
            provider.executeUpdate(alterSql);
            LOGGER.info("Migrated: added packed column to shop_shops");
        } catch (SQLException ignored) {
            // Column already exists - expected after first migration
        }

        // Migration: add listed_until column. Existing rows get
        // Long.MAX_VALUE (permanent listing) so shops that were already
        // visible in the directory before this feature landed stay
        // grandfathered in. New rows inserted by code override this via
        // the explicit listed_until in the INSERT statement.
        try {
            String alterSql = "ALTER TABLE shop_shops ADD COLUMN listed_until BIGINT DEFAULT "
                + Long.MAX_VALUE;
            provider.executeUpdate(alterSql);
            LOGGER.info("Migrated: added listed_until column to shop_shops "
                + "(existing shops grandfathered as permanent)");
        } catch (SQLException ignored) {
            // Column already exists - expected after first migration
        }

        // Migration: add item_metadata column to shop_items if missing.
        // Required for BSON ItemStack metadata capture (enchantments, pet ids,
        // weapon mastery levels etc.) on shop listings created before the
        // metadata pipeline was introduced.
        try {
            provider.executeUpdate("ALTER TABLE shop_items ADD COLUMN item_metadata TEXT");
            LOGGER.info("Migrated: added item_metadata column to shop_items");
        } catch (SQLException ignored) {
            // Column already exists - expected after first migration
        }

        // Migration: add item_metadata column to shop_mailbox if missing.
        // Required for BSON metadata on ITEM mails so enchanted purchases
        // survive the mailbox round-trip on their way to the buyer.
        try {
            provider.executeUpdate("ALTER TABLE shop_mailbox ADD COLUMN item_metadata TEXT");
            LOGGER.info("Migrated: added item_metadata column to shop_mailbox");
        } catch (SQLException ignored) {
            // Column already exists - expected after first migration
        }

        LOGGER.info("Database tables initialized");
    }

    // ==================== SHOP CRUD ====================

    public List<ShopData> loadAllShops() {
        List<ShopData> shops = new ArrayList<>();
        try {
            ResultSet rs = provider.executeQuery("SELECT * FROM shop_shops");
            try {
                while (rs.next()) {
                    ShopData shop = readShop(rs);
                    if (shop != null) {
                        // Load items for this shop
                        shop.getItems().addAll(loadShopItems(shop.getId()));
                        shops.add(shop);
                    }
                }
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load shops: " + e.getMessage());
        }
        return shops;
    }

    public void saveShop(ShopData shop) {
        String sql = provider.isMySQL()
            ? "REPLACE INTO shop_shops (id, name, description, type, owner_uuid, owner_name, " +
              "world_name, pos_x, pos_y, pos_z, npc_rot_y, npc_entity_id, npc_skin_username, icon_item_id, " +
              "category, tags, average_rating, total_ratings, total_revenue, total_tax_paid, " +
              "shop_balance, rent_paid_until, rent_cost_per_cycle, rent_cycle_days, featured, featured_until, " +
              "open, show_name_tag, packed, listed_until, created_at, last_activity) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            : "INSERT OR REPLACE INTO shop_shops (id, name, description, type, owner_uuid, owner_name, " +
              "world_name, pos_x, pos_y, pos_z, npc_rot_y, npc_entity_id, npc_skin_username, icon_item_id, " +
              "category, tags, average_rating, total_ratings, total_revenue, total_tax_paid, " +
              "shop_balance, rent_paid_until, rent_cost_per_cycle, rent_cycle_days, featured, featured_until, " +
              "open, show_name_tag, packed, listed_until, created_at, last_activity) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try {
            provider.executeUpdate(sql,
                shop.getId().toString(),
                shop.getName(),
                shop.getDescription(),
                shop.getType().name(),
                shop.getOwnerUuid() != null ? shop.getOwnerUuid().toString() : null,
                shop.getOwnerName(),
                shop.getWorldName(),
                shop.getPosX(),
                shop.getPosY(),
                shop.getPosZ(),
                shop.getNpcRotY(),
                shop.getNpcEntityId(),
                shop.getNpcSkinUsername(),
                shop.getIconItemId(),
                shop.getCategory(),
                GSON.toJson(shop.getTags()),
                shop.getAverageRating(),
                shop.getTotalRatings(),
                shop.getTotalRevenue(),
                shop.getTotalTaxPaid(),
                shop.getShopBalance(),
                shop.getRentPaidUntil(),
                shop.getRentCostPerCycle(),
                shop.getRentCycleDays(),
                shop.isFeatured(),
                shop.getFeaturedUntil(),
                shop.isOpen(),
                shop.isShowNameTag(),
                shop.isPacked(),
                shop.getListedUntil(),
                shop.getCreatedAt(),
                shop.getLastActivity()
            );

            // Save items
            saveShopItems(shop.getId(), shop.getItems());
        } catch (SQLException e) {
            LOGGER.warning("Failed to save shop " + shop.getId() + ": " + e.getMessage());
        }
    }

    public void deleteShop(UUID shopId) {
        try {
            provider.executeUpdate("DELETE FROM shop_items WHERE shop_id = ?", shopId.toString());
            provider.executeUpdate("DELETE FROM shop_ratings WHERE shop_id = ?", shopId.toString());
            provider.executeUpdate("DELETE FROM shop_transactions WHERE shop_id = ?", shopId.toString());
            provider.executeUpdate("DELETE FROM shop_shops WHERE id = ?", shopId.toString());
        } catch (SQLException e) {
            LOGGER.warning("Failed to delete shop " + shopId + ": " + e.getMessage());
        }
    }

    // ==================== SHOP ITEMS ====================

    public List<ShopItem> loadShopItems(UUID shopId) {
        List<ShopItem> items = new ArrayList<>();
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT * FROM shop_items WHERE shop_id = ? ORDER BY slot ASC",
                shopId.toString());
            try {
                while (rs.next()) {
                    ShopItem item = readShopItem(rs);
                    if (item != null) items.add(item);
                }
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load items for shop " + shopId + ": " + e.getMessage());
        }
        return items;
    }

    public void saveShopItems(UUID shopId, List<ShopItem> items) {
        try {
            // Delete existing items, then re-insert
            provider.executeUpdate("DELETE FROM shop_items WHERE shop_id = ?", shopId.toString());

            String sql = provider.isMySQL()
                ? "INSERT INTO shop_items (shop_id, item_id, buy_price, sell_price, stock, max_stock, " +
                  "buy_enabled, sell_enabled, slot, category, daily_buy_limit, daily_sell_limit, item_metadata) " +
                  "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT INTO shop_items (shop_id, item_id, buy_price, sell_price, stock, max_stock, " +
                  "buy_enabled, sell_enabled, slot, category, daily_buy_limit, daily_sell_limit, item_metadata) " +
                  "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";

            for (ShopItem item : items) {
                provider.executeUpdate(sql,
                    shopId.toString(),
                    item.getItemId(),
                    item.getBuyPrice(),
                    item.getSellPrice(),
                    item.getStock(),
                    item.getMaxStock(),
                    item.isBuyEnabled(),
                    item.isSellEnabled(),
                    item.getSlot(),
                    item.getCategory(),
                    item.getDailyBuyLimit(),
                    item.getDailySellLimit(),
                    item.getItemMetadata()
                );
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to save items for shop " + shopId + ": " + e.getMessage());
        }
    }

    // ==================== ATOMIC STOCK OPERATIONS ====================

    /**
     * Atomically decrements stock for a shop item in the database.
     * Uses a conditional UPDATE to prevent overselling:
     * only succeeds if current stock >= quantity (or stock is unlimited = -1).
     *
     * @return the number of rows affected (1 = success, 0 = out of stock or not found)
     */
    public int executeAtomicStockDecrement(UUID shopId, String itemId, int quantity) {
        String sql = "UPDATE shop_items SET stock = stock - ? " +
            "WHERE shop_id = ? AND item_id = ? AND (stock >= ? OR stock = -1)";
        try {
            return provider.executeUpdate(sql, quantity, shopId.toString(), itemId, quantity);
        } catch (SQLException e) {
            LOGGER.warning("Atomic stock decrement failed for shop " + shopId
                + " item " + itemId + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Atomically increments stock for a shop item in the database.
     * Respects maxStock: only increments if result would not exceed maxStock
     * (or maxStock is unlimited = -1).
     *
     * @return the number of rows affected (1 = success, 0 = at max stock or not found)
     */
    public int executeAtomicStockIncrement(UUID shopId, String itemId, int quantity) {
        String sql = "UPDATE shop_items SET stock = stock + ? " +
            "WHERE shop_id = ? AND item_id = ? AND stock != -1 " +
            "AND (max_stock = -1 OR stock + ? <= max_stock)";
        try {
            return provider.executeUpdate(sql, quantity, shopId.toString(), itemId, quantity);
        } catch (SQLException e) {
            LOGGER.warning("Atomic stock increment failed for shop " + shopId
                + " item " + itemId + ": " + e.getMessage());
            return 0;
        }
    }

    // ==================== TRANSACTIONS ====================

    public void logTransaction(UUID shopId, UUID buyerUuid, String buyerName,
                               UUID sellerUuid, String itemId, int quantity,
                               int pricePerUnit, int totalPrice, int taxAmount, String type) {
        String sql = provider.isMySQL()
            ? "INSERT INTO shop_transactions (shop_id, buyer_uuid, buyer_name, seller_uuid, " +
              "item_id, quantity, price_per_unit, total_price, tax_amount, type, timestamp) " +
              "VALUES (?,?,?,?,?,?,?,?,?,?,?)"
            : "INSERT INTO shop_transactions (shop_id, buyer_uuid, buyer_name, seller_uuid, " +
              "item_id, quantity, price_per_unit, total_price, tax_amount, type, timestamp) " +
              "VALUES (?,?,?,?,?,?,?,?,?,?,?)";

        try {
            provider.executeUpdate(sql,
                shopId.toString(),
                buyerUuid.toString(),
                buyerName,
                sellerUuid != null ? sellerUuid.toString() : null,
                itemId,
                quantity,
                pricePerUnit,
                totalPrice,
                taxAmount,
                type,
                System.currentTimeMillis()
            );
        } catch (SQLException e) {
            LOGGER.warning("Failed to log transaction: " + e.getMessage());
        }
    }

    // ==================== TRANSACTION HISTORY ====================

    /**
     * Loads recent transactions for a player (as buyer or seller).
     *
     * @param playerUuid the player's UUID
     * @param limit max number of transactions to return
     * @return list of TransactionRecord, newest first
     */
    public List<TransactionRecord> loadTransactions(UUID playerUuid, int limit) {
        List<TransactionRecord> transactions = new ArrayList<>();
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT * FROM shop_transactions " +
                "WHERE buyer_uuid = ? OR seller_uuid = ? " +
                "ORDER BY timestamp DESC LIMIT ?",
                playerUuid.toString(), playerUuid.toString(), limit);
            try {
                while (rs.next()) {
                    transactions.add(new TransactionRecord(
                        rs.getString("shop_id"),
                        rs.getString("buyer_uuid"),
                        rs.getString("buyer_name"),
                        rs.getString("seller_uuid"),
                        rs.getString("item_id"),
                        rs.getInt("quantity"),
                        rs.getInt("price_per_unit"),
                        rs.getInt("total_price"),
                        rs.getInt("tax_amount"),
                        rs.getString("type"),
                        rs.getLong("timestamp")
                    ));
                }
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load transactions for " + playerUuid + ": " + e.getMessage());
        }
        return transactions;
    }

    /**
     * Sums total_price of all BUY transactions for a shop since the given timestamp.
     * Used for per-period revenue charts (today, this week).
     */
    public double getRevenueForShopSince(UUID shopId, long sinceTimestamp) {
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT COALESCE(SUM(total_price), 0) AS total FROM shop_transactions " +
                "WHERE shop_id = ? AND type = 'BUY' AND timestamp >= ?",
                shopId.toString(), sinceTimestamp);
            try {
                if (rs.next()) return rs.getDouble("total");
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to sum revenue for shop " + shopId + ": " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * Loads transactions for a specific shop, paginated. Newest first.
     */
    public List<TransactionRecord> loadTransactionsForShop(UUID shopId, int offset, int limit) {
        List<TransactionRecord> transactions = new ArrayList<>();
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT * FROM shop_transactions " +
                "WHERE shop_id = ? " +
                "ORDER BY timestamp DESC LIMIT ? OFFSET ?",
                shopId.toString(), limit, offset);
            try {
                while (rs.next()) {
                    transactions.add(new TransactionRecord(
                        rs.getString("shop_id"),
                        rs.getString("buyer_uuid"),
                        rs.getString("buyer_name"),
                        rs.getString("seller_uuid"),
                        rs.getString("item_id"),
                        rs.getInt("quantity"),
                        rs.getInt("price_per_unit"),
                        rs.getInt("total_price"),
                        rs.getInt("tax_amount"),
                        rs.getString("type"),
                        rs.getLong("timestamp")
                    ));
                }
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load tx for shop " + shopId + ": " + e.getMessage());
        }
        return transactions;
    }

    /**
     * Counts total transactions for a shop. Used for pagination.
     */
    public int countTransactionsForShop(UUID shopId) {
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT COUNT(*) AS cnt FROM shop_transactions WHERE shop_id = ?",
                shopId.toString());
            try {
                if (rs.next()) return rs.getInt("cnt");
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to count tx for shop " + shopId + ": " + e.getMessage());
        }
        return 0;
    }

    /**
     * Counts total completed sales where the given player was the seller.
     * A "sale" is a BUY transaction (customer buying from the shop) logged
     * with {@code seller_uuid = ownerUuid}. Used by the player stats command
     * to show how many items the shop owner has sold across all their shops.
     */
    public int countSalesForOwner(UUID ownerUuid) {
        if (ownerUuid == null) return 0;
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT COUNT(*) AS cnt FROM shop_transactions WHERE seller_uuid = ? AND type = 'BUY'",
                ownerUuid.toString());
            try {
                if (rs.next()) return rs.getInt("cnt");
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to count sales for " + ownerUuid + ": " + e.getMessage());
        }
        return 0;
    }

    // ==================== PLAYER DATA ====================

    public PlayerShopData loadPlayerData(UUID playerUuid) {
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT * FROM shop_shops WHERE owner_uuid = ?", playerUuid.toString());
            try {
                // Build player data from owned shops
                List<UUID> ownedShopIds = new ArrayList<>();
                while (rs.next()) {
                    String id = rs.getString("id");
                    if (id != null) {
                        try { ownedShopIds.add(UUID.fromString(id)); }
                        catch (IllegalArgumentException ignored) {}
                    }
                }
                // For full player data, we'd need a separate players table.
                // For now, return null and let the caller create a new PlayerShopData.
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load player data for " + playerUuid + ": " + e.getMessage());
        }
        return null;
    }

    public void savePlayerData(PlayerShopData data) {
        // Player data is derived from shops — no separate table needed currently.
        // The dirty flag is cleared by the caller after this returns.
    }

    // ==================== RATINGS ====================

    public List<ShopRating> loadRatings(UUID shopId) {
        List<ShopRating> ratings = new ArrayList<>();
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT * FROM shop_ratings WHERE shop_id = ? ORDER BY timestamp DESC",
                shopId.toString());
            try {
                while (rs.next()) {
                    ratings.add(new ShopRating(
                        UUID.fromString(rs.getString("rater_uuid")),
                        rs.getString("rater_name"),
                        UUID.fromString(rs.getString("shop_id")),
                        rs.getInt("stars"),
                        rs.getString("comment"),
                        rs.getLong("timestamp")
                    ));
                }
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load ratings for shop " + shopId + ": " + e.getMessage());
        }
        return ratings;
    }

    public void saveRating(ShopRating rating) {
        String sql = provider.isMySQL()
            ? "REPLACE INTO shop_ratings (rater_uuid, shop_id, rater_name, stars, comment, timestamp) " +
              "VALUES (?,?,?,?,?,?)"
            : "INSERT OR REPLACE INTO shop_ratings (rater_uuid, shop_id, rater_name, stars, comment, timestamp) " +
              "VALUES (?,?,?,?,?,?)";

        try {
            provider.executeUpdate(sql,
                rating.getRaterUuid().toString(),
                rating.getShopId().toString(),
                rating.getRaterName(),
                rating.getStars(),
                rating.getComment(),
                rating.getTimestamp()
            );
        } catch (SQLException e) {
            LOGGER.warning("Failed to save rating: " + e.getMessage());
        }
    }

    // ==================== NOTIFICATIONS ====================

    public List<SaleNotification> loadNotifications(UUID playerUuid) {
        List<SaleNotification> notifications = new ArrayList<>();
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT * FROM shop_notifications WHERE owner_uuid = ? AND read_flag = ? ORDER BY timestamp DESC",
                playerUuid.toString(), false);
            try {
                while (rs.next()) {
                    notifications.add(new SaleNotification(
                        rs.getString("buyer_name"),
                        rs.getString("item_id"),
                        rs.getInt("quantity"),
                        rs.getInt("earned"),
                        rs.getLong("timestamp")
                    ));
                }
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load notifications for " + playerUuid + ": " + e.getMessage());
        }
        return notifications;
    }

    public void addNotification(UUID ownerUuid, String buyerName, String itemId,
                                int quantity, int earned) {
        String sql = provider.isMySQL()
            ? "INSERT INTO shop_notifications (owner_uuid, buyer_name, item_id, quantity, earned, timestamp) " +
              "VALUES (?,?,?,?,?,?)"
            : "INSERT INTO shop_notifications (owner_uuid, buyer_name, item_id, quantity, earned, timestamp) " +
              "VALUES (?,?,?,?,?,?)";

        try {
            provider.executeUpdate(sql,
                ownerUuid.toString(),
                buyerName,
                itemId,
                quantity,
                earned,
                System.currentTimeMillis()
            );
        } catch (SQLException e) {
            LOGGER.warning("Failed to add notification: " + e.getMessage());
        }
    }

    public void markNotificationsRead(UUID playerUuid) {
        try {
            provider.executeUpdate(
                "UPDATE shop_notifications SET read_flag = ? WHERE owner_uuid = ?",
                true, playerUuid.toString()
            );
        } catch (SQLException e) {
            LOGGER.warning("Failed to mark notifications read for " + playerUuid + ": " + e.getMessage());
        }
    }

    // ==================== MAILBOX ====================

    public void insertMailboxEntry(MailboxEntry entry) {
        String sql = "INSERT INTO shop_mailbox (owner_uuid, mail_type, item_id, quantity, amount, " +
            "from_shop_id, from_shop_name, from_player_name, created_at, claimed, item_metadata) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try {
            int rows = provider.executeUpdate(sql,
                entry.getOwnerUuid().toString(),
                entry.getType().name(),
                entry.getItemId(),
                entry.getQuantity(),
                entry.getAmount(),
                entry.getFromShopId() != null ? entry.getFromShopId().toString() : null,
                entry.getFromShopName(),
                entry.getFromPlayerName(),
                entry.getCreatedAt(),
                entry.isClaimed() ? 1 : 0,
                entry.getItemMetadata());
            if (rows > 0) {
                long lastId = fetchLastInsertId();
                entry.setId(lastId);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to insert mailbox entry: " + e.getMessage());
        }
    }

    /**
     * Helper: returns the last auto-increment ID from the provider.
     * SQLite uses SELECT last_insert_rowid(); MySQL uses SELECT LAST_INSERT_ID().
     * Note: on MySQL this only works if the provider reuses the same Connection
     * as the prior INSERT. Since our pooled provider does NOT guarantee this,
     * MySQL callers should expect the returned ID may be 0 if the connection
     * was returned to the pool; treat the in-memory ID as best-effort until a
     * subsequent reload from the database.
     */
    private long fetchLastInsertId() {
        String sql = provider.isMySQL() ? "SELECT LAST_INSERT_ID()" : "SELECT last_insert_rowid()";
        try {
            ResultSet rs = provider.executeQuery(sql);
            try {
                if (rs.next()) return rs.getLong(1);
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to fetch last insert id: " + e.getMessage());
        }
        return 0L;
    }

    public List<MailboxEntry> loadMailboxForPlayer(UUID ownerUuid, boolean includeClaimed) {
        List<MailboxEntry> mails = new ArrayList<>();
        String sql = includeClaimed
            ? "SELECT * FROM shop_mailbox WHERE owner_uuid = ? ORDER BY created_at DESC"
            : "SELECT * FROM shop_mailbox WHERE owner_uuid = ? AND claimed = 0 ORDER BY created_at DESC";
        try {
            ResultSet rs = provider.executeQuery(sql, ownerUuid.toString());
            try {
                while (rs.next()) {
                    mails.add(readMailboxEntry(rs));
                }
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load mailbox for " + ownerUuid + ": " + e.getMessage());
        }
        return mails;
    }

    public int countUnclaimedMailsForPlayer(UUID ownerUuid) {
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT COUNT(*) AS cnt FROM shop_mailbox WHERE owner_uuid = ? AND claimed = 0",
                ownerUuid.toString());
            try {
                if (rs.next()) return rs.getInt("cnt");
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to count unclaimed mails for " + ownerUuid + ": " + e.getMessage());
        }
        return 0;
    }

    public MailboxEntry loadMail(long mailId) {
        try {
            ResultSet rs = provider.executeQuery(
                "SELECT * FROM shop_mailbox WHERE id = ?", mailId);
            try {
                if (rs.next()) return readMailboxEntry(rs);
            } finally {
                closeResultSet(rs);
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to load mail " + mailId + ": " + e.getMessage());
        }
        return null;
    }

    public boolean markMailClaimed(long mailId) {
        try {
            int rows = provider.executeUpdate(
                "UPDATE shop_mailbox SET claimed = 1 WHERE id = ? AND claimed = 0", mailId);
            return rows > 0;
        } catch (SQLException e) {
            LOGGER.warning("Failed to mark mail claimed " + mailId + ": " + e.getMessage());
            return false;
        }
    }

    private MailboxEntry readMailboxEntry(ResultSet rs) throws SQLException {
        UUID ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
        MailboxEntry.Type type;
        try { type = MailboxEntry.Type.valueOf(rs.getString("mail_type")); }
        catch (Exception e) { type = MailboxEntry.Type.MONEY; }
        String itemId = rs.getString("item_id");
        int quantity = rs.getInt("quantity");
        double amount = rs.getDouble("amount");
        String fromShopIdStr = rs.getString("from_shop_id");
        UUID fromShopId = null;
        if (fromShopIdStr != null) {
            try { fromShopId = UUID.fromString(fromShopIdStr); }
            catch (IllegalArgumentException ignored) {}
        }
        String fromShopName = rs.getString("from_shop_name");
        String fromPlayerName = rs.getString("from_player_name");
        long createdAt = rs.getLong("created_at");
        boolean claimed = rs.getInt("claimed") != 0;

        // Read item_metadata safely (column may not exist on legacy rows).
        // Legacy mails simply get null metadata and deliver a vanilla item.
        String itemMetadata = null;
        try { itemMetadata = rs.getString("item_metadata"); } catch (SQLException ignored) {}

        return MailboxEntry.fromDatabase(rs.getLong("id"), ownerUuid, type, itemId,
            quantity, amount, fromShopId, fromShopName, fromPlayerName, createdAt,
            itemMetadata, claimed);
    }

    // ==================== INNER CLASSES ====================

    /**
     * Lightweight transaction record for history display.
     */
    public static class TransactionRecord {
        public final String shopId;
        public final String buyerUuid;
        public final String buyerName;
        public final String sellerUuid;
        public final String itemId;
        public final int quantity;
        public final int pricePerUnit;
        public final int totalPrice;
        public final int taxAmount;
        public final String type;
        public final long timestamp;

        public TransactionRecord(String shopId, String buyerUuid, String buyerName,
                                 String sellerUuid, String itemId, int quantity,
                                 int pricePerUnit, int totalPrice, int taxAmount,
                                 String type, long timestamp) {
            this.shopId = shopId;
            this.buyerUuid = buyerUuid;
            this.buyerName = buyerName;
            this.sellerUuid = sellerUuid;
            this.itemId = itemId;
            this.quantity = quantity;
            this.pricePerUnit = pricePerUnit;
            this.totalPrice = totalPrice;
            this.taxAmount = taxAmount;
            this.type = type;
            this.timestamp = timestamp;
        }
    }

    /**
     * Lightweight notification record for offline sale alerts.
     */
    public static class SaleNotification {
        public final String buyerName;
        public final String itemId;
        public final int quantity;
        public final int earned;
        public final long timestamp;

        public SaleNotification(String buyerName, String itemId, int quantity, int earned, long timestamp) {
            this.buyerName = buyerName;
            this.itemId = itemId;
            this.quantity = quantity;
            this.earned = earned;
            this.timestamp = timestamp;
        }
    }

    // ==================== INTERNAL HELPERS ====================

    private ShopData readShop(ResultSet rs) {
        try {
            String typeStr = rs.getString("type");
            ShopType type;
            try { type = ShopType.valueOf(typeStr); }
            catch (IllegalArgumentException e) { type = ShopType.PLAYER; }

            String ownerUuidStr = rs.getString("owner_uuid");
            UUID ownerUuid = null;
            if (ownerUuidStr != null && !ownerUuidStr.isEmpty()) {
                try { ownerUuid = UUID.fromString(ownerUuidStr); }
                catch (IllegalArgumentException ignored) {}
            }

            String tagsJson = rs.getString("tags");
            List<String> tags = new ArrayList<>();
            if (tagsJson != null && !tagsJson.isEmpty()) {
                try {
                    List<String> parsed = GSON.fromJson(tagsJson, STRING_LIST_TYPE);
                    if (parsed != null) tags = parsed;
                } catch (Exception ignored) {}
            }

            // Read shop_balance safely (column may not exist in legacy DBs)
            double shopBalance = 0;
            try { shopBalance = rs.getDouble("shop_balance"); } catch (SQLException ignored) {}

            // Read icon_item_id safely (column may not exist in legacy DBs)
            String iconItemId = null;
            try { iconItemId = rs.getString("icon_item_id"); } catch (SQLException ignored) {}

            // Read show_name_tag safely (column may not exist in legacy DBs)
            boolean showNameTag = true;
            try { showNameTag = rs.getBoolean("show_name_tag"); } catch (SQLException ignored) {}

            // Read packed safely (column may not exist in legacy DBs)
            boolean packed = false;
            try { packed = rs.getBoolean("packed"); } catch (SQLException ignored) {}

            // Read listed_until safely. Default to Long.MAX_VALUE so shops
            // from DBs that never had the column get treated as permanently
            // listed (grandfathering matches the migration default).
            long listedUntil = Long.MAX_VALUE;
            try { listedUntil = rs.getLong("listed_until"); } catch (SQLException ignored) {}

            ShopData shop = ShopData.fromDatabase(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("description"),
                type,
                ownerUuid,
                rs.getString("owner_name"),
                rs.getString("world_name"),
                rs.getDouble("pos_x"),
                rs.getDouble("pos_y"),
                rs.getDouble("pos_z"),
                rs.getFloat("npc_rot_y"),
                rs.getString("npc_entity_id"),
                rs.getString("npc_skin_username"),
                iconItemId,
                new ArrayList<>(), // items loaded separately
                rs.getString("category"),
                tags,
                rs.getDouble("average_rating"),
                rs.getInt("total_ratings"),
                rs.getDouble("total_revenue"),
                rs.getDouble("total_tax_paid"),
                rs.getLong("rent_paid_until"),
                rs.getDouble("rent_cost_per_cycle"),
                rs.getInt("rent_cycle_days"),
                rs.getBoolean("featured"),
                rs.getLong("featured_until"),
                rs.getBoolean("open"),
                rs.getLong("created_at"),
                rs.getLong("last_activity")
            );
            shop.setShopBalance(shopBalance);
            shop.setShowNameTag(showNameTag);
            shop.setPacked(packed);
            shop.setListedUntil(listedUntil);
            shop.clearDirty();  // just loaded from DB, no pending changes
            return shop;
        } catch (Exception e) {
            LOGGER.warning("Failed to read shop row: " + e.getMessage());
            return null;
        }
    }

    private ShopItem readShopItem(ResultSet rs) {
        try {
            // Read item_metadata safely (column may not exist if migration
            // has not run yet against a very old DB snapshot).
            String itemMetadata = null;
            try { itemMetadata = rs.getString("item_metadata"); } catch (SQLException ignored) {}

            return new ShopItem(
                rs.getString("item_id"),
                rs.getInt("buy_price"),
                rs.getInt("sell_price"),
                rs.getInt("stock"),
                rs.getInt("max_stock"),
                rs.getBoolean("buy_enabled"),
                rs.getBoolean("sell_enabled"),
                rs.getInt("slot"),
                rs.getString("category"),
                rs.getInt("daily_buy_limit"),
                rs.getInt("daily_sell_limit"),
                itemMetadata
            );
        } catch (Exception e) {
            LOGGER.warning("Failed to read shop item row: " + e.getMessage());
            return null;
        }
    }

    /**
     * Close a ResultSet and its underlying Statement.
     * For MySQL (pooled connections), also closes the connection to return it to the pool.
     */
    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                var stmt = rs.getStatement();
                rs.close();
                if (stmt != null) {
                    var conn = stmt.getConnection();
                    stmt.close();
                    if (provider.isMySQL() && conn != null) {
                        conn.close();
                    }
                }
            }
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
    }
}
