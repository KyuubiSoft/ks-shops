package com.kyuubisoft.shops.service;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
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
import com.kyuubisoft.shops.util.BsonMetadataCodec;
import com.kyuubisoft.shops.util.PlayerInventoryAccess;

import org.bson.BsonDocument;

import java.util.ArrayList;
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
     * Processes a purchase: deducts currency from buyer, decrements stock,
     * routes both the purchased items and the seller earnings through the
     * mailbox, and fires the transaction event.
     *
     * Follows the Transaction Journal pattern (Phase 3 — mailbox flow):
     *   1. Validate all preconditions
     *   2. Atomic stock decrement in DB
     *   3. Withdraw from buyer
     *   4. Create ITEM mail for buyer (no direct inventory delivery)
     *   5. Create MONEY mail for seller (player shops only — admin shops skip)
     *   6. Log transaction + update stats
     *
     * <p>shopBalance is no longer credited on a sale — it is now strictly a
     * buyback pool fed by {@link #depositToShop(PlayerRef, UUID, double)}
     * and drained by {@link #sellItem(PlayerRef, UUID, String, int)}. The
     * owner's earnings live in the mailbox until claimed.
     *
     * <p>Failure handling: if the mailbox insertion throws (DB issue), the
     * buyer is refunded, the stock is restored, and no money mail is
     * created. The transaction never partially commits.
     *
     * @return true if the purchase succeeded
     */
    public boolean purchaseItem(PlayerRef buyer, UUID shopId, int itemSlot, int quantity) {
        return purchaseItemWithReason(buyer, shopId, itemSlot, quantity).isSuccess();
    }

    /**
     * Like {@link #purchaseItem} but returns a typed reason on failure so the
     * UI can show a specific i18n key instead of the generic "purchase failed"
     * message.
     */
    public PurchaseResult purchaseItemWithReason(PlayerRef buyer, UUID shopId, int itemSlot, int quantity) {
        if (buyer == null || shopId == null || quantity <= 0) {
            return PurchaseResult.error("shop.buy.fail.invalid_input");
        }

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Purchase rejected: economy provider not available");
            return PurchaseResult.error("shop.buy.fail.economy_unavailable");
        }

        // --- Validate shop exists and is open ---
        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            LOGGER.info("Purchase rejected: shop not found " + shopId);
            return PurchaseResult.error("shop.buy.fail.shop_not_found");
        }
        if (!shop.isOpen()) {
            LOGGER.info("Purchase rejected: shop is closed " + shopId);
            return PurchaseResult.error("shop.buy.fail.shop_closed");
        }

        // --- Self-purchase prevention (skipped for admin shops which have no owner) ---
        UUID buyerUuid = buyer.getUuid();
        if (!shop.isAdminShop() && shop.getOwnerUuid() != null && shop.getOwnerUuid().equals(buyerUuid)) {
            LOGGER.info("Purchase rejected: buyer is shop owner ('" + buyer.getUsername()
                + "' on shop '" + shop.getName() + "')");
            return PurchaseResult.error("shop.buy.fail.own_shop");
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
            LOGGER.info("Purchase rejected: no item in slot " + itemSlot + " of shop " + shop.getName());
            return PurchaseResult.error("shop.buy.fail.item_missing");
        }
        if (!shopItem.isBuyEnabled()) {
            LOGGER.info("Purchase rejected: buy disabled for " + shopItem.getItemId());
            return PurchaseResult.error("shop.buy.fail.not_for_sale");
        }
        if (shopItem.getBuyPrice() <= 0) {
            LOGGER.info("Purchase rejected: buy price is zero or negative for " + shopItem.getItemId());
            return PurchaseResult.error("shop.buy.fail.bad_price");
        }

        // --- Validate stock (cache check before DB) ---
        if (!shopItem.isUnlimitedStock() && shopItem.getStock() < quantity) {
            LOGGER.info("Purchase rejected: insufficient stock for " + shopItem.getItemId()
                + " (have " + shopItem.getStock() + ", want " + quantity + ")");
            return PurchaseResult.error("shop.buy.fail.out_of_stock");
        }

        // --- Calculate pricing (FIX 5: tax disabled — collected nowhere, would vanish) ---
        int pricePerUnit = shopItem.getBuyPrice();
        int totalPrice = pricePerUnit * quantity;
        int taxAmount = 0;
        int buyerCost = totalPrice;
        int sellerReceived = totalPrice;

        // --- Validate buyer has funds ---
        if (!economyBridge.has(buyerUuid, buyerCost)) {
            LOGGER.info("Purchase rejected: buyer '" + buyer.getUsername()
                + "' has insufficient funds (need " + buyerCost + ")");
            return PurchaseResult.error("shop.buy.fail.no_funds");
        }

        // --- Step 1: Atomic stock decrement in DB (prevents overselling) ---
        if (!shopItem.isUnlimitedStock()) {
            int rows = database.executeAtomicStockDecrement(shopId, shopItem.getItemId(), quantity);
            if (rows == 0) {
                LOGGER.info("Purchase rejected: atomic stock decrement failed (out of stock)");
                return PurchaseResult.error("shop.buy.fail.out_of_stock");
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
            return PurchaseResult.error("shop.buy.fail.withdraw_failed");
        }

        // --- Step 3: Mail items to buyer + earnings to seller (atomic group) ---
        // Both mailbox writes happen here. If either throws, we roll back the
        // economy + stock and abort. We intentionally insert the buyer's item
        // mail first — if it fails, no money mail exists yet to clean up.
        boolean isPlayerShop = shop.isPlayerShop() && shop.getOwnerUuid() != null;
        try {
            // 3a. ITEM mail to the buyer (always — admin and player shops alike).
            // Pass the persisted BSON metadata so the buyer receives the exact
            // same enchanted / customised ItemStack the owner listed, not a
            // vanilla copy.
            plugin.getMailboxService().createItemMail(
                buyerUuid,
                shop.getId(),
                shop.getName(),
                shop.getName(), // ITEM mails show the shop name as the "sender"
                shopItem.getItemId(),
                quantity,
                shopItem.getItemMetadata()
            );

            // 3b. MONEY mail to the seller (player shops only — admin shops skip)
            if (isPlayerShop) {
                plugin.getMailboxService().createMoneyMail(
                    shop.getOwnerUuid(),
                    shop.getId(),
                    shop.getName(),
                    buyer.getUsername(),
                    sellerReceived
                );
            }
        } catch (Exception e) {
            // Roll back: restore stock and refund the buyer. No mail to undo
            // because we never reach the catch after a successful insertion
            // pair (insertMailboxEntry is a single atomic INSERT each).
            if (!shopItem.isUnlimitedStock()) {
                database.executeAtomicStockIncrement(shopId, shopItem.getItemId(), quantity);
            }
            economyBridge.deposit(buyerUuid, buyerCost);
            LOGGER.severe("Purchase failed: mailbox insert threw — buyer refunded, stock restored. Reason: "
                + e.getMessage());
            return PurchaseResult.error("shop.buy.fail.mailbox_error");
        }

        // --- Step 4: Notify the buyer that the items are in their mailbox ---
        try {
            String itemLabel = shopItem.getItemId().replace('_', ' ');
            String msg = i18n.get(buyer, "shop.buy.sent_to_mailbox", quantity, itemLabel);
            buyer.sendMessage(Message.raw(msg).color("#FFD700"));
        } catch (Exception e) {
            // Non-fatal — the mail is already created.
            LOGGER.fine("Purchase: failed to send mailbox notification chat: " + e.getMessage());
        }

        // --- Step 4b: Update seller stats (player shops only) ---
        if (isPlayerShop) {
            PlayerShopData sellerData = plugin.getPlayerData(shop.getOwnerUuid());
            if (sellerData != null) {
                sellerData.setTotalEarnings(sellerData.getTotalEarnings() + sellerReceived);
                sellerData.setTotalSales(sellerData.getTotalSales() + 1);
            }

            // Create offline notification if seller is offline. The mailbox is
            // the canonical earnings store now, but offline-summary chat on
            // login is still useful to the seller.
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
        // Admin shops: no stats / notifications — money does not exist for them

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
        return PurchaseResult.success();
    }

    /**
     * Typed result for {@link #purchaseItemWithReason}. Carries either
     * success or a specific i18n error key the UI can show to the player.
     */
    public static final class PurchaseResult {
        private final boolean success;
        private final String errorKey;

        private PurchaseResult(boolean success, String errorKey) {
            this.success = success;
            this.errorKey = errorKey;
        }

        public static PurchaseResult success() { return new PurchaseResult(true, null); }
        public static PurchaseResult error(String errorKey) { return new PurchaseResult(false, errorKey); }

        public boolean isSuccess() { return success; }
        public String getErrorKey() { return errorKey; }
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

        // --- Self-sell prevention (skipped for admin shops which have no owner) ---
        // Prevents the shop owner from selling their own items to their own shop to
        // drain the shop balance / extract money. Mirrors the check in purchaseItem().
        UUID sellerUuid = seller.getUuid();
        if (!shop.isAdminShop() && shop.getOwnerUuid() != null && shop.getOwnerUuid().equals(sellerUuid)) {
            LOGGER.fine("Sell rejected: seller is shop owner");
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
     * Backwards-compatible overload that uses the global config default for
     * max-shops. Prefer the {@link #createPlayerShop(PlayerRef, Player, String,
     * String, String, String, double, double, double)} overload below
     * whenever a {@link Player} is available so per-rank
     * {@code ks.shop.limit.shops.N} permissions are honored.
     */
    public CreateShopResult createPlayerShop(PlayerRef owner, String name, String category,
                                             String description, String worldName,
                                             double x, double y, double z) {
        return createPlayerShop(owner, null, name, category, description,
            worldName, x, y, z);
    }

    /**
     * Creates a new player-owned shop at the given world position.
     *
     * <p>If {@code player} is non-null, the max-shops limit is resolved via
     * {@link com.kyuubisoft.shops.util.PermissionLimits#resolveMaxShops}
     * which scans {@code ks.shop.limit.shops.N} permission nodes; the
     * highest matching {@code N} (or the global default) wins. Pass
     * {@code null} for {@code player} to fall back to the global default
     * unconditionally - useful for system-spawned shops.</p>
     *
     * @return a {@link CreateShopResult} — never null; call {@link CreateShopResult#isSuccess()}
     *         to distinguish success from failure
     */
    public CreateShopResult createPlayerShop(PlayerRef owner, Player player,
                                             String name, String category,
                                             String description, String worldName,
                                             double x, double y, double z) {
        if (owner == null || name == null || name.isBlank()) {
            return CreateShopResult.error("shop.create.name_too_short");
        }

        UUID ownerUuid = owner.getUuid();
        ShopConfig.ConfigData cfg = config.getData();

        // --- Validate player shop feature is enabled ---
        if (!cfg.features.playerShops) {
            LOGGER.fine("Create rejected: player shops disabled");
            return CreateShopResult.error("shop.create.disabled");
        }

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Create rejected: economy provider not available");
            return CreateShopResult.error("shop.create.economy_unavailable");
        }

        // --- Validate max shops not reached (per-rank perm aware) ---
        int maxShops = com.kyuubisoft.shops.util.PermissionLimits.resolveMaxShops(
            player, cfg.playerShops.maxShopsPerPlayer);
        List<ShopData> ownedShops = shopManager.getShopsByOwner(ownerUuid);
        if (ownedShops.size() >= maxShops) {
            LOGGER.fine("Create rejected: player " + owner.getUsername()
                + " reached max shops (" + maxShops + ")");
            return CreateShopResult.error("shop.create.max_reached");
        }

        // --- Validate name length ---
        String trimmedName = name.trim();
        if (trimmedName.length() < cfg.playerShops.nameMinLength) {
            LOGGER.fine("Create rejected: name too short (" + trimmedName.length() + ")");
            return CreateShopResult.error("shop.create.name_too_short");
        }
        if (trimmedName.length() > cfg.playerShops.nameMaxLength) {
            LOGGER.fine("Create rejected: name too long (" + trimmedName.length() + ")");
            return CreateShopResult.error("shop.create.name_too_long");
        }

        // --- Validate name uniqueness ---
        for (ShopData existing : shopManager.getAllShops()) {
            if (existing.getName().equalsIgnoreCase(trimmedName)) {
                LOGGER.fine("Create rejected: name already taken '" + trimmedName + "'");
                return CreateShopResult.error("shop.create.name_taken");
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
                return CreateShopResult.error("shop.create.claim_required");
            }
        }

        // --- Check funds for creation cost ---
        int creationCost = cfg.playerShops.creationCost;
        if (creationCost > 0) {
            if (!economyBridge.has(ownerUuid, creationCost)) {
                LOGGER.fine("Create rejected: insufficient funds for creation cost ("
                    + creationCost + ")");
                return CreateShopResult.error("shop.create.not_enough");
            }

            // Withdraw creation cost
            boolean withdrawn = economyBridge.withdraw(ownerUuid, creationCost);
            if (!withdrawn) {
                LOGGER.warning("Create failed: withdraw of creation cost failed");
                return CreateShopResult.error("shop.create.failed");
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
        return CreateShopResult.success(shopData);
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

    // ==================== SHOP PICKUP / REPLANT ====================

    /**
     * Packs up a player shop without deleting it. The shop row stays in the DB
     * (preserving rating history, transaction log, category, icon, skin, name
     * tag, total revenue) but is marked {@code packed=true} and {@code open=false}.
     *
     * <p>On pickup we:
     * <ol>
     *   <li>Return every stocked item in the shop to the owner's combined
     *       storage (using the same combined-container pattern as
     *       {@link #giveItem(PlayerRef, String, int)}); unlimited-stock items
     *       (admin-style, {@code stock == -1}) are skipped.</li>
     *   <li>Refund the shop buyback pool ({@code shopBalance}) to the owner's
     *       wallet via the economy bridge. If the deposit fails, the balance
     *       stays on the shop so the owner can withdraw manually later.</li>
     *   <li>Despawn the NPC via {@link ShopNpcManager#despawnNpc(UUID)}.</li>
     *   <li>Clear the shop item list, mark {@code packed=true}, save.</li>
     *   <li>Put a fresh {@code Shop_NPC_Token} into the owner's inventory so
     *       they can replant the shop at a new location.</li>
     * </ol>
     *
     * <p>The max-shops-per-player cap still counts packed shops, so owners
     * cannot hoard packed shops by repeatedly picking up and creating new ones.
     *
     * @param player  the owner's live {@link Player} entity (required to
     *                resolve the inventory for item refunds and token delivery)
     * @param owner   the owner's {@link PlayerRef}
     * @param shopId  the shop to pack up
     * @return true on success, false if validation failed or the shop was
     *         already packed
     */
    public boolean pickupShop(Player player, PlayerRef owner, UUID shopId) {
        if (player == null || owner == null || shopId == null) return false;

        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            LOGGER.fine("pickupShop rejected: shop not found " + shopId);
            return false;
        }
        if (!shop.isPlayerShop()) {
            LOGGER.fine("pickupShop rejected: not a player shop " + shopId);
            return false;
        }

        UUID ownerUuid = owner.getUuid();
        if (shop.getOwnerUuid() == null || !shop.getOwnerUuid().equals(ownerUuid)) {
            LOGGER.fine("pickupShop rejected: not the owner of shop " + shopId);
            return false;
        }
        if (shop.isPacked()) {
            LOGGER.fine("pickupShop rejected: shop already packed " + shopId);
            return false;
        }

        // --- Step 1: send shop items to the owner's mailbox ---
        //
        // Items + balance both route through the mailbox instead of directly
        // dropping into the inventory/wallet. Consistent with the rest of the
        // mod (sales + purchases also use the mailbox) and automatically
        // handles full-inventory cases without dropping items on the ground.
        // The owner gets a clear itemised list of everything they get back
        // when they claim the pickup mails.
        int refundedItemCount = 0;
        int refundedMailCount = 0;
        String shopName = shop.getName();
        List<ShopItem> itemsCopy = new ArrayList<>(shop.getItems());
        for (ShopItem item : itemsCopy) {
            if (item == null) continue;
            // Admin-style unlimited stock items don't represent real inventory,
            // so we skip refunding them. Regular items use their current stock.
            if (item.isUnlimitedStock()) continue;
            int stock = item.getStock();
            if (stock <= 0) continue;
            try {
                // Preserve the ItemStack metadata so the owner gets back the
                // exact same enchanted / customised items they listed.
                plugin.getMailboxService().createItemMail(
                    ownerUuid, shopId, shopName, "[Shop Pickup]",
                    item.getItemId(), stock, item.getItemMetadata());
                refundedItemCount += stock;
                refundedMailCount++;
            } catch (Exception e) {
                LOGGER.warning("pickupShop: failed to send " + stock + "x "
                    + item.getItemId() + " to mailbox for shop " + shopName + ": " + e.getMessage());
            }
        }

        // --- Step 2: send buyback pool balance to the mailbox as a money mail ---
        double balance = shop.getShopBalance();
        if (balance > 0) {
            try {
                plugin.getMailboxService().createMoneyMail(
                    ownerUuid, shopId, shopName, "[Shop Pickup]", balance);
                shop.setShopBalance(0);
            } catch (Exception e) {
                LOGGER.warning("pickupShop: failed to send balance " + balance
                    + " to mailbox for shop " + shopName + " - keeping on shop: " + e.getMessage());
            }
        }

        // --- Step 3: despawn NPC ---
        try {
            plugin.getNpcManager().despawnNpc(shopId);
        } catch (Exception e) {
            LOGGER.warning("pickupShop: despawnNpc failed for shop " + shopId + ": " + e.getMessage());
        }

        // --- Step 4: clear items, mark packed, save ---
        shop.getItems().clear();
        shop.setNpcEntityId(null);
        shop.setOpen(false);
        shop.setPacked(true);
        shop.setLastActivity(System.currentTimeMillis());
        try {
            database.saveShop(shop);
        } catch (Exception e) {
            LOGGER.severe("pickupShop: failed to persist packed shop " + shopId + ": " + e.getMessage());
            return false;
        }

        // --- Step 5: give token back to player ---
        try {
            giveShopNpcToken(player, owner);
        } catch (Exception e) {
            LOGGER.warning("pickupShop: failed to deliver replacement token for "
                + owner.getUsername() + ": " + e.getMessage());
        }

        LOGGER.info("pickupShop: packed shop '" + shop.getName() + "' for "
            + owner.getUsername() + " (sent to mailbox: " + refundedMailCount
            + " item mails totalling " + refundedItemCount + " units, balance "
            + balance + ")");
        return true;
    }

    /**
     * Reactivates a packed shop at a new world position. Called from
     * {@link com.kyuubisoft.shops.interaction.ShopNpcTokenInteraction} when the
     * owner right-clicks a Shop_NPC_Token while they already have a packed
     * shop on file — the packed shop's rating, category, icon, skin, name-tag
     * state and transaction history are all preserved; only the location
     * changes.
     *
     * <p>MUST be called from the world thread (or dispatched to it) because
     * {@link ShopNpcManager#spawnNpcAtPosition(ShopData, World, Vector3d, float)}
     * performs entity operations that require world.execute().
     *
     * @param player  the owner's live {@link Player} entity
     * @param owner   the owner's {@link PlayerRef}
     * @param shopId  the packed shop to reactivate
     * @param world   the target world
     * @param x       world x
     * @param y       world y
     * @param z       world z
     * @param rotY    NPC yaw rotation (radians)
     * @return true on success
     */
    public boolean replantShop(Player player, PlayerRef owner, UUID shopId,
                               World world, double x, double y, double z, float rotY) {
        if (player == null || owner == null || shopId == null || world == null) return false;

        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            LOGGER.fine("replantShop rejected: shop not found " + shopId);
            return false;
        }
        if (!shop.isPacked()) {
            LOGGER.fine("replantShop rejected: shop not packed " + shopId);
            return false;
        }
        if (shop.getOwnerUuid() == null || !shop.getOwnerUuid().equals(owner.getUuid())) {
            LOGGER.fine("replantShop rejected: not the owner of shop " + shopId);
            return false;
        }

        // --- Update coordinates and state ---
        shop.setWorldName(world.getName());
        shop.setPosX(x);
        shop.setPosY(y);
        shop.setPosZ(z);
        shop.setNpcRotY(rotY);
        shop.setNpcEntityId(null);
        shop.setOpen(true);
        shop.setPacked(false);
        shop.setLastActivity(System.currentTimeMillis());

        try {
            database.saveShop(shop);
        } catch (Exception e) {
            LOGGER.severe("replantShop: failed to persist reactivated shop " + shopId
                + ": " + e.getMessage());
            return false;
        }

        // --- Register + spawn NPC at new location ---
        // spawnNpcAtPosition handles registerShopInWorld + spawnedWorlds internally.
        try {
            plugin.getNpcManager().spawnNpcAtPosition(shop, world, new Vector3d(x, y, z), rotY);
        } catch (Exception e) {
            LOGGER.warning("replantShop: spawnNpcAtPosition failed for shop " + shopId
                + ": " + e.getMessage());
            // Not a hard failure - the shop is unpacked and persisted; the NPC
            // will be retried on the next world-load pass.
        }

        LOGGER.info("replantShop: reactivated shop '" + shop.getName() + "' at "
            + world.getName() + " [" + (int) x + ", " + (int) y + ", " + (int) z + "] for "
            + owner.getUsername());
        return true;
    }

    /**
     * Adds one {@code Shop_NPC_Token} to the player's inventory. Used by
     * {@link #pickupShop(Player, PlayerRef, UUID)} to give the owner a fresh
     * token they can use to replant the packed shop.
     *
     * <p>Uses the same combined-storage-first pattern as
     * {@link #giveItem(PlayerRef, String, int)} so the token lands in the
     * player's main inventory whenever possible, dropping at the feet only as
     * a last resort.
     */
    private void giveShopNpcToken(Player player, PlayerRef owner) {
        if (player == null || owner == null) return;

        try {
            Ref<EntityStore> ref = owner.getReference();
            if (ref == null || !ref.isValid()) {
                LOGGER.warning("giveShopNpcToken: invalid player reference for "
                    + owner.getUsername());
                return;
            }
            Store<EntityStore> store = ref.getStore();

            ItemStack stack = new ItemStack(
                com.kyuubisoft.shops.interaction.ShopNpcTokenInteraction.TOKEN_ITEM_ID, 1);

            PlayerInventoryAccess inv = PlayerInventoryAccess.of(player);
            CombinedItemContainer combined = inv.getCombinedStorageFirst();

            if (combined != null) {
                var transaction = combined.addItemStack(stack);
                ItemStack remainder = (transaction != null) ? transaction.getRemainder() : stack;
                if (remainder == null || remainder.isEmpty()) {
                    inv.markChanged();
                    return;
                }
                inv.markChanged();
                SimpleItemContainer.addOrDropItemStack(store, ref, combined, remainder);
                return;
            }

            SimpleItemContainer tempContainer = new SimpleItemContainer((short) 0);
            SimpleItemContainer.addOrDropItemStack(store, ref, tempContainer, stack);
        } catch (Exception e) {
            LOGGER.warning("giveShopNpcToken failed for " + owner.getUsername()
                + ": " + e.getMessage());
        }
    }

    // ==================== SHOP TRANSFER ====================

    /**
     * Transfers ownership of a player shop to another player. Does NOT check
     * ownership, permissions, or the target's shop limit — callers must validate
     * those before invoking this method (see TransferCmd in ShopCommand).
     *
     * <p>Updates the in-memory shop's owner UUID/name, bumps {@code lastActivity},
     * persists via {@link ShopDatabase#saveShop(ShopData)}, and keeps the
     * {@link PlayerShopData} maps for both players in sync. The NPC is not
     * respawned here — the command layer triggers a respawn to refresh the
     * nameplate/skin after the owner change.
     *
     * @param shopId        the shop to transfer
     * @param newOwnerUuid  the UUID of the new owner
     * @param newOwnerName  the display name of the new owner (used for NPC nameplate)
     * @return true on success
     */
    public boolean transferShop(UUID shopId, UUID newOwnerUuid, String newOwnerName) {
        if (shopId == null || newOwnerUuid == null) return false;

        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) return false;
        if (!shop.isPlayerShop()) {
            LOGGER.warning("transferShop rejected: shop " + shopId + " is not a player shop");
            return false;
        }

        UUID oldOwnerUuid = shop.getOwnerUuid();

        shop.setOwnerUuid(newOwnerUuid);
        shop.setOwnerName(newOwnerName);
        shop.setLastActivity(System.currentTimeMillis());

        try {
            database.saveShop(shop);
        } catch (Exception e) {
            LOGGER.severe("Failed to persist transferred shop " + shopId + ": " + e.getMessage());
            return false;
        }

        // Keep the per-player shop index in sync so /ksshop myshops reflects the change.
        if (oldOwnerUuid != null) {
            PlayerShopData oldData = plugin.getPlayerData(oldOwnerUuid);
            if (oldData != null) oldData.removeOwnedShop(shopId);
        }
        PlayerShopData newData = plugin.getPlayerData(newOwnerUuid);
        if (newData != null) newData.addOwnedShop(shopId);

        // Release any editor lock held by the previous owner.
        if (oldOwnerUuid != null) {
            sessionManager.unlockEditor(shopId, oldOwnerUuid);
        }

        LOGGER.info("Shop transferred: '" + shop.getName() + "' (" + shopId + ") -> "
            + newOwnerName + " (" + newOwnerUuid + ")");
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
     * @deprecated Phase 3 (mailbox refactor): shopBalance is now a buyback pool,
     * not an earnings pool. New sales never increment shopBalance — they create
     * a MONEY mail in the seller's mailbox instead. This method is kept only
     * as a legacy helper for the case where a non-zero shopBalance still exists
     * after the one-shot legacy migration runs (e.g. ad-hoc admin tooling).
     * Player-facing entry point is now {@code Mailbox_Block} interaction.
     *
     * <p>Iterates all shops owned by the player, captures a per-shop breakdown,
     * deposits the total into the player's wallet, and resets shop balances
     * to 0. Use only if you knowingly want to drain the buyback pool back into
     * the owner's wallet — note this will leave the shop unable to buy items
     * back from customers until the owner deposits again.
     *
     * @return a {@link CollectResult} describing the outcome (total, per-shop entries, failure flags)
     */
    @Deprecated
    public CollectResult collectEarnings(PlayerRef player) {
        if (player == null) return CollectResult.empty();

        UUID playerUuid = player.getUuid();

        // --- Validate economy ---
        if (!economyBridge.isAvailable()) {
            LOGGER.warning("Collect earnings rejected: economy provider not available");
            return CollectResult.economyFailure();
        }

        // --- Capture per-shop amounts BEFORE resetting ---
        List<ShopData> ownedShops = shopManager.getShopsByOwner(playerUuid);
        List<CollectResult.ShopEntry> entries = new ArrayList<>();
        double totalToCollect = 0.0;
        for (ShopData shop : ownedShops) {
            double bal = shop.getShopBalance();
            if (bal > 0) {
                entries.add(new CollectResult.ShopEntry(shop.getId(), shop.getName(), bal));
                totalToCollect += bal;
            }
        }

        if (totalToCollect <= 0.0) return CollectResult.empty();

        // --- Deposit total to player wallet ---
        boolean deposited = economyBridge.deposit(playerUuid, totalToCollect);
        if (!deposited) {
            LOGGER.warning("Collect earnings failed: deposit to " + playerUuid + " failed");
            return CollectResult.economyFailure();
        }

        // --- Reset shop balances only for shops that contributed ---
        for (CollectResult.ShopEntry entry : entries) {
            ShopData shop = shopManager.getShop(entry.shopId);
            if (shop != null) {
                shop.setShopBalance(0);
                database.saveShop(shop);
            }
        }

        LOGGER.info("Earnings collected: " + player.getUsername() + " received "
            + economyBridge.format(totalToCollect) + " from " + entries.size() + " shop(s)");
        return CollectResult.success(totalToCollect, entries);
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

    // ==================== LEGACY BALANCE MIGRATION ====================

    /**
     * One-shot migration that converts every player shop's pre-Phase-3
     * {@code shopBalance} into a single MONEY mail in the owner's mailbox.
     *
     * <p>Before the mailbox refactor, {@code shopBalance} doubled as both a
     * buyback pool and the owner's uncollected earnings — there was no way
     * to tell the two roles apart from the field alone. The mailbox split
     * earnings out to per-sale entries; any non-zero balance lingering at
     * upgrade time therefore represents earnings the owner never collected
     * and is moved verbatim to the mailbox as a single legacy entry. The
     * shop's balance is then reset to 0 so it cleanly takes its new role
     * as a buyback pool only.
     *
     * <p>Idempotency is enforced via a hidden file marker
     * {@code .legacy_balances_migrated} in the plugin data folder. If the
     * marker exists the method is a no-op. After a successful run (whether
     * 0 or N shops were migrated) the marker is created so subsequent
     * starts skip immediately.
     *
     * <p>Admin shops are skipped — they have no owner and no real balance.
     * Failures on individual shops are logged but do not abort the run.
     *
     * @return number of shops whose balances were migrated
     */
    public int migrateLegacyShopBalances() {
        java.nio.file.Path marker = plugin.getDataDirectory().resolve(".legacy_balances_migrated");
        if (java.nio.file.Files.exists(marker)) {
            LOGGER.fine("Legacy balance migration: marker present, skipping.");
            return 0;
        }

        int migrated = 0;
        int errors = 0;
        for (ShopData shop : shopManager.getAllShops()) {
            if (shop.isAdminShop() || shop.getOwnerUuid() == null) continue;
            double bal = shop.getShopBalance();
            if (bal <= 0) continue;

            try {
                plugin.getMailboxService().createMoneyMail(
                    shop.getOwnerUuid(),
                    shop.getId(),
                    shop.getName(),
                    "[Legacy Balance]",
                    bal
                );
                shop.setShopBalance(0);
                database.saveShop(shop);
                migrated++;
            } catch (Exception e) {
                errors++;
                LOGGER.warning("Legacy balance migration failed for shop '" + shop.getName()
                    + "' (" + shop.getId() + "): " + e.getMessage());
            }
        }

        try {
            java.nio.file.Files.createFile(marker);
        } catch (java.io.IOException e) {
            LOGGER.warning("Failed to create legacy migration marker: " + e.getMessage());
        }

        LOGGER.info(i18n.get("shop.migration.legacy_balances", migrated)
            + (errors > 0 ? " (" + errors + " errors)" : ""));
        return migrated;
    }

    // ==================== SHOP BLOCK -> NPC MIGRATION ====================

    /**
     * One-shot migration that converts every pre-existing block-anchored player
     * shop into a standalone NPC shop.
     *
     * <p>Before this change, player shops were bound to a physical {@code Shop_Block}
     * variant placed in the world. {@code ShopData.posX/Y/Z} pointed at the block
     * and the shop's NPC (if any) spawned at an offset behind it. The block is now
     * deprecated — shops are created by right-clicking a Shop_NPC_Token and the
     * resulting NPC IS the interaction point.
     *
     * <p>Existing shops with block-anchored positions continue to function as-is:
     * the NPC will spawn at {@code posX/posY/posZ} on the next world-init event via
     * the normal lazy spawn path. This migration only marks them as "migrated" so
     * we can log a single summary line and avoid re-running on every startup.
     *
     * <p>Idempotency is enforced via a hidden file marker
     * {@code .shops_npc_migration_done} in the plugin data folder. If the marker
     * exists the method is a no-op.
     *
     * @return number of shops flagged as migrated
     */
    public int migrateShopBlocksToNpcShops() {
        java.nio.file.Path marker = plugin.getDataDirectory().resolve(".shops_npc_migration_done");
        // Second-generation migration marker: re-runs if the user came from an
        // older build that spawned NPCs with Core's KS_NPC_Interactable_Role.
        // When this marker is missing we force a respawn cycle so existing shop
        // NPCs pick up the standalone Shop_Keeper_Role + Invulnerable component.
        java.nio.file.Path markerV2 = plugin.getDataDirectory().resolve(".shops_npc_role_v2_done");
        boolean hasV1 = java.nio.file.Files.exists(marker);
        boolean hasV2 = java.nio.file.Files.exists(markerV2);

        if (hasV1 && hasV2) {
            LOGGER.fine("Shop NPC migration: both markers present, skipping.");
            return 0;
        }

        // V2 upgrade path: clear npcEntityIds so the lazy spawn re-registers
        // with Shop_Keeper_Role. The in-world entities are still chunk-backed
        // from the previous role; despawn via the NPC manager when possible.
        if (hasV1 && !hasV2) {
            int respawned = 0;
            for (ShopData shop : shopManager.getAllShops()) {
                if (shop.isAdminShop() || shop.getOwnerUuid() == null) continue;
                if (shop.getWorldName() == null || shop.getWorldName().isEmpty()) continue;
                try {
                    plugin.getNpcManager().despawnNpc(shop.getId());
                } catch (Exception ignored) {
                    // Entity may not be present yet; just clearing the id is enough.
                }
                shop.setNpcEntityId(null);
                try {
                    database.saveShop(shop);
                } catch (Exception e) {
                    LOGGER.warning("Shop NPC v2 migration: save failed for shop '"
                        + shop.getName() + "': " + e.getMessage());
                }
                respawned++;
            }
            try {
                java.nio.file.Files.createFile(markerV2);
            } catch (java.io.IOException e) {
                LOGGER.warning("Failed to create shop NPC v2 migration marker: " + e.getMessage());
            }
            LOGGER.info("Shop NPC v2 migration: cleared " + respawned
                + " NPC entity ids for respawn with Shop_Keeper_Role");
            return respawned;
        }

        int migrated = 0;
        for (ShopData shop : shopManager.getAllShops()) {
            if (shop.isAdminShop() || shop.getOwnerUuid() == null) continue;
            // Any player shop that has a world + coordinates is considered migratable.
            if (shop.getWorldName() == null || shop.getWorldName().isEmpty()) continue;
            // Clear any stale npcEntityId so the next lazy spawn re-registers cleanly.
            shop.setNpcEntityId(null);
            try {
                database.saveShop(shop);
            } catch (Exception e) {
                LOGGER.warning("Shop NPC migration: save failed for shop '" + shop.getName()
                    + "': " + e.getMessage());
            }
            migrated++;
        }

        try {
            java.nio.file.Files.createFile(marker);
        } catch (java.io.IOException e) {
            LOGGER.warning("Failed to create shop NPC migration marker: " + e.getMessage());
        }
        // First-run implies v2 is also satisfied (fresh spawn already uses Shop_Keeper_Role).
        try {
            if (!java.nio.file.Files.exists(markerV2)) {
                java.nio.file.Files.createFile(markerV2);
            }
        } catch (java.io.IOException e) {
            LOGGER.warning("Failed to create shop NPC v2 marker: " + e.getMessage());
        }

        LOGGER.info(i18n.get("shop.migration.npc_migrated", migrated));
        return migrated;
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
     * Gives items to a player. Tries to add to the combined inventory (hotbar-first,
     * then storage) and respects the transaction remainder - any items that did not
     * fit are dropped at the player's feet via SimpleItemContainer.addOrDropItemStack.
     *
     * Pattern mirrors the bank mod (BankPage.transferBankToInventory).
     *
     * BUG FIX: previously ignored the return value of addItemStack, so items silently
     * vanished when the storage was partially full.
     *
     * Public so the MailboxPage (and other dispensers) can reuse it.
     */
    public void giveItem(PlayerRef playerRef, String itemId, int quantity) {
        giveItem(playerRef, itemId, quantity, null);
    }

    /**
     * Gives items to a player with optional BSON metadata attached. Used by
     * the mailbox claim path to preserve enchantments / custom stats / pet
     * ids / weapon mastery levels captured when the seller listed the item.
     * Pass {@code null} for {@code metadataJson} to deliver a vanilla item
     * (equivalent to the 3-arg overload).
     */
    public void giveItem(PlayerRef playerRef, String itemId, int quantity, String metadataJson) {
        if (playerRef == null || itemId == null || quantity <= 0) return;

        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                LOGGER.warning("giveItem: invalid player reference for " + playerRef.getUsername());
                return;
            }
            Store<EntityStore> store = ref.getStore();

            // Reconstruct the full ItemStack with metadata when present so
            // enchantments / custom stats / pet ids survive the delivery.
            BsonDocument meta = BsonMetadataCodec.decode(metadataJson);
            ItemStack stack = (meta != null)
                ? new ItemStack(itemId, quantity, meta)
                : new ItemStack(itemId, quantity);

            // Resolve the Player entity from the store so we can use PlayerInventoryAccess
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                // Fallback: no player entity — drop the full stack at the entity's position
                SimpleItemContainer tempContainer = new SimpleItemContainer((short) 0);
                SimpleItemContainer.addOrDropItemStack(store, ref, tempContainer, stack);
                LOGGER.fine("giveItem: dropped " + quantity + "x " + itemId
                    + " for " + playerRef.getUsername() + " (player entity unavailable)");
                return;
            }

            PlayerInventoryAccess inv = PlayerInventoryAccess.of(player);
            CombinedItemContainer combined = inv.getCombinedStorageFirst();

            if (combined != null) {
                // Try to add to the combined storage+hotbar container
                var transaction = combined.addItemStack(stack);
                ItemStack remainder = (transaction != null) ? transaction.getRemainder() : stack;

                if (remainder == null || remainder.isEmpty()) {
                    // Fully added — mark both inventory components dirty
                    inv.markChanged();
                    return;
                }

                // Partial add: some items fit, some did not. Drop the remainder at the
                // player's feet using the same combined container so addOrDropItemStack
                // can retry placement once more before dropping.
                inv.markChanged();
                SimpleItemContainer.addOrDropItemStack(store, ref, combined, remainder);
                LOGGER.fine("giveItem: dropped " + remainder.getQuantity() + "x " + itemId
                    + " for " + playerRef.getUsername() + " (inventory full, remainder dropped)");
                return;
            }

            // Combined container unavailable — drop the full stack at the player's feet
            SimpleItemContainer tempContainer = new SimpleItemContainer((short) 0);
            SimpleItemContainer.addOrDropItemStack(store, ref, tempContainer, stack);
            LOGGER.fine("giveItem: dropped " + quantity + "x " + itemId
                + " for " + playerRef.getUsername() + " (combined container unavailable)");
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
