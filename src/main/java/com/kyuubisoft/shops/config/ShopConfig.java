package com.kyuubisoft.shops.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kyuubisoft.shops.bridge.CoreBridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Configuration for the Shop System.
 * Loaded from config.json (file-first, JAR-fallback).
 * Tries SmartConfigManager (Core) for merge-based loading, then falls back to plain GSON.
 */
public class ShopConfig {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataFolder;
    private ConfigData data;

    public ShopConfig(Path dataFolder) {
        this.dataFolder = dataFolder;
        this.data = new ConfigData();
    }

    // ==================== LOADING ====================

    public void load() {
        if (dataFolder == null) {
            LOGGER.warning("Data folder not set, using defaults");
            return;
        }

        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            LOGGER.warning("Failed to create data folder: " + e.getMessage());
        }

        Path configFile = dataFolder.resolve("config.json");

        // Try SmartConfigManager (Core) for merge-based loading
        boolean loadedViaSmart = false;
        try {
            Class<?> scm = Class.forName("com.kyuubisoft.core.util.SmartConfigManager");
            var method = scm.getMethod("loadAndMerge",
                Path.class, Class.class, Object.class, Gson.class);
            data = (ConfigData) method.invoke(null,
                configFile, ConfigData.class, new ConfigData(), GSON);
            if (data == null) data = new ConfigData();
            loadedViaSmart = true;
            if (CoreBridge.isDebug()) LOGGER.info("Config loaded via SmartConfigManager");
        } catch (ClassNotFoundException e) {
            // Core not available — fallback
        } catch (Exception e) {
            LOGGER.warning("SmartConfigManager failed: " + e.getMessage());
        }

        if (!loadedViaSmart) {
            if (Files.exists(configFile)) {
                try {
                    String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
                    data = GSON.fromJson(content, ConfigData.class);
                    if (data == null) data = new ConfigData();
                    if (CoreBridge.isDebug()) LOGGER.info("Config loaded from file");
                } catch (Exception e) {
                    LOGGER.warning("Failed to load config.json: " + e.getMessage());
                    data = new ConfigData();
                }
            } else {
                extractDefault(configFile);
            }
        }

        // Validate
        data.validate();

        LOGGER.info("Config loaded: playerShops=" + data.features.playerShops
            + ", adminShops=" + data.features.adminShops
            + ", tax=" + data.tax.enabled
            + ", rent=" + data.rent.enabled
            + ", categories=" + data.categories.size());
    }

    public void reload() {
        load();
    }

    public void save() {
        try {
            Path configFile = dataFolder.resolve("config.json");
            Files.createDirectories(configFile.getParent());
            String json = GSON.toJson(data);
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
            LOGGER.info("Config saved to " + configFile);
        } catch (Exception e) {
            LOGGER.warning("Failed to save config: " + e.getMessage());
        }
    }

    // ==================== GETTERS ====================

    public ConfigData getData() { return data; }
    public Path getDataFolder() { return dataFolder; }

    // ==================== EXTRACTION ====================

    private void extractDefault(Path targetPath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("defaults/config.json")) {
            if (is == null) {
                LOGGER.warning("Default config.json not found in JAR, using built-in defaults");
                data = new ConfigData();
                save();
                return;
            }
            Files.createDirectories(targetPath.getParent());
            Files.copy(is, targetPath);
            if (CoreBridge.isDebug()) LOGGER.info("Extracted default config.json");

            String content = new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8);
            data = GSON.fromJson(content, ConfigData.class);
            if (data == null) data = new ConfigData();
        } catch (IOException e) {
            LOGGER.warning("Failed to extract default config: " + e.getMessage());
            data = new ConfigData();
        }
    }

    // ==================== CONFIG DATA ====================

    public static class ConfigData {
        public Features features = new Features();
        public PlayerShops playerShops = new PlayerShops();
        public AdminShops adminShops = new AdminShops();
        public Economy economy = new Economy();
        public Tax tax = new Tax();
        public Rent rent = new Rent();
        public Ratings ratings = new Ratings();
        public Notifications notifications = new Notifications();
        public Npc npc = new Npc();
        public Directory directory = new Directory();
        public Featured featured = new Featured();
        public Database database = new Database();
        public List<String> itemBlacklist = new ArrayList<>();
        public List<CategoryDef> categories = new ArrayList<>();

        public void validate() {
            if (features == null) features = new Features();
            if (playerShops == null) playerShops = new PlayerShops();
            if (adminShops == null) adminShops = new AdminShops();
            if (economy == null) economy = new Economy();
            if (tax == null) tax = new Tax();
            if (rent == null) rent = new Rent();
            if (ratings == null) ratings = new Ratings();
            if (notifications == null) notifications = new Notifications();
            if (npc == null) npc = new Npc();
            if (directory == null) directory = new Directory();
            if (featured == null) featured = new Featured();
            if (database == null) database = new Database();
            if (itemBlacklist == null) itemBlacklist = new ArrayList<>();
            if (categories == null) categories = new ArrayList<>();

            // Clamp values
            if (playerShops.maxShopsPerPlayer < 1) playerShops.maxShopsPerPlayer = 1;
            if (playerShops.maxItemsPerShop < 1) playerShops.maxItemsPerShop = 9;
            if (playerShops.maxItemsPerShop > 45) playerShops.maxItemsPerShop = 45;
            if (tax.buyTaxPercent < 0) tax.buyTaxPercent = 0;
            if (tax.sellTaxPercent < 0) tax.sellTaxPercent = 0;
            if (ratings.minStars < 1) ratings.minStars = 1;
            if (ratings.maxStars < 1) ratings.maxStars = 5;
            if (ratings.maxStars > 10) ratings.maxStars = 10;
        }
    }

    // ==================== INNER CONFIG CLASSES ====================

    public static class Features {
        public boolean playerShops = true;
        public boolean adminShops = true;
        public boolean directory = true;
        public boolean ratings = true;
        public boolean notifications = true;
        public boolean shopOfTheWeek = false;
        public boolean rentSystem = false;
        public boolean claimsIntegration = true;
    }

    public static class PlayerShops {
        public int maxShopsPerPlayer = 3;
        // Matches the 9x5 = 45 native ItemGrid in ShopBrowsePage so a fully
        // stocked shop can fill its entire display grid in one page.
        public int maxItemsPerShop = 45;
        public int creationCost = 500;
        public boolean requireClaim = false;
        public boolean allowNpcCustomization = true;
        public int nameMinLength = 3;
        public int nameMaxLength = 24;
        public int descriptionMaxLength = 100;
        public int maxTagsPerShop = 5;

        // --- Directory listing (paid) ---
        // When enabled, new player shops are NOT automatically listed in
        // the public directory. Owners must either buy a listing via
        // /ksshop list <days> (price = listingPricePerDay * days) or have
        // one of the permission shortcuts (see PermissionLimits below).
        // Admin shops are never affected by this - they are always listed.
        public boolean listingEnabled = true;
        public int listingPricePerDay = 100;
        public int listingMinDays = 1;
        public int listingMaxDays = 30;
        // Free listing days granted on shop creation (0 = off).
        public int listingFreeDaysOnCreate = 7;
    }

    public static class AdminShops {
        public boolean unlimitedStock = true;
        public boolean allowBuyAndSell = true;
        public String defaultNpcSkin = "";
    }

    public static class Economy {
        public String provider = "auto";
        public String currencyName = "Gold";
        public String currencySymbol = "";
        public int maxPrice = 1_000_000;
        public int minPrice = 1;
    }

    public static class Tax {
        public boolean enabled = false;
        public double buyTaxPercent = 5.0;
        public double sellTaxPercent = 5.0;
        public boolean showTaxInPrice = true;
        public String taxRecipient = "server";
    }

    public static class Rent {
        public boolean enabled = false;
        public double costPerDay = 50.0;
        public int gracePeriodDays = 3;
        public boolean autoCloseOnExpire = true;
        public int autoDeleteOnUnpaidDays = 7;
        public int rentExemptForNewShopsDays = 7;
        public int rentNotificationBeforeDays = 1;
        public int maxCycleDays = 30;
    }

    public static class Ratings {
        public boolean enabled = true;
        public int minStars = 1;
        public int maxStars = 5;
        public boolean allowComments = true;
        public int commentMaxLength = 100;
        public int cooldownMinutes = 60;
    }

    public static class Notifications {
        public boolean enabled = true;
        public boolean batchOnLogin = true;
        public int maxStoredPerPlayer = 50;
        public boolean soundEnabled = true;
    }

    public static class Npc {
        public String defaultEntityId = "NPC_Shopkeeper";
        public String defaultSkinUsername = "";
        public boolean lookAtPlayer = true;
        public float interactionRange = 5.0f;
        public boolean showNameplate = true;
    }

    public static class Directory {
        public int shopsPerPage = 9;
        public String defaultSortBy = "rating";
        public boolean showEmptyShops = false;
        public boolean showClosedShops = false;
    }

    public static class Featured {
        public boolean enabled = false;
        public int cost = 1000;
        public int durationDays = 7;
        public int maxFeaturedSlots = 3;
        public boolean autoFeatureTopRated = false;
        public double autoFeatureMinRating = 4.5;
        public int autoFeatureMinSales = 50;
    }

    public static class Database {
        public String type = "sqlite";
        public String sqlitePath = "shops.db";
        public MysqlConfig mysql = new MysqlConfig();
    }

    public static class MysqlConfig {
        public String host = "localhost";
        public int port = 3306;
        public String database = "hytale";
        public String username = "root";
        public String password = "";
    }

    public static class CategoryDef {
        public String id = "";
        public String displayName = "";
        public String icon = "";
        public int sortOrder = 0;

        public CategoryDef() {}

        public CategoryDef(String id, String displayName, String icon, int sortOrder) {
            this.id = id;
            this.displayName = displayName;
            this.icon = icon;
            this.sortOrder = sortOrder;
        }
    }
}
