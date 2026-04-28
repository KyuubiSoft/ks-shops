package com.kyuubisoft.shops.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.AdminShopDefinition;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.ShopItem;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.event.ShopCreateEvent;
import com.kyuubisoft.shops.event.ShopDeleteEvent;
import com.kyuubisoft.shops.event.ShopEventBus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages all shops in memory. Provides cache-first access with
 * dirty-tracking persistence to the underlying ShopDatabase.
 */
public class ShopManager {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final Gson GSON = new GsonBuilder().create();

    private final ConcurrentHashMap<UUID, ShopData> shops = new ConcurrentHashMap<>();
    private final ShopDatabase database;
    private final ShopConfig config;

    public ShopManager(ShopDatabase database, ShopConfig config) {
        this.database = database;
        this.config = config;
    }

    /**
     * Loads all shops from DB into the in-memory cache,
     * then loads admin shop definitions from JSON files.
     */
    public void loadAll() {
        LOGGER.info("Loading all shops from database...");
        shops.clear();

        try {
            List<ShopData> loaded = database.loadAllShops();
            for (ShopData shop : loaded) {
                shops.put(shop.getId(), shop);
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to load shops from database: " + e.getMessage());
        }

        // Load admin shop definitions from JSON files
        loadAdminShops();

        LOGGER.info("Loaded " + shops.size() + " shops (" +
            shops.values().stream().filter(ShopData::isAdminShop).count() + " admin, " +
            shops.values().stream().filter(ShopData::isPlayerShop).count() + " player)");
    }

    /**
     * Loads admin shop definitions from {@code data/admin-shops/} directory.
     * <p>
     * For each JSON definition:
     * <ul>
     *   <li>If no shop with that ID exists yet, creates a new ShopData and saves to DB.</li>
     *   <li>If a shop already exists, updates its items from the definition (admin shops are template-driven).</li>
     * </ul>
     */
    private void loadAdminShops() {
        Path adminShopsDir = config.getDataFolder().resolve("admin-shops");
        if (!Files.isDirectory(adminShopsDir)) {
            LOGGER.info("No admin-shops directory found at " + adminShopsDir + ", skipping admin shop loading");
            return;
        }

        int created = 0;
        int updated = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(adminShopsDir, "*.json")) {
            for (Path file : stream) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    AdminShopDefinition def = GSON.fromJson(json, AdminShopDefinition.class);
                    if (def == null || def.getId() == null || def.getId().isEmpty()) {
                        LOGGER.warning("Skipping invalid admin shop file: " + file.getFileName());
                        continue;
                    }

                    UUID shopId;
                    try {
                        shopId = UUID.fromString(def.getId());
                    } catch (IllegalArgumentException e) {
                        // Generate deterministic UUID from the string ID
                        shopId = UUID.nameUUIDFromBytes(def.getId().getBytes(StandardCharsets.UTF_8));
                    }

                    List<ShopItem> defItems = convertAdminItems(def);
                    ShopData existing = shops.get(shopId);

                    if (existing == null) {
                        // Create new admin shop from definition
                        ShopData shop = ShopData.fromDatabase(
                            shopId,
                            def.getName() != null ? def.getName() : "Admin Shop",
                            def.getDescription() != null ? def.getDescription() : "",
                            ShopType.ADMIN,
                            null, // No owner for admin shops
                            null,
                            def.getWorldName() != null ? def.getWorldName() : "world",
                            def.getPosX(), def.getPosY(), def.getPosZ(),
                            def.getNpcRotY(),
                            null, // npcEntityId
                            def.getNpcSkinUsername(),
                            null, // iconItemId (admin shops fall back to first item)
                            defItems,
                            def.getCategory() != null ? def.getCategory() : "",
                            new ArrayList<>(), // tags
                            0, 0,   // rating
                            0, 0,   // revenue/tax
                            0, 0, 0, // rent
                            false, 0, // featured
                            true,    // open
                            System.currentTimeMillis(),
                            System.currentTimeMillis()
                        );

                        shops.put(shopId, shop);
                        database.saveShop(shop);
                        created++;
                    } else {
                        // Update existing admin shop items from template
                        existing.getItems().clear();
                        existing.getItems().addAll(defItems);
                        // Update name/description in case definition changed
                        if (def.getName() != null) existing.setName(def.getName());
                        if (def.getDescription() != null) existing.setDescription(def.getDescription());
                        if (def.getCategory() != null) existing.setCategory(def.getCategory());
                        if (def.getNpcSkinUsername() != null) existing.setNpcSkinUsername(def.getNpcSkinUsername());

                        database.saveShopItems(shopId, existing.getItems());
                        existing.clearDirty(); // Reset dirty since we just synced from template
                        updated++;
                    }
                } catch (Exception e) {
                    LOGGER.warning("Failed to load admin shop file " + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to read admin-shops directory: " + e.getMessage());
        }

        if (created > 0 || updated > 0) {
            LOGGER.info("Admin shops: " + created + " created, " + updated + " updated from definitions");
        }
    }

    /**
     * Converts admin shop definition items to ShopItem list.
     */
    private List<ShopItem> convertAdminItems(AdminShopDefinition def) {
        List<ShopItem> items = new CopyOnWriteArrayList<>();
        if (def.getItems() == null) return items;

        boolean unlimitedStock = config.getData().adminShops.unlimitedStock;

        int slot = 0;
        for (AdminShopDefinition.AdminShopItemConfig itemCfg : def.getItems()) {
            if (itemCfg.getItemId() == null || itemCfg.getItemId().isEmpty()) continue;

            int stock = unlimitedStock ? -1 : itemCfg.getStock();
            int maxStock = unlimitedStock ? -1 : itemCfg.getMaxStock();

            items.add(new ShopItem(
                itemCfg.getItemId(),
                itemCfg.getBuyPrice(),
                itemCfg.getSellPrice(),
                stock,
                maxStock,
                itemCfg.isBuyEnabled(),
                itemCfg.isSellEnabled(),
                slot++,
                itemCfg.getCategory() != null ? itemCfg.getCategory() : "",
                itemCfg.getDailyBuyLimit(),
                itemCfg.getDailySellLimit(),
                null // No metadata for admin shop items
            ));
        }
        return items;
    }

    /**
     * Saves all shops to DB (regardless of dirty state).
     */
    public void saveAll() {
        LOGGER.info("Saving all shops to database...");
        int count = 0;
        for (ShopData shop : shops.values()) {
            try {
                database.saveShop(shop);
                shop.clearDirty();
                count++;
            } catch (Exception e) {
                LOGGER.warning("Failed to save shop " + shop.getId() + ": " + e.getMessage());
            }
        }
        LOGGER.info("Saved " + count + " shops to database");
    }

    /**
     * Saves only shops that have been modified since last save.
     */
    public void saveDirty() {
        int saved = 0;
        for (ShopData shop : shops.values()) {
            if (!shop.isDirty()) continue;
            try {
                database.saveShop(shop);
                shop.clearDirty();
                saved++;
            } catch (Exception e) {
                LOGGER.warning("Failed to save dirty shop " + shop.getId() + ": " + e.getMessage());
            }
        }
        if (saved > 0) {
            LOGGER.info("Saved " + saved + " dirty shops to database");
        }
    }

    /**
     * Returns a shop by its unique ID, or null if not found.
     */
    public ShopData getShop(UUID id) {
        return shops.get(id);
    }

    /**
     * Returns all shops owned by the given player UUID.
     */
    public List<ShopData> getShopsByOwner(UUID ownerUuid) {
        return shops.values().stream()
            .filter(s -> ownerUuid.equals(s.getOwnerUuid()))
            .collect(Collectors.toList());
    }

    /**
     * Returns all shops matching the given category.
     */
    public List<ShopData> getShopsByCategory(String category) {
        return shops.values().stream()
            .filter(s -> category != null && category.equalsIgnoreCase(s.getCategory()))
            .collect(Collectors.toList());
    }

    /**
     * Returns the ShopData row whose {@code rentalSlotId} matches the
     * given UUID. Used by the rental system to find the shell/renter
     * shop backing a rental slot. Returns null if no match.
     */
    public ShopData getShopByRentalSlotId(UUID rentalSlotId) {
        if (rentalSlotId == null) return null;
        for (ShopData shop : shops.values()) {
            if (rentalSlotId.equals(shop.getRentalSlotId())) {
                return shop;
            }
        }
        return null;
    }

    /**
     * Returns all shops currently loaded in memory.
     */
    public Collection<ShopData> getAllShops() {
        return Collections.unmodifiableCollection(shops.values());
    }

    /**
     * Creates a new shop: persists to DB, adds to in-memory cache,
     * and fires a {@link ShopCreateEvent}.
     */
    public void createShop(ShopData shop) {
        try {
            database.saveShop(shop);
        } catch (Exception e) {
            LOGGER.severe("Failed to persist new shop " + shop.getId() + " to database: " + e.getMessage());
            return;
        }

        shops.put(shop.getId(), shop);
        shop.clearDirty();

        ShopEventBus.getInstance().fire(new ShopCreateEvent(
            shop.getId(), shop.getType(), shop.getOwnerUuid(), shop.getName()
        ));

        LOGGER.info("Created shop '" + shop.getName() + "' (" + shop.getId() + ")");
    }

    /**
     * Deletes a shop by ID: removes from DB, removes from cache,
     * and fires a {@link ShopDeleteEvent}.
     */
    public void deleteShop(UUID shopId) {
        ShopData shop = shops.remove(shopId);
        if (shop == null) {
            LOGGER.warning("Attempted to delete non-existent shop: " + shopId);
            return;
        }

        try {
            database.deleteShop(shopId);
        } catch (Exception e) {
            LOGGER.severe("Failed to delete shop " + shopId + " from database: " + e.getMessage());
            // Put back into cache since DB delete failed
            shops.put(shopId, shop);
            return;
        }

        ShopEventBus.getInstance().fire(new ShopDeleteEvent(
            shopId, shop.getType(), shop.getOwnerUuid(), "deleted"
        ));

        LOGGER.info("Deleted shop '" + shop.getName() + "' (" + shopId + ")");
    }

    /**
     * Returns the total number of loaded shops.
     */
    public int getShopCount() {
        return shops.size();
    }
}
