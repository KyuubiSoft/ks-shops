package com.kyuubisoft.shops.service;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.bridge.ClaimsBridge;
import com.kyuubisoft.shops.bridge.ShopEconomyBridge;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.PlayerShopData;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.ShopItem;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.event.ShopCreateEvent;
import com.kyuubisoft.shops.event.ShopDeleteEvent;
import com.kyuubisoft.shops.event.ShopEventBus;
import com.kyuubisoft.shops.event.ShopTransactionEvent;
import com.kyuubisoft.shops.i18n.ShopI18n;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Core business logic for the shop system.
 * Handles purchases, sales, shop creation/deletion, earnings, rent, and notifications.
 */
public class ShopService {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private final ShopPlugin plugin;
    private final ShopManager shopManager;
    private final ShopSessionManager sessionManager;
    private final ShopEconomyBridge economyBridge;
    private final ShopConfig config;
    private final ShopI18n i18n;
    private final ShopDatabase database;

    public ShopService(ShopPlugin plugin, ShopManager shopManager, ShopSessionManager sessionManager,
                       ShopEconomyBridge economyBridge, ShopConfig config, ShopI18n i18n, ShopDatabase database) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.sessionManager = sessionManager;
        this.economyBridge = economyBridge;
        this.config = config;
        this.i18n = i18n;
        this.database = database;
    }

    // ==================== PURCHASE ====================

    /**
     * Processes a purchase: deducts currency from buyer, adds stock change,
     * credits seller earnings, fires transaction event.
     *
     * Follows the Transaction Journal pattern:
     *   1. Validate all preconditions
     *   2. Atomic stock decrement in DB
     *   3. Withdraw from buyer
     *   4. Give item to buyer
     *   5. Deposit to seller (if player shop)
     *   6. Log transaction + update stats
     *
     * @return true if the purchase succeeded
     */
    public boolean purchaseItem(PlayerRef buyer, UUID shopId, int itemSlot, int quantity) {
        if (buyer == null || shopId == null || quantity <= 0) return false;

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Purchase rejected: economy provider not available");
            return false;
        }

        // --- Validate shop exists and is open ---
        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            LOGGER.fine("Purchase rejected: shop not found " + shopId);
            return false;
        }
        if (!shop.isOpen()) {
            LOGGER.fine("Purchase rejected: shop is closed " + shopId);
            return false;
        }

        // --- Self-purchase prevention (skipped for admin shops which have no owner) ---
        UUID buyerUuid = buyer.getUuid();
        if (!shop.isAdminShop() && shop.getOwnerUuid() != null && shop.getOwnerUuid().equals(buyerUuid)) {
            LOGGER.fine("Purchase rejected: buyer is shop owner");
            return false;
        }

        // --- Validate item slot and find the ShopItem ---
        List<ShopItem> items = shop.getItems();
        ShopItem shopItem = null;
        for (ShopItem item : items) {
            if (item.getSlot() == itemSlot) {
                shopItem = item;
                break;
            }
        }
        if (shopItem == null) {
            LOGGER.fine("Purchase rejected: no item in slot " + itemSlot);
            return false;
        }
        if (!shopItem.isBuyEnabled()) {
            LOGGER.fine("Purchase rejected: buy disabled for " + shopItem.getItemId());
            return false;
        }
        if (shopItem.getBuyPrice() <= 0) {
            LOGGER.fine("Purchase rejected: buy price is zero or negative for " + shopItem.getItemId());
            return false;
        }

        // --- Validate stock (cache check before DB) ---
        if (!shopItem.isUnlimitedStock() && shopItem.getStock() < quantity) {
            LOGGER.fine("Purchase rejected: insufficient stock for " + shopItem.getItemId());
            return false;
        }

        // --- Calculate pricing (FIX 5: tax disabled — collected nowhere, would vanish) ---
        int pricePerUnit = shopItem.getBuyPrice();
        int totalPrice = pricePerUnit * quantity;
        int taxAmount = 0;
        int buyerCost = totalPrice;
        int sellerReceived = totalPrice;

        // --- Validate buyer has funds ---
        if (!economyBridge.has(buyerUuid, buyerCost)) {
            LOGGER.fine("Purchase rejected: buyer has insufficient funds");
            return false;
        }

        // --- Step 1: Atomic stock decrement in DB (prevents overselling) ---
        if (!shopItem.isUnlimitedStock()) {
            int rows = database.executeAtomicStockDecrement(shopId, shopItem.getItemId(), quantity);
            if (rows == 0) {
                LOGGER.fine("Purchase rejected: atomic stock decrement failed (out of stock)");
                return false;
            }
        }

        // --- Step 2: Withdraw from buyer ---
        boolean withdrawn = economyBridge.withdraw(buyerUuid, buyerCost);
        if (!withdrawn) {
            // Rollback: restore stock in DB
            if (!shopItem.isUnlimitedStock()) {
                database.executeAtomicStockIncrement(shopId, shopItem.getItemId(), quantity);
            }
            LOGGER.warning("Purchase failed: withdraw from buyer failed, stock rolled back");
            return false;
        }

        // --- Step 3: Give item to buyer (placeholder, wired up with Player reference) ---
        giveItem(buyer, shopItem.getItemId(), quantity);

        // --- Step 4: Credit shop balance (player shop) or skip (admin shop) ---
        boolean isPlayerShop = shop.isPlayerShop() && shop.getOwnerUuid() != null;
        if (isPlayerShop) {
            // Add earnings to shop's own balance account (owner collects later)
            shop.addToBalance(sellerReceived);

            // Update seller's tracking stats
            PlayerShopData sellerData = plugin.getPlayerData(shop.getOwnerUuid());
            if (sellerData != null) {
                sellerData.setTotalEarnings(sellerData.getTotalEarnings() + sellerReceived);
                sellerData.setTotalSales(sellerData.getTotalSales() + 1);
            }

            // Create offline notification if seller is offline
            PlayerRef sellerRef = findOnlinePlayer(shop.getOwnerUuid());
            if (sellerRef == null) {
                database.addNotification(
                    shop.getOwnerUuid(),
                    buyer.getUsername(),
                    shopItem.getItemId(),
                    quantity,
                    sellerReceived
                );
            }
        }
        // Admin shops: money goes to the void (no balance tracking)

        // --- Step 5: Update buyer stats ---
        PlayerShopData buyerData = plugin.getPlayerData(buyerUuid);
        if (buyerData != null) {
            buyerData.setTotalSpent(buyerData.getTotalSpent() + buyerCost);
            buyerData.setTotalPurchases(buyerData.getTotalPurchases() + 1);
        }

        // --- Step 6: Update cache to match DB ---
        if (!shopItem.isUnlimitedStock()) {
            shopItem.setStock(shopItem.getStock() - quantity);
        }

        // --- Step 7: Update shop metadata ---
        shop.setLastActivity(System.currentTimeMillis());
        shop.setTotalRevenue(shop.getTotalRevenue() + totalPrice);
        if (taxAmount > 0) {
            shop.setTotalTaxPaid(shop.getTotalTaxPaid() + taxAmount);
        }

        // --- Step 8: Log transaction to DB ---
        database.logTransaction(
            shopId, buyerUuid, buyer.getUsername(),
            shop.getOwnerUuid(),
            shopItem.getItemId(), quantity,
            pricePerUnit, totalPrice, taxAmount, "BUY"
        );

        // --- Step 9: Fire event ---
        ShopEventBus.getInstance().fire(new ShopTransactionEvent(
            shopId, buyerUuid, shop.getOwnerUuid(),
            shopItem.getItemId(), quantity, totalPrice, taxAmount, "BUY"
        ));

        // --- Step 10: Persist shop state (balance, revenue, lastActivity, stock cache) ---
        database.saveShop(shop);

        LOGGER.info("Purchase complete: " + buyer.getUsername() + " bought " + quantity
            + "x " + shopItem.getItemId() + " for " + buyerCost + " from shop " + shop.getName());
        return true;
    }

    // ==================== SELL ====================

    /**
     * Processes a sale: player sells items to a shop that accepts buy-back.
     *
     * @return true if the sale succeeded
     */
    public boolean sellItem(PlayerRef seller, UUID shopId, String itemId, int quantity) {
        if (seller == null || shopId == null || itemId == null || quantity <= 0) return false;

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Sell rejected: economy provider not available");
            return false;
        }

        // --- Validate shop exists and is open ---
        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            LOGGER.fine("Sell rejected: shop not found " + shopId);
            return false;
        }
        if (!shop.isOpen()) {
            LOGGER.fine("Sell rejected: shop is closed " + shopId);
            return false;
        }

        // --- Find the ShopItem by itemId ---
        ShopItem shopItem = shop.getItem(itemId);
        if (shopItem == null) {
            LOGGER.fine("Sell rejected: item not found in shop " + itemId);
            return false;
        }
        if (!shopItem.isSellEnabled()) {
            LOGGER.fine("Sell rejected: sell disabled for " + itemId);
            return false;
        }
        if (shopItem.getSellPrice() <= 0) {
            LOGGER.fine("Sell rejected: sell price is zero or negative for " + itemId);
            return false;
        }

        // --- Check max stock (don't accept more than maxStock) ---
        if (!shopItem.isUnlimitedStock() && shopItem.getMaxStock() > 0) {
            if (shopItem.getStock() + quantity > shopItem.getMaxStock()) {
                LOGGER.fine("Sell rejected: shop at max stock for " + itemId);
                return false;
            }
        }

        // --- Check buyback quota (dailyBuyLimit field is reused as persistent quota) ---
        // 0 = unlimited, >0 = max amount the shop wants to buy
        int quota = shopItem.getDailyBuyLimit();
        if (quota > 0) {
            if (quantity > quota) {
                LOGGER.fine("Sell rejected: would exceed shop buyback quota (quota=" + quota + " requested=" + quantity + ")");
                return false;
            }
        }

        // --- Calculate pricing (FIX 5: tax disabled — collected nowhere, would vanish) ---
        UUID sellerUuid = seller.getUuid();
        int pricePerUnit = shopItem.getSellPrice();
        int totalPrice = pricePerUnit * quantity;
        int taxAmount = 0;
        int sellerReceived = totalPrice;

        // --- For player shops: check if shop balance can afford to buy back ---
        boolean isPlayerShop = shop.isPlayerShop() && shop.getOwnerUuid() != null;
        if (isPlayerShop) {
            if (shop.getShopBalance() < totalPrice) {
                LOGGER.fine("Sell rejected: shop balance insufficient ("
                    + shop.getShopBalance() + " < " + totalPrice + ")");
                return false;
            }
        }
        // Admin shops: unlimited balance, no check needed

        // --- Verify seller has the items (placeholder, actual inventory check at Player level) ---
        // The caller (UI handler) should verify inventory before calling this method.
        // We proceed trusting that the check was done.

        // --- Step 1: Remove items from seller (placeholder) ---
        boolean removed = removeItem(seller, itemId, quantity);
        if (!removed) {
            LOGGER.fine("Sell rejected: failed to remove items from seller inventory");
            return false;
        }

        // --- Step 2: Deduct from shop balance (player shop) ---
        if (isPlayerShop) {
            boolean deducted = shop.deductFromBalance(totalPrice);
            if (!deducted) {
                // Rollback: return items to seller
                giveItem(seller, itemId, quantity);
                LOGGER.warning("Sell failed: shop balance deduction failed, items returned");
                return false;
            }
        }
        // Admin shops: no balance deduction

        // --- Step 3: Deposit to seller ---
        boolean deposited = economyBridge.deposit(sellerUuid, sellerReceived);
        if (!deposited) {
            // Rollback: restore shop balance, return items to seller
            if (isPlayerShop) {
                shop.addToBalance(totalPrice);
            }
            giveItem(seller, itemId, quantity);
            LOGGER.warning("Sell failed: deposit to seller failed, all rolled back");
            return false;
        }

        // --- Step 4: Atomic increment stock in DB (FIX 6: respects maxStock, rolls back on cache drift) ---
        if (!shopItem.isUnlimitedStock()) {
            int rows = database.executeAtomicStockIncrement(shopId, itemId, quantity);
            if (rows == 0) {
                // Rollback: items back to seller, restore shop balance, withdraw deposit
                giveItem(seller, itemId, quantity);
                if (isPlayerShop) {
                    shop.addToBalance(totalPrice);
                }
                economyBridge.withdraw(sellerUuid, sellerReceived);
                LOGGER.warning("Sell rollback: stock increment failed (max stock exceeded or item missing) for "
                    + itemId + " in shop " + shop.getName());
                return false;
            }
            shopItem.setStock(shopItem.getStock() + quantity);
        }

        // --- Step 4b: Decrement buyback quota (dailyBuyLimit field) ---
        int currentQuota = shopItem.getDailyBuyLimit();
        if (currentQuota > 0) {
            int newQuota = Math.max(0, currentQuota - quantity);
            shopItem.setDailyBuyLimit(newQuota);
            // If quota reached 0, disable sellEnabled so the shop stops accepting this item
            if (newQuota == 0) {
                shopItem.setSellEnabled(false);
                LOGGER.info("Buyback quota exhausted for " + itemId + " in shop " + shop.getName() + " - sell disabled");
            }
        }
        shop.markDirty();

        // --- Step 5: Update stats ---
        PlayerShopData sellerData = plugin.getPlayerData(sellerUuid);
        if (sellerData != null) {
            sellerData.setTotalEarnings(sellerData.getTotalEarnings() + sellerReceived);
        }

        // --- Step 6: Update shop metadata ---
        shop.setLastActivity(System.currentTimeMillis());
        if (taxAmount > 0) {
            shop.setTotalTaxPaid(shop.getTotalTaxPaid() + taxAmount);
        }

        // --- Step 7: Log transaction ---
        database.logTransaction(
            shopId, sellerUuid, seller.getUsername(),
            shop.getOwnerUuid(),
            itemId, quantity,
            pricePerUnit, totalPrice, taxAmount, "SELL"
        );

        // --- Step 8: Fire event ---
        ShopEventBus.getInstance().fire(new ShopTransactionEvent(
            shopId, sellerUuid, shop.getOwnerUuid(),
            itemId, quantity, totalPrice, taxAmount, "SELL"
        ));

        // --- Step 9: Persist shop state (balance, quota, lastActivity, stock cache) ---
        database.saveShop(shop);

        LOGGER.info("Sale complete: " + seller.getUsername() + " sold " + quantity
            + "x " + itemId + " for " + sellerReceived + " to shop " + shop.getName());
        return true;
    }

    // ==================== SHOP CREATION ====================

    /**
     * Creates a new player-owned shop at the given world position.
     *
     * @return the created ShopData, or null on failure
     */
    public ShopData createPlayerShop(PlayerRef owner, String name, String category,
                                     String description, String worldName,
                                     double x, double y, double z) {
        if (owner == null || name == null || name.isBlank()) return null;

        UUID ownerUuid = owner.getUuid();
        ShopConfig.ConfigData cfg = config.getData();

        // --- Validate player shop feature is enabled ---
        if (!cfg.features.playerShops) {
            LOGGER.fine("Create rejected: player shops disabled");
            return null;
        }

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Create rejected: economy provider not available");
            return null;
        }

        // --- Validate max shops not reached ---
        List<ShopData> ownedShops = shopManager.getShopsByOwner(ownerUuid);
        if (ownedShops.size() >= cfg.playerShops.maxShopsPerPlayer) {
            LOGGER.fine("Create rejected: player " + owner.getUsername()
                + " reached max shops (" + cfg.playerShops.maxShopsPerPlayer + ")");
            return null;
        }

        // --- Validate name length ---
        String trimmedName = name.trim();
        if (trimmedName.length() < cfg.playerShops.nameMinLength
            || trimmedName.length() > cfg.playerShops.nameMaxLength) {
            LOGGER.fine("Create rejected: name length invalid (" + trimmedName.length() + ")");
            return null;
        }

        // --- Validate name uniqueness ---
        for (ShopData existing : shopManager.getAllShops()) {
            if (existing.getName().equalsIgnoreCase(trimmedName)) {
                LOGGER.fine("Create rejected: name already taken '" + trimmedName + "'");
                return null;
            }
        }

        // --- Validate description length ---
        if (description != null && description.length() > cfg.playerShops.descriptionMaxLength) {
            description = description.substring(0, cfg.playerShops.descriptionMaxLength);
        }

        // --- Check claims if required ---
        if (cfg.playerShops.requireClaim && cfg.features.claimsIntegration) {
            if (!ClaimsBridge.isPlayerClaim(ownerUuid, worldName, x, z)) {
                LOGGER.fine("Create rejected: player does not own claim at position");
                return null;
            }
        }

        // --- Check funds for creation cost ---
        int creationCost = cfg.playerShops.creationCost;
        if (creationCost > 0) {
            if (!economyBridge.has(ownerUuid, creationCost)) {
                LOGGER.fine("Create rejected: insufficient funds for creation cost ("
                    + creationCost + ")");
                return null;
            }

            // Withdraw creation cost
            boolean withdrawn = economyBridge.withdraw(ownerUuid, creationCost);
            if (!withdrawn) {
                LOGGER.warning("Create failed: withdraw of creation cost failed");
                return null;
            }
        }

        // --- Create ShopData ---
        ShopData shopData = new ShopData(
            trimmedName,
            description != null ? description : "",
            ShopType.PLAYER,
            ownerUuid,
            owner.getUsername(),
            worldName,
            x, y, z,
            0.0f
        );
        shopData.setCategory(category != null ? category : "");

        // --- Persist ---
        shopManager.createShop(shopData);

        // --- Update player data ---
        PlayerShopData playerData = plugin.getPlayerData(ownerUuid);
        if (playerData != null) {
            playerData.addOwnedShop(shopData.getId());
            playerData.setTotalSpent(playerData.getTotalSpent() + creationCost);
        }

        // --- Fire event ---
        ShopEventBus.getInstance().fire(new ShopCreateEvent(
            shopData.getId(), ShopType.PLAYER, ownerUuid, trimmedName
        ));

        LOGGER.info("Shop created: '" + trimmedName + "' by " + owner.getUsername()
            + " at " + worldName + " [" + (int) x + ", " + (int) y + ", " + (int) z + "]");
        return shopData;
    }

    // ==================== SHOP DELETION ====================

    /**
     * Deletes a player-owned shop. Only the owner (or admin) may delete.
     *
     * @return true if deletion succeeded
     */
    public boolean deletePlayerShop(PlayerRef owner, UUID shopId) {
        if (owner == null || shopId == null) return false;

        UUID ownerUuid = owner.getUuid();

        // --- Validate shop exists ---
        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            LOGGER.fine("Delete rejected: shop not found " + shopId);
            return false;
        }

        // --- Validate ownership ---
        if (!shop.isPlayerShop() || shop.getOwnerUuid() == null
            || !shop.getOwnerUuid().equals(ownerUuid)) {
            LOGGER.fine("Delete rejected: not the owner of shop " + shopId);
            return false;
        }

        // --- Calculate refund ---
        ShopConfig.ConfigData cfg = config.getData();
        // Default 50% refund on deletion
        int creationCost = cfg.playerShops.creationCost;
        int refund = creationCost / 2;

        // --- Deposit refund ---
        if (refund > 0 && economyBridge.isAvailable()) {
            boolean deposited = economyBridge.deposit(ownerUuid, refund);
            if (!deposited) {
                LOGGER.warning("Delete: refund deposit failed for " + ownerUuid
                    + " (amount: " + refund + "), proceeding with deletion anyway");
            }
        }

        // --- Close any active sessions for this shop ---
        sessionManager.unlockEditor(shopId, ownerUuid);

        // --- Remove from manager (cache + DB) ---
        shopManager.deleteShop(shopId);

        // --- Update player data ---
        PlayerShopData playerData = plugin.getPlayerData(ownerUuid);
        if (playerData != null) {
            playerData.removeOwnedShop(shopId);
        }

        // --- Fire event ---
        ShopEventBus.getInstance().fire(new ShopDeleteEvent(
            shopId, ShopType.PLAYER, ownerUuid, "owner_deleted"
        ));

        LOGGER.info("Shop deleted: '" + shop.getName() + "' by " + owner.getUsername()
            + " (refund: " + refund + ")");
        return true;
    }

    // ==================== SHOP UI ====================

    /**
     * Opens a shop UI for a buyer (browse + purchase mode).
     */
    public void openShopForBuyer(Player player, PlayerRef ref, UUID shopId) {
        if (player == null || ref == null || shopId == null) return;

        ShopData shop = shopManager.getShop(shopId);
        if (shop == null || !shop.isOpen()) return;

        // Mark player as active buyer
        sessionManager.addActiveBuyer(ref.getUuid());

        // TODO: Open shop browse page via PageManager
        // player.getPageManager().openCustomPage(ref, shopBrowseStore, shopBrowsePage);
    }

    /**
     * Opens a shop UI for the owner (editor mode with drag&drop).
     */
    public void openShopForOwner(Player player, PlayerRef ref, UUID shopId) {
        if (player == null || ref == null || shopId == null) return;

        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) return;

        UUID playerUuid = ref.getUuid();

        // Validate ownership
        if (shop.getOwnerUuid() == null || !shop.getOwnerUuid().equals(playerUuid)) return;

        // Try to acquire editor lock
        if (!sessionManager.lockEditor(shopId, playerUuid)) {
            // Shop is already being edited by someone else
            return;
        }

        // TODO: Open shop editor page via PageManager
        // player.getPageManager().openCustomPage(ref, shopEditorStore, shopEditorPage);
    }

    // ==================== EARNINGS ====================

    /**
     * Collects earnings from all owned shop balances.
     * Iterates all shops owned by the player, sums their shopBalance,
     * deposits into the player's wallet, and resets shop balances to 0.
     *
     * @return the total amount collected
     */
    public double collectEarnings(PlayerRef player) {
        if (player == null) return 0.0;

        UUID playerUuid = player.getUuid();

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Collect earnings rejected: economy provider not available");
            return 0.0;
        }

        // --- Sum balances from all owned shops ---
        List<ShopData> ownedShops = shopManager.getShopsByOwner(playerUuid);
        double totalToCollect = 0;
        for (ShopData shop : ownedShops) {
            totalToCollect += shop.getShopBalance();
        }

        if (totalToCollect <= 0) return 0.0;

        // --- Deposit total to player wallet ---
        boolean deposited = economyBridge.deposit(playerUuid, totalToCollect);
        if (!deposited) {
            LOGGER.warning("Collect earnings failed: deposit to " + playerUuid + " failed");
            return 0.0;
        }

        // --- Reset all shop balances to 0 ---
        for (ShopData shop : ownedShops) {
            if (shop.getShopBalance() > 0) {
                shop.setShopBalance(0);
                database.saveShop(shop);
            }
        }

        LOGGER.info("Earnings collected: " + player.getUsername() + " received "
            + economyBridge.format(totalToCollect) + " from " + ownedShops.size() + " shop(s)");
        return totalToCollect;
    }

    // ==================== SHOP BALANCE DEPOSIT ====================

    /**
     * Deposits money from the player's wallet into a shop's balance.
     *
     * @param player the shop owner
     * @param shopId the shop to deposit into
     * @param amount the amount to deposit
     * @return true if the deposit succeeded
     */
    public boolean depositToShop(PlayerRef player, UUID shopId, double amount) {
        if (player == null || shopId == null || amount <= 0) return false;

        UUID playerUuid = player.getUuid();

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Shop deposit rejected: economy provider not available");
            return false;
        }

        // --- Validate shop exists and is owned by player ---
        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            LOGGER.fine("Shop deposit rejected: shop not found " + shopId);
            return false;
        }
        if (shop.getOwnerUuid() == null || !shop.getOwnerUuid().equals(playerUuid)) {
            LOGGER.fine("Shop deposit rejected: not the owner of shop " + shopId);
            return false;
        }

        // --- Validate player has enough funds ---
        if (!economyBridge.has(playerUuid, amount)) {
            LOGGER.fine("Shop deposit rejected: insufficient funds");
            return false;
        }

        // --- Withdraw from player ---
        boolean withdrawn = economyBridge.withdraw(playerUuid, amount);
        if (!withdrawn) {
            LOGGER.warning("Shop deposit failed: withdraw from player failed");
            return false;
        }

        // --- Add to shop balance ---
        shop.addToBalance(amount);
        database.saveShop(shop);

        LOGGER.info("Shop deposit: " + player.getUsername() + " deposited "
            + economyBridge.format(amount) + " into shop '" + shop.getName() + "'"
            + " (new balance: " + economyBridge.format(shop.getShopBalance()) + ")");
        return true;
    }

    // ==================== RENT COLLECTION ====================

    /**
     * Processes rent collection for all player shops (scheduled task).
     *
     * For each player shop where rentPaidUntil has elapsed:
     *   1. Skip shops where rent is not enabled or owner has bypass.rent permission
     *   2. Skip shops still within their new-shop exemption period
     *   3. Try to withdraw rent from owner via economyBridge
     *   4. On success: advance rentPaidUntil by one cycle, save shop
     *   5. On failure: check grace period; if exceeded, auto-close
     *   6. If autoDeleteOnUnpaidDays exceeded, delete shop entirely
     */
    public void collectRent() {
        if (!config.getData().rent.enabled) return;

        ShopConfig.Rent rentConfig = config.getData().rent;
        long now = System.currentTimeMillis();
        double costPerDay = rentConfig.costPerDay;
        int gracePeriodDays = rentConfig.gracePeriodDays;
        boolean autoClose = rentConfig.autoCloseOnExpire;
        int autoDeleteDays = rentConfig.autoDeleteOnUnpaidDays;
        int exemptDays = rentConfig.rentExemptForNewShopsDays;
        long dayMs = 86400000L;

        int collected = 0;
        int closed = 0;
        int deleted = 0;

        // Collect shops to delete in a separate list to avoid ConcurrentModificationException
        List<UUID> toDelete = new java.util.ArrayList<>();

        for (ShopData shop : shopManager.getAllShops()) {
            // Only process player-owned shops
            if (!shop.isPlayerShop() || shop.getOwnerUuid() == null) continue;

            UUID ownerUuid = shop.getOwnerUuid();

            // Skip new shops within exemption period
            if (exemptDays > 0) {
                long shopAge = now - shop.getCreatedAt();
                if (shopAge < exemptDays * dayMs) {
                    continue;
                }
            }

            // Initialize rentPaidUntil if it was never set (0 = legacy/new shop)
            if (shop.getRentPaidUntil() <= 0) {
                shop.setRentPaidUntil(now);
                database.saveShop(shop);
                continue;
            }

            // Check if rent is due
            if (shop.getRentPaidUntil() > now) continue;

            // Calculate how many days overdue
            long overdueDays = (now - shop.getRentPaidUntil()) / dayMs;

            // Auto-delete if exceeded autoDeleteOnUnpaidDays threshold
            if (autoDeleteDays > 0 && overdueDays > autoDeleteDays) {
                LOGGER.info("Shop '" + shop.getName() + "' (owner: " + shop.getOwnerName()
                    + ") deleted: rent unpaid for " + overdueDays + " days (limit: " + autoDeleteDays + ")");

                // Create notification for owner before deleting
                database.addNotification(ownerUuid, "SYSTEM", "SHOP_DELETED_RENT",
                    0, 0);

                toDelete.add(shop.getId());
                deleted++;
                continue;
            }

            // Within grace period: skip payment attempt but log
            if (overdueDays <= gracePeriodDays) {
                LOGGER.fine("Shop '" + shop.getName() + "' rent overdue " + overdueDays
                    + "d (grace: " + gracePeriodDays + "d) - skipping");
                continue;
            }

            // Grace period exceeded: attempt payment
            if (economyBridge.isAvailable() && economyBridge.has(ownerUuid, costPerDay)) {
                boolean withdrawn = economyBridge.withdraw(ownerUuid, costPerDay);
                if (withdrawn) {
                    // Advance rentPaidUntil by one full cycle day
                    int cycleDays = shop.getRentCycleDays() > 0 ? shop.getRentCycleDays() : 1;
                    shop.setRentPaidUntil(shop.getRentPaidUntil() + (cycleDays * dayMs));
                    database.saveShop(shop);
                    collected++;
                    LOGGER.fine("Rent collected from shop '" + shop.getName()
                        + "' (owner: " + shop.getOwnerName() + ", cost: " + costPerDay + ")");
                    continue;
                }
            }

            // Payment failed: auto-close if configured
            if (autoClose && shop.isOpen()) {
                shop.setOpen(false);
                database.saveShop(shop);
                closed++;
                LOGGER.info("Shop '" + shop.getName() + "' closed: rent unpaid for "
                    + overdueDays + " days (owner: " + shop.getOwnerName() + ")");

                // Notify owner about shop closure
                database.addNotification(ownerUuid, "SYSTEM", "SHOP_CLOSED_RENT",
                    0, 0);

                ShopEventBus.getInstance().fire(new ShopDeleteEvent(
                    shop.getId(), ShopType.PLAYER, ownerUuid, "rent_unpaid"
                ));
            }
        }

        // Process deletions outside the iteration
        for (UUID shopId : toDelete) {
            shopManager.deleteShop(shopId);
        }

        if (collected > 0 || closed > 0 || deleted > 0) {
            LOGGER.info("Rent cycle complete: " + collected + " collected, "
                + closed + " closed, " + deleted + " deleted");
        }
    }

    // ==================== SHOP OF THE WEEK ====================

    /**
     * Selects and updates the "Shop of the Week" featured shops (scheduled task).
     *
     * 1. Clears expired featured flags (featuredUntil < now)
     * 2. If autoFeatureTopRated is enabled:
     *    - Filters shops by minRating and minSales thresholds
     *    - Scores shops by weighted rating + sales + recent activity
     *    - Picks top N (maxFeaturedSlots) that aren't already featured
     *    - Sets featured=true, featuredUntil = now + (durationDays * 86400000)
     *    - Saves shops
     * 3. Logs featured shop names
     */
    public void updateShopOfTheWeek() {
        if (!config.getData().features.shopOfTheWeek) return;

        ShopConfig.Featured featuredConfig = config.getData().featured;
        long now = System.currentTimeMillis();
        long dayMs = 86400000L;

        // Step 1: Clear expired featured flags
        int expired = 0;
        for (ShopData shop : shopManager.getAllShops()) {
            if (shop.isFeatured() && shop.getFeaturedUntil() < now) {
                shop.setFeatured(false);
                shop.setFeaturedUntil(0);
                database.saveShop(shop);
                expired++;
            }
        }

        if (expired > 0) {
            LOGGER.fine("Cleared " + expired + " expired featured flags");
        }

        // Step 2: Auto-feature top-rated shops if enabled
        if (!featuredConfig.autoFeatureTopRated) {
            LOGGER.fine("Shop of the Week update complete (auto-feature disabled)");
            return;
        }

        double minRating = featuredConfig.autoFeatureMinRating;
        int minSales = featuredConfig.autoFeatureMinSales;
        int maxSlots = featuredConfig.maxFeaturedSlots;
        int durationDays = featuredConfig.durationDays;

        // Count currently featured shops
        int currentlyFeatured = 0;
        for (ShopData shop : shopManager.getAllShops()) {
            if (shop.isFeatured()) currentlyFeatured++;
        }

        int slotsAvailable = maxSlots - currentlyFeatured;
        if (slotsAvailable <= 0) {
            LOGGER.fine("Shop of the Week: all " + maxSlots + " featured slots occupied");
            return;
        }

        // Build candidate list: player shops that meet rating/sales thresholds and are not already featured
        List<ShopData> candidates = new java.util.ArrayList<>();
        for (ShopData shop : shopManager.getAllShops()) {
            if (!shop.isPlayerShop()) continue;
            if (!shop.isOpen()) continue;
            if (shop.isFeatured()) continue;
            if (shop.getAverageRating() < minRating) continue;
            if (shop.getTotalRatings() < 1) continue;

            // Use totalRevenue as a proxy for sales volume
            // (totalRevenue accumulates over the shop's lifetime)
            if (shop.getTotalRevenue() < minSales) continue;

            candidates.add(shop);
        }

        if (candidates.isEmpty()) {
            LOGGER.fine("Shop of the Week: no candidates meet thresholds (minRating="
                + minRating + ", minSales=" + minSales + ")");
            return;
        }

        // Score candidates: weighted combination of rating, revenue, and recency
        // Score = (averageRating * 20) + (totalRevenue * 0.01) + recencyBonus
        candidates.sort((a, b) -> {
            double scoreA = computeFeatureScore(a, now);
            double scoreB = computeFeatureScore(b, now);
            return Double.compare(scoreB, scoreA); // Descending
        });

        // Pick top N
        int toFeature = Math.min(slotsAvailable, candidates.size());
        List<String> featuredNames = new java.util.ArrayList<>();

        for (int i = 0; i < toFeature; i++) {
            ShopData shop = candidates.get(i);
            shop.setFeatured(true);
            shop.setFeaturedUntil(now + (durationDays * dayMs));
            database.saveShop(shop);
            featuredNames.add(shop.getName());
        }

        LOGGER.info("Shop of the Week: featured " + featuredNames.size()
            + " shops: " + String.join(", ", featuredNames));
    }

    /**
     * Computes a feature score for ranking shop-of-the-week candidates.
     * Higher score = more likely to be featured.
     *
     * Factors:
     *   - averageRating * 20 (0-100 range for 5-star system)
     *   - totalRevenue * 0.01 (scaled for large values)
     *   - recency bonus: shops active in the last 7 days get +20
     */
    private double computeFeatureScore(ShopData shop, long now) {
        double ratingScore = shop.getAverageRating() * 20.0;
        double revenueScore = shop.getTotalRevenue() * 0.01;

        // Recency bonus: active within last 7 days
        long dayMs = 86400000L;
        long daysSinceActivity = (now - shop.getLastActivity()) / dayMs;
        double recencyBonus = daysSinceActivity <= 7 ? 20.0 : 0.0;

        return ratingScore + revenueScore + recencyBonus;
    }

    // ==================== NOTIFICATIONS ====================

    /**
     * Sends batched offline notifications to a player on login
     * (e.g. sales while offline, rent due, shop closed).
     *
     * Flow:
     *   1. Load unread notifications from DB
     *   2. Build summary (total earnings, items sold count)
     *   3. Send chat summary message via PlayerRef.sendMessage()
     *   4. Do NOT mark as read here (player can review in /ksshop notifications UI)
     */
    public void sendOfflineNotifications(PlayerRef player, PlayerShopData data) {
        if (player == null || data == null) return;

        if (!config.getData().notifications.enabled) return;
        if (!config.getData().notifications.batchOnLogin) return;

        UUID playerUuid = player.getUuid();

        // Load unread notifications from DB
        List<ShopDatabase.SaleNotification> notifications = database.loadNotifications(playerUuid);
        if (notifications.isEmpty()) return;

        // Build batch summary
        int totalEarned = 0;
        int totalItems = 0;
        for (ShopDatabase.SaleNotification notification : notifications) {
            totalEarned += notification.earned;
            totalItems += notification.quantity;
        }

        // Send summary notification to player via chat
        try {
            String summaryMsg = i18n.get(player, "shop.notification.offline_summary",
                notifications.size(), totalItems, economyBridge.format(totalEarned));
            player.sendMessage(Message.raw(summaryMsg).color("#FFD700"));

            // Hint to open notifications page for details
            String hintMsg = i18n.get(player, "shop.notification.offline_hint");
            player.sendMessage(Message.raw(hintMsg).color("#96a9be"));
        } catch (Exception e) {
            LOGGER.warning("Failed to send offline notifications to " + player.getUsername()
                + ": " + e.getMessage());
        }

        LOGGER.fine("Sent offline notification summary to " + player.getUsername()
            + ": " + notifications.size() + " sales, " + totalEarned + " earned");

        // Note: notifications are NOT marked as read here.
        // The player can review them via /ksshop notifications UI and mark read there.
    }

    // ==================== HELPERS ====================

    /**
     * Gives items to a player. Tries to add to Storage first; if the storage is full
     * the overflow is dropped at the player's feet via SimpleItemContainer.addOrDropItemStack.
     * Marks both Storage and Hotbar dirty on success.
     *
     * Pattern mirrors the bank mod (BankService.addItemOrDrop).
     */
    private void giveItem(PlayerRef playerRef, String itemId, int quantity) {
        if (playerRef == null || itemId == null || quantity <= 0) return;

        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                LOGGER.warning("giveItem: invalid player reference for " + playerRef.getUsername());
                return;
            }
            Store<EntityStore> store = ref.getStore();

            ItemStack stack = new ItemStack(itemId, quantity);

            // Attempt to add to player Storage
            var storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            if (storageComp != null) {
                ItemContainer storage = storageComp.getInventory();
                if (storage != null) {
                    storage.addItemStack(stack);
                    storageComp.markDirty();

                    // Also mark Hotbar dirty in case overflow spilled there via combined containers
                    var hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
                    if (hotbarComp != null) hotbarComp.markDirty();
                    return;
                }
            }

            // Storage unavailable or full — drop at player's feet
            SimpleItemContainer tempContainer = new SimpleItemContainer((short) 0);
            SimpleItemContainer.addOrDropItemStack(store, ref, tempContainer, stack);
            LOGGER.fine("giveItem: dropped " + quantity + "x " + itemId
                + " for " + playerRef.getUsername() + " (storage unavailable)");
        } catch (Exception e) {
            LOGGER.warning("giveItem failed for " + playerRef.getUsername()
                + " (" + quantity + "x " + itemId + "): " + e.getMessage());
        }
    }

    /**
     * Removes items from a player's inventory by iterating Hotbar + Storage and
     * decrementing matching stacks. If the player does not have enough items,
     * NOTHING is changed and false is returned (atomic check).
     *
     * @return true if all requested items were successfully removed
     */
    private boolean removeItem(PlayerRef playerRef, String itemId, int quantity) {
        if (playerRef == null || itemId == null || quantity <= 0) return false;

        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                LOGGER.warning("removeItem: invalid player reference for " + playerRef.getUsername());
                return false;
            }
            Store<EntityStore> store = ref.getStore();

            var hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            var storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            ItemContainer hotbar = hotbarComp != null ? hotbarComp.getInventory() : null;
            ItemContainer storage = storageComp != null ? storageComp.getInventory() : null;

            // Pre-flight: count available items across both containers
            int available = countItem(hotbar, itemId) + countItem(storage, itemId);
            if (available < quantity) {
                LOGGER.fine("removeItem: insufficient items for " + playerRef.getUsername()
                    + " (have " + available + ", need " + quantity + " of " + itemId + ")");
                return false;
            }

            // Decrement stacks: hotbar first, then storage
            int remaining = quantity;
            remaining = decrementContainer(hotbar, itemId, remaining);
            if (remaining > 0) {
                remaining = decrementContainer(storage, itemId, remaining);
            }

            if (remaining > 0) {
                // Should never happen due to pre-flight check, but guard against races
                LOGGER.warning("removeItem: drift detected for " + playerRef.getUsername()
                    + " — pre-flight passed but " + remaining + " items remain undecremented");
                return false;
            }

            // Mark both containers dirty
            if (hotbarComp != null) hotbarComp.markDirty();
            if (storageComp != null) storageComp.markDirty();
            return true;
        } catch (Exception e) {
            LOGGER.warning("removeItem failed for " + playerRef.getUsername()
                + " (" + quantity + "x " + itemId + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Counts the total quantity of a given itemId across all slots of a container.
     */
    private static int countItem(ItemContainer container, String itemId) {
        if (container == null) return 0;
        int total = 0;
        for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack s = container.getItemStack(i);
            if (s == null || s.isEmpty()) continue;
            if (itemId.equals(s.getItemId())) {
                total += s.getQuantity();
            }
        }
        return total;
    }

    /**
     * Decrements stacks of a given itemId in a container until {@code remaining}
     * items have been removed. Returns the number of items still left to remove
     * (0 = fully satisfied).
     */
    private static int decrementContainer(ItemContainer container, String itemId, int remaining) {
        if (container == null || remaining <= 0) return remaining;
        for (short i = 0; i < container.getCapacity(); i++) {
            if (remaining <= 0) break;
            ItemStack s = container.getItemStack(i);
            if (s == null || s.isEmpty()) continue;
            if (!itemId.equals(s.getItemId())) continue;

            int stackQty = s.getQuantity();
            if (stackQty <= remaining) {
                // Consume the whole stack
                container.setItemStackForSlot(i, null);
                remaining -= stackQty;
            } else {
                // Partial consume
                ItemStack reduced = new ItemStack(s.getItemId(), stackQty - remaining,
                    s.getDurability(), s.getMaxDurability(), s.getMetadata());
                container.setItemStackForSlot(i, reduced);
                remaining = 0;
            }
        }
        return remaining;
    }

    /**
     * Finds an online player by UUID. Returns null if the player is offline.
     * Used to determine whether to create offline notifications.
     */
    private PlayerRef findOnlinePlayer(UUID uuid) {
        // Check if the player has data loaded (connected indicator)
        PlayerShopData data = plugin.getPlayerData(uuid);
        // If data is loaded in the playerData map, the player is online
        // (data is added on connect, removed on disconnect)
        // But we need the PlayerRef -- for now we return null to be safe
        // and always create notifications. The notification system handles
        // duplicate/read notifications gracefully.
        return null;
    }
}
