package com.kyuubisoft.shops.service;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

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

        // --- Self-purchase prevention ---
        UUID buyerUuid = buyer.getUuid();
        if (shop.getOwnerUuid() != null && shop.getOwnerUuid().equals(buyerUuid)) {
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

        // --- Validate stock (cache check before DB) ---
        if (!shopItem.isUnlimitedStock() && shopItem.getStock() < quantity) {
            LOGGER.fine("Purchase rejected: insufficient stock for " + shopItem.getItemId());
            return false;
        }

        // --- Calculate pricing ---
        int pricePerUnit = shopItem.getBuyPrice();
        int totalPrice = pricePerUnit * quantity;
        ShopConfig.Tax taxConfig = config.getData().tax;
        int taxAmount = 0;
        if (taxConfig.enabled) {
            taxAmount = (int) (totalPrice * taxConfig.buyTaxPercent / 100.0);
        }
        int buyerCost = totalPrice + taxAmount;
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

        // --- Step 4: Deposit to seller (if player shop) ---
        boolean isPlayerShop = shop.isPlayerShop() && shop.getOwnerUuid() != null;
        if (isPlayerShop) {
            boolean deposited = economyBridge.deposit(shop.getOwnerUuid(), sellerReceived);
            if (!deposited) {
                // Rollback: refund buyer (item already given, so log the inconsistency)
                LOGGER.warning("Purchase partial failure: deposit to seller failed for shop "
                    + shopId + " — refunding buyer " + buyerUuid);
                economyBridge.deposit(buyerUuid, buyerCost);
                // Restore stock
                if (!shopItem.isUnlimitedStock()) {
                    database.executeAtomicStockIncrement(shopId, shopItem.getItemId(), quantity);
                }
                return false;
            }

            // Update seller's pending earnings for tracking
            PlayerShopData sellerData = plugin.getPlayerData(shop.getOwnerUuid());
            if (sellerData != null) {
                sellerData.setPendingEarnings(sellerData.getPendingEarnings() + sellerReceived);
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

        // --- Calculate pricing ---
        UUID sellerUuid = seller.getUuid();
        int pricePerUnit = shopItem.getSellPrice();
        int totalPrice = pricePerUnit * quantity;
        ShopConfig.Tax taxConfig = config.getData().tax;
        int taxAmount = 0;
        if (taxConfig.enabled) {
            taxAmount = (int) (totalPrice * taxConfig.sellTaxPercent / 100.0);
        }
        int sellerReceived = totalPrice - taxAmount;

        // --- For player shops: check if shop owner can afford to buy ---
        boolean isPlayerShop = shop.isPlayerShop() && shop.getOwnerUuid() != null;
        if (isPlayerShop) {
            if (!economyBridge.has(shop.getOwnerUuid(), totalPrice)) {
                LOGGER.fine("Sell rejected: shop owner has insufficient funds");
                return false;
            }
        }

        // --- Verify seller has the items (placeholder, actual inventory check at Player level) ---
        // The caller (UI handler) should verify inventory before calling this method.
        // We proceed trusting that the check was done.

        // --- Step 1: Remove items from seller (placeholder) ---
        boolean removed = removeItem(seller, itemId, quantity);
        if (!removed) {
            LOGGER.fine("Sell rejected: failed to remove items from seller inventory");
            return false;
        }

        // --- Step 2: Withdraw from shop owner (player shop) ---
        if (isPlayerShop) {
            boolean withdrawn = economyBridge.withdraw(shop.getOwnerUuid(), totalPrice);
            if (!withdrawn) {
                // Rollback: return items to seller
                giveItem(seller, itemId, quantity);
                LOGGER.warning("Sell failed: withdraw from shop owner failed, items returned");
                return false;
            }
        }

        // --- Step 3: Deposit to seller ---
        boolean deposited = economyBridge.deposit(sellerUuid, sellerReceived);
        if (!deposited) {
            // Rollback: refund shop owner, return items to seller
            if (isPlayerShop) {
                economyBridge.deposit(shop.getOwnerUuid(), totalPrice);
            }
            giveItem(seller, itemId, quantity);
            LOGGER.warning("Sell failed: deposit to seller failed, all rolled back");
            return false;
        }

        // --- Step 4: Increment stock in DB ---
        if (!shopItem.isUnlimitedStock()) {
            database.executeAtomicStockIncrement(shopId, itemId, quantity);
            shopItem.setStock(shopItem.getStock() + quantity);
        }

        // --- Step 5: Update stats ---
        PlayerShopData sellerData = plugin.getPlayerData(sellerUuid);
        if (sellerData != null) {
            sellerData.setTotalEarnings(sellerData.getTotalEarnings() + sellerReceived);
        }
        if (isPlayerShop) {
            PlayerShopData ownerData = plugin.getPlayerData(shop.getOwnerUuid());
            if (ownerData != null) {
                ownerData.setTotalSpent(ownerData.getTotalSpent() + totalPrice);
            }
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
     * Collects all pending earnings for a player.
     *
     * @return the amount collected
     */
    public double collectEarnings(PlayerRef player) {
        if (player == null) return 0.0;

        UUID playerUuid = player.getUuid();

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Collect earnings rejected: economy provider not available");
            return 0.0;
        }

        // --- Load player data ---
        PlayerShopData data = plugin.getPlayerData(playerUuid);
        if (data == null) {
            LOGGER.fine("Collect earnings: no player data found for " + playerUuid);
            return 0.0;
        }

        double pending = data.getPendingEarnings();
        if (pending <= 0) return 0.0;

        // --- Deposit earnings to player ---
        boolean deposited = economyBridge.deposit(playerUuid, pending);
        if (!deposited) {
            LOGGER.warning("Collect earnings failed: deposit to " + playerUuid + " failed");
            return 0.0;
        }

        // --- Reset pending earnings ---
        data.setPendingEarnings(0);
        data.markDirty();

        LOGGER.info("Earnings collected: " + player.getUsername() + " received "
            + economyBridge.format(pending));
        return pending;
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
     * Gives items to a player. Placeholder that will be wired up with full
     * Hytale inventory API when Player entity is available in the call chain.
     *
     * In production: player.getInventoryManager().getInventory().addItemStack(new ItemStack(itemId, quantity))
     */
    private void giveItem(PlayerRef playerRef, String itemId, int quantity) {
        // TODO: Wire up with actual Hytale inventory API via Player entity
        // Player player = resolvePlayer(playerRef);
        // if (player != null) {
        //     player.getInventoryManager().getInventory().addItemStack(new ItemStack(itemId, quantity));
        // }
        LOGGER.fine("giveItem: " + quantity + "x " + itemId + " -> " + playerRef.getUsername());
    }

    /**
     * Removes items from a player's inventory. Placeholder.
     *
     * @return true if the items were successfully removed
     */
    private boolean removeItem(PlayerRef playerRef, String itemId, int quantity) {
        // TODO: Wire up with actual Hytale inventory API via Player entity
        // Player player = resolvePlayer(playerRef);
        // if (player != null) {
        //     return player.getInventoryManager().getInventory().removeItemStack(itemId, quantity);
        // }
        LOGGER.fine("removeItem: " + quantity + "x " + itemId + " <- " + playerRef.getUsername());
        return true;
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
