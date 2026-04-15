package com.kyuubisoft.shops.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.ShopItem;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.util.BsonMetadataCodec;

import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Shop editor page with drag&drop item management.
 *
 * Uses {@code openCustomPageWithWindows()} with a {@link com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow}
 * for native item grid drag & drop.
 *
 * STAGING PATTERN:
 * <ul>
 *   <li>On open: shop items are copied into a staging SimpleItemContainer.</li>
 *   <li>Player drags items between staging container and their inventory.</li>
 *   <li>On "Save": staging is committed to real ShopData + DB.</li>
 *   <li>On close without save: staging items are returned to player inventory.</li>
 * </ul>
 *
 * Usage:
 * <pre>
 *   ShopEditPage page = new ShopEditPage(playerRef, player, plugin, shopData);
 *   ContainerWindow window = new ContainerWindow(page.getStagingContainer());
 *   player.getPageManager().openCustomPageWithWindows(ref, store, page, window);
 * </pre>
 */
public class ShopEditPage extends InteractiveCustomUIPage<ShopEditPage.EditData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    static final int SHOP_SLOTS_PER_PAGE = 45; // 9 cols x 5 rows, matching 488x260 grid
    private static final int HISTORY_PER_PAGE = 8;

    private static final String[] CATEGORIES = {
        "weapons", "armor", "tools", "resources", "potions", "food", "building", "misc"
    };

    private static final String[] CATEGORY_DISPLAY = {
        "Weapons", "Armor", "Tools", "Resources", "Potions", "Food", "Building", "Misc"
    };

    /**
     * Curated 6x4 grid of icons for the shop avatar picker.
     * MUST stay in lock-step with #IconPick0..#IconPick23 in ShopEdit.ui.
     * All entries verified against external_res/dev-export/items.json.
     */
    static final String[] ICON_OPTIONS = {
        // Row 1 - Weapons
        "Weapon_Sword_Iron", "Weapon_Axe_Iron", "Weapon_Spear_Iron",
        "Weapon_Shield_Iron", "Weapon_Crossbow_Iron", "Weapon_Battleaxe_Iron",
        // Row 2 - Armor
        "Armor_Iron_Head", "Armor_Iron_Chest", "Armor_Iron_Hands",
        "Armor_Iron_Legs", "Tool_Pickaxe_Iron", "Tool_Hatchet_Iron",
        // Row 3 - Tools and bars
        "Tool_Hammer_Iron", "Ingredient_Bar_Iron", "Ingredient_Bar_Gold",
        "Ingredient_Bar_Silver", "Ingredient_Crystal_Blue", "Ingredient_Crystal_Red",
        // Row 4 - Crystals, food, materials
        "Ingredient_Crystal_Green", "Food_Bread", "Food_Cheese",
        "Plant_Fruit_Apple", "Ingredient_Leather_Medium", "Ingredient_Feathers_Light"
    };

    private enum Tab { SETTINGS, PRICING, REVENUE, HISTORY }

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;
    private final ShopData shopData;

    // Staging container for drag&drop shop item editing
    private final SimpleItemContainer stagingContainer;

    // Staging metadata: maps staging slot -> ShopItem price/mode configuration
    private final StagedItemMeta[] stagingMeta;

    // UI state
    private Tab activeTab = Tab.SETTINGS;
    private int shopPage = 0;
    private int historyPage = 0;
    private int selectedSlot = -1;
    private boolean saved = false;

    // BUG #11: Track whether the owner has made any pending edits that would be lost on dismiss.
    // Set true by every mutation path (drop, remove, price/quota/stock/mode/name/desc edits);
    // cleared to false on successful save. Checked in onDismiss() to warn the owner via chat
    // if they close the editor with unsaved changes.
    private boolean dirty = false;

    // Edited fields (staged until Save)
    private String editedName;
    private String editedDesc;
    private String editedCategory;
    private String editedIconItemId;
    private String editedNpcSkin;
    private String pendingDepositAmount = "";

    // FIX 1: Snapshot of original shop items at open time (multiset by itemId).
    // Used by getStagingOnlyItems() to determine which items are NEW in the staging
    // container (and must be returned to the player on discard). Per-slot equality
    // doesn't work because the user may have moved an original item to a different slot.
    private final Map<String, Integer> originalItemCounts = new HashMap<>();

    // FIX 4: Track which item IDs were unlimited stock at open time, so handleSave()
    // can preserve unlimited (-1) instead of clobbering it with the staging quantity.
    private final Set<String> originalUnlimitedItems = new HashSet<>();

    // FIX 5: Only push text-field values to the client when the server has a "fresh"
    // value the client doesn't know yet. Otherwise refreshUI() triggered by an unrelated
    // event would clobber whatever the user is currently typing. Two flags so slot changes
    // can refresh per-item fields without clobbering global text fields (name/desc/category).
    private boolean pushGlobalFields = true; // name, desc, category — pushed once at open
    private boolean pushItemFields = true;   // per-slot price/stock/quota — pushed on slot change

    // Cached refs for post-build updates
    private Ref<EntityStore> buildRef;
    private Store<EntityStore> buildStore;

    public ShopEditPage(PlayerRef playerRef, Player player, ShopPlugin plugin, ShopData shopData) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EditData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.shopData = shopData;

        // Initialize edited fields from current shop data
        this.editedName = shopData.getName() != null ? shopData.getName() : "";
        this.editedDesc = shopData.getDescription() != null ? shopData.getDescription() : "";
        String rawCategory = shopData.getCategory();
        this.editedCategory = isValidCategory(rawCategory) ? rawCategory : "misc";
        this.editedIconItemId = shopData.getIconItemId();
        this.editedNpcSkin = shopData.getNpcSkinUsername() != null ? shopData.getNpcSkinUsername() : "";

        // Create staging container matching the grid (9 cols x 5 rows = 45 slots)
        int containerSlots = SHOP_SLOTS_PER_PAGE; // 45
        this.stagingContainer = new SimpleItemContainer((short) containerSlots);
        this.stagingMeta = new StagedItemMeta[containerSlots];

        // Copy existing shop items into staging container
        populateStagingFromShop();

        // FIX 1 + FIX 4: Snapshot original counts (multiset) and unlimited-stock IDs.
        // Read directly from shopData so we get the authoritative pre-edit state.
        for (ShopItem item : shopData.getItems()) {
            int qty = item.isUnlimitedStock() ? 1 : Math.max(1, item.getStock());
            originalItemCounts.merge(item.getItemId(), qty, Integer::sum);
            if (item.isUnlimitedStock()) {
                originalUnlimitedItems.add(item.getItemId());
            }
        }
    }

    /**
     * Returns the staging container for use with ContainerWindow.
     */
    public SimpleItemContainer getStagingContainer() {
        return stagingContainer;
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/ShopEdit.ui");
        this.buildRef = ref;
        this.buildStore = store;

        bindAllEvents(events);
        buildUI(ui, ref, store);

        // Configure native ItemGrid drag & drop
        ui.set("#ShopGrid.AreItemsDraggable", true);
        ui.set("#ShopGrid.DisplayItemQuantity", true);
        ui.set("#ShopGrid.InventorySectionId", 0);

        ui.set("#InvGrid.AreItemsDraggable", true);
        ui.set("#InvGrid.DisplayItemQuantity", true);
        ui.set("#InvGrid.InventorySectionId", -2);

        ui.set("#HotbarGrid.AreItemsDraggable", true);
        ui.set("#HotbarGrid.DisplayItemQuantity", true);
        ui.set("#HotbarGrid.InventorySectionId", -1);

        // Note: Unlike BankItemContainer (DelegateItemContainer with custom callback),
        // we use raw SimpleItemContainer here. Grid refresh after drag&drop is handled
        // via the Dropped/SlotClicking event bindings in bindAllEvents() which trigger
        // a full UI refresh through handleDataEvent().
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull EditData data) {
        super.handleDataEvent(ref, store, data);

        // Grid drop/click actions (native ItemGrid events)
        if (data.action != null) {
            LOGGER.info("[ShopEdit] Grid action: " + data.action
                + " slot=" + data.slotIndex + " srcSlot=" + data.sourceSlotId
                + " srcSection=" + data.sourceSectionId);
            switch (data.action) {
                case "drop_shop" -> {
                    // Item dropped onto shop grid
                    if (data.slotIndex != null && data.sourceSlotId != null) {
                        handleDrop(ref, store, data.sourceSectionId, data.sourceSlotId, 0, data.slotIndex);
                    } else {
                        refreshUI();
                    }
                }
                case "drop_inv" -> {
                    // Item dropped onto inventory
                    if (data.slotIndex != null && data.sourceSlotId != null) {
                        handleDrop(ref, store, data.sourceSectionId, data.sourceSlotId, -2, data.slotIndex);
                    } else {
                        refreshUI();
                    }
                }
                case "drop_hotbar" -> {
                    // Item dropped onto hotbar
                    if (data.slotIndex != null && data.sourceSlotId != null) {
                        handleDrop(ref, store, data.sourceSectionId, data.sourceSlotId, -1, data.slotIndex);
                    } else {
                        refreshUI();
                    }
                }
                case "click_shop" -> {
                    if (data.slotIndex != null) {
                        int actualSlot = shopPage * SHOP_SLOTS_PER_PAGE + data.slotIndex;
                        handleSelectSlot(String.valueOf(actualSlot));
                    } else {
                        refreshUI();
                    }
                }
                case "click_inv" -> refreshUI();
                default -> this.sendUpdate(new UICommandBuilder(), false);
            }
            return;
        }

        // Tab switching
        if (data.tab != null) {
            handleTabSwitch(data.tab);
            return;
        }

        // Slot selection in shop grid
        if (data.select != null) {
            handleSelectSlot(data.select);
            return;
        }

        // Mode change for selected item
        if (data.mode != null) {
            handleModeChange(data.mode);
            return;
        }

        // Icon picker tile click
        if (data.iconPick != null) {
            handleIconPick(data.iconPick);
            return;
        }

        // Button actions
        if (data.button != null) {
            switch (data.button) {
                case "save" -> handleSave(ref, store);
                case "toggleOpen" -> handleToggleOpen();
                case "removeItem" -> handleRemoveItem();
                case "deposit" -> handleDeposit(ref, store);
                case "toggleNameTag" -> handleToggleNameTag();
                case "prevPage" -> handlePrevPage();
                case "nextPage" -> handleNextPage();
                case "histPrev" -> handleHistPrev();
                case "histNext" -> handleHistNext();
                case "apply_skin" -> handleApplySkin();
                case "pickup_shop" -> handlePickupShop();
                // Drag & drop completed: refresh grids to reflect container changes
                case "shopDrop", "invDrop", "hotbarDrop" -> refreshUI();
                default -> this.sendUpdate(new UICommandBuilder(), false);
            }
            return;
        }

        // Field value changes (name, desc, price, stockLimit, category)
        if (data.field != null) {
            switch (data.field) {
                case "name" -> {
                    if (data.nameVal != null) {
                        editedName = data.nameVal.trim();
                        dirty = true; // BUG #11
                    }
                }
                case "desc" -> {
                    if (data.descVal != null) {
                        editedDesc = data.descVal.trim();
                        dirty = true; // BUG #11
                    }
                }
                case "price" -> {
                    if (data.priceVal != null) handlePriceChange(data.priceVal);
                }
                case "buyPrice" -> {
                    if (data.buyPriceVal != null) handleBuyPriceChange(data.buyPriceVal);
                }
                case "sellPrice" -> {
                    if (data.sellPriceVal != null) handleSellPriceChange(data.sellPriceVal);
                }
                case "buyQuota" -> {
                    if (data.buyQuotaVal != null) handleBuyQuotaChange(data.buyQuotaVal);
                }
                case "stockLimit" -> {
                    if (data.stockLimitVal != null) handleStockLimitChange(data.stockLimitVal);
                }
                case "npcSkin" -> {
                    if (data.npcSkinVal != null) {
                        editedNpcSkin = data.npcSkinVal.trim();
                        dirty = true;
                    }
                }
                case "depositAmount" -> {
                    if (data.depositAmountVal != null) {
                        pendingDepositAmount = data.depositAmountVal.trim();
                    }
                }
            }
            // For text fields, just acknowledge without full refresh
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // Category dropdown change (no Field key, just raw @Category)
        if (data.categoryVal != null && !data.categoryVal.isEmpty()) {
            editedCategory = data.categoryVal;
            dirty = true; // BUG #11
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // Catch-all
        this.sendUpdate(new UICommandBuilder(), false);
    }

    // ==================== TAB SWITCHING ====================

    private void handleTabSwitch(String tab) {
        Tab newTab = switch (tab) {
            case "pricing" -> Tab.PRICING;
            case "revenue" -> Tab.REVENUE;
            case "history" -> Tab.HISTORY;
            default -> Tab.SETTINGS;
        };

        if (newTab == activeTab) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        activeTab = newTab;
        refreshUI();
    }

    // ==================== SLOT SELECTION ====================

    private void handleSelectSlot(String slotStr) {
        // "click" comes from SlotClicking events -- just refresh without changing selection
        if ("click".equals(slotStr)) {
            refreshUI();
            return;
        }

        try {
            int slot = Integer.parseInt(slotStr);
            int actualSlot = shopPage * SHOP_SLOTS_PER_PAGE + slot;

            if (actualSlot >= 0 && actualSlot < stagingContainer.getCapacity()) {
                ItemStack stack = stagingContainer.getItemStack((short) actualSlot);
                if (stack != null && !stack.isEmpty()) {
                    selectedSlot = actualSlot;
                } else {
                    selectedSlot = -1;
                }
            } else {
                selectedSlot = -1;
            }
        } catch (NumberFormatException e) {
            selectedSlot = -1;
        }

        // FIX 5: Slot change must push fresh price/stock/quota values to the UI fields.
        pushItemFields = true;
        refreshUI();
    }

    // ==================== MODE CHANGE ====================

    private void handleModeChange(String mode) {
        if (selectedSlot < 0 || selectedSlot >= stagingMeta.length) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        StagedItemMeta meta = getOrCreateMeta(selectedSlot);
        switch (mode) {
            case "buy" -> {
                meta.buyEnabled = true;
                meta.sellEnabled = false;
            }
            case "sell" -> {
                meta.buyEnabled = false;
                meta.sellEnabled = true;
            }
            case "both" -> {
                meta.buyEnabled = true;
                meta.sellEnabled = true;
            }
        }
        dirty = true; // BUG #11

        refreshUI();
    }

    // ==================== ICON PICKER ====================

    private void handleIconPick(String idxStr) {
        try {
            int idx = Integer.parseInt(idxStr);
            if (idx < 0 || idx >= ICON_OPTIONS.length) {
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }
            String picked = ICON_OPTIONS[idx];
            // Toggle: clicking the already-selected icon clears it back to fallback
            if (picked.equals(editedIconItemId)) {
                editedIconItemId = null;
            } else {
                editedIconItemId = picked;
            }
            dirty = true; // BUG #11
            refreshUI();
        } catch (NumberFormatException e) {
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== NPC SKIN APPLY ====================

    /**
     * Commits the edited NPC skin username to the shop, persists the change,
     * and respawns the NPC so the new skin is visible immediately.
     *
     * The skin field is a plain text input — Hytale's Core skin API accepts a
     * Minecraft-style username and resolves it via the configured skin source.
     * If the username is blank, the NPC falls back to the default skin.
     */
    private void handleApplySkin() {
        ShopI18n i18n = plugin.getI18n();
        String newSkin = editedNpcSkin != null ? editedNpcSkin.trim() : "";
        String normalised = newSkin.isEmpty() ? null : newSkin;

        // Idempotency: if the skin did not actually change, skip the DB write
        // and the respawn. Without this guard, clicking APPLY SKIN repeatedly
        // (or the button firing twice due to double-click) would spawn extra
        // NPC entities because respawnNpc removes the tracked ref before it
        // re-creates, and the onPlayerAddedToWorld + apply_skin paths can
        // race on it.
        String currentSkin = shopData.getNpcSkinUsername();
        boolean unchanged = (currentSkin == null && normalised == null)
            || (currentSkin != null && currentSkin.equals(normalised));
        if (unchanged) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.edit.skin.applied")).color("#55FF55"));
            refreshUI();
            return;
        }

        shopData.setNpcSkinUsername(normalised);
        shopData.markDirty();
        dirty = true;

        // Persist immediately so a crash before save does not lose the skin.
        try {
            plugin.getDatabase().saveShop(shopData);
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] Failed to persist NPC skin: " + e.getMessage());
        }

        // Respawn the NPC so the skin takes effect on the live entity. The
        // respawnNpc(shop, world) path dispatches to the world thread internally.
        try {
            World world = player.getWorld();
            plugin.getNpcManager().respawnNpc(shopData, world);
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] NPC respawn failed after skin change: " + e.getMessage());
        }

        player.sendMessage(Message.raw(
            i18n.get(playerRef, "shop.edit.skin.applied")).color("#55FF55"));

        refreshUI();
    }

    // ==================== PICKUP SHOP ====================

    /**
     * Packs the current shop up. Delegates to
     * {@link com.kyuubisoft.shops.service.ShopService#pickupShop(Player, PlayerRef, java.util.UUID)}
     * which refunds all stocked items to the owner's inventory, returns the
     * buyback pool to their wallet, despawns the NPC, and gives them a fresh
     * Shop_NPC_Token back. The shop row stays in the DB (packed=true) so
     * rating history, transaction log, category, icon, skin and name-tag
     * state are all preserved for when the owner replants.
     *
     * <p>After a successful pickup the editor is closed — the shop is no
     * longer in a state where further editing makes sense. We set
     * {@code saved = true} before closing so onDismiss does NOT try to
     * return staged items to the player's inventory (that would duplicate
     * refunds, since pickupShop already returned everything).
     */
    private void handlePickupShop() {
        ShopI18n i18n = plugin.getI18n();

        boolean ok;
        try {
            ok = plugin.getShopService().pickupShop(player, playerRef, shopData.getId());
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] pickupShop threw: " + e.getMessage());
            ok = false;
        }

        if (!ok) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.pickup.failed")).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        player.sendMessage(Message.raw(
            i18n.get(playerRef, "shop.pickup.success", shopData.getName())
        ).color("#55FF55"));

        // Prevent onDismiss from running the "return staged items / restore
        // original items" dupe-prevention paths — pickupShop has already
        // cleared shopData.items and refunded everything through the service.
        saved = true;
        plugin.getSessionManager().unlockEditor(shopData.getId(), playerRef.getUuid());

        try {
            this.close();
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] Failed to close page after pickup: " + e.getMessage());
        }
    }

    // ==================== PRICE / STOCK CHANGE ====================

    private void handlePriceChange(String priceStr) {
        // Legacy single price field - kept for backwards compat
        handleBuyPriceChange(priceStr);
    }

    private void handleBuyPriceChange(String priceStr) {
        if (selectedSlot < 0 || selectedSlot >= stagingMeta.length) return;
        try {
            int price = Integer.parseInt(priceStr);
            ShopConfig.Economy eco = plugin.getShopConfig().getData().economy;
            if (price < eco.minPrice) price = eco.minPrice;
            if (price > eco.maxPrice) price = eco.maxPrice;

            StagedItemMeta meta = getOrCreateMeta(selectedSlot);
            meta.buyPrice = price;
            dirty = true; // BUG #11
        } catch (NumberFormatException ignored) {}
    }

    private void handleSellPriceChange(String priceStr) {
        if (selectedSlot < 0 || selectedSlot >= stagingMeta.length) return;
        try {
            int price = Integer.parseInt(priceStr);
            ShopConfig.Economy eco = plugin.getShopConfig().getData().economy;
            if (price < 0) price = 0;
            if (price > eco.maxPrice) price = eco.maxPrice;

            StagedItemMeta meta = getOrCreateMeta(selectedSlot);
            meta.sellPrice = price;
            dirty = true; // BUG #11
        } catch (NumberFormatException ignored) {}
    }

    private void handleBuyQuotaChange(String quotaStr) {
        if (selectedSlot < 0 || selectedSlot >= stagingMeta.length) return;
        try {
            int quota = Integer.parseInt(quotaStr);
            if (quota < 0) quota = 0;
            StagedItemMeta meta = getOrCreateMeta(selectedSlot);
            meta.buyQuota = quota;
            dirty = true; // BUG #11
        } catch (NumberFormatException ignored) {}
    }

    private void handleStockLimitChange(String limitStr) {
        if (selectedSlot < 0 || selectedSlot >= stagingMeta.length) return;
        try {
            int limit = Integer.parseInt(limitStr);
            StagedItemMeta meta = getOrCreateMeta(selectedSlot);
            meta.maxStock = limit;
            dirty = true; // BUG #11
        } catch (NumberFormatException ignored) {}
    }

    // ==================== SAVE ====================

    private void handleSave(Ref<EntityStore> ref, Store<EntityStore> store) {
        ShopI18n i18n = plugin.getI18n();
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();

        // Validate edited name
        if (editedName.isEmpty()) {
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.edit.name_empty")).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        if (editedName.length() < cfg.playerShops.nameMinLength
            || editedName.length() > cfg.playerShops.nameMaxLength) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.edit.name_invalid")
            ).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // Check name uniqueness (only if changed)
        if (!editedName.equalsIgnoreCase(shopData.getName())) {
            for (ShopData other : plugin.getShopManager().getAllShops()) {
                if (other.getId().equals(shopData.getId())) continue;
                if (other.getName().equalsIgnoreCase(editedName)) {
                    player.sendMessage(Message.raw(
                        i18n.get(playerRef, "shop.edit.name_taken")
                    ).color("#FF5555"));
                    this.sendUpdate(new UICommandBuilder(), false);
                    return;
                }
            }
        }

        // Commit metadata fields to ShopData
        boolean nameChanged = !editedName.equals(shopData.getName());
        shopData.setName(editedName);
        shopData.setDescription(editedDesc);
        shopData.setCategory(editedCategory);
        shopData.setIconItemId(editedIconItemId);
        shopData.setLastActivity(System.currentTimeMillis());

        // Rebuild shop items from staging container.
        // FIX 4: If the item was originally unlimited stock (-1), preserve that on save.
        // Otherwise the staging container's quantity (always >= 1) would clobber the
        // unlimited flag and turn the listing into a 1-stock item.
        List<ShopItem> newItems = new ArrayList<>();
        for (int i = 0; i < stagingContainer.getCapacity(); i++) {
            ItemStack stack = stagingContainer.getItemStack((short) i);
            if (stack != null && !stack.isEmpty()) {
                StagedItemMeta meta = stagingMeta[i];
                int finalStock = originalUnlimitedItems.contains(stack.getItemId())
                    ? -1
                    : stack.getQuantity();

                // Capture BSON metadata (enchantments, custom stats, pet IDs,
                // weapon mastery levels etc.) from the staged ItemStack so it
                // can be restored on the buyer side. The canonical item id
                // strips any DTT virtual-suffix so persisted rows never
                // contain runtime-only cache state.
                String metadataJson = null;
                try {
                    BsonDocument bsonMeta = stack.getMetadata();
                    metadataJson = BsonMetadataCodec.encode(bsonMeta);
                } catch (Exception ignored) {
                    // Items without metadata throw -> treat as null
                }

                String canonicalId = BsonMetadataCodec.stripDttSuffix(stack.getItemId());

                ShopItem item = new ShopItem(
                    canonicalId,
                    meta != null ? meta.buyPrice : 10,
                    meta != null ? meta.sellPrice : 5,
                    finalStock,
                    meta != null ? meta.maxStock : -1,
                    meta != null ? meta.buyEnabled : true,
                    meta != null ? meta.sellEnabled : false,
                    i,
                    editedCategory,
                    meta != null ? meta.buyQuota : 0,  // Reuse dailyBuyLimit as buy quota (persistent)
                    0,   // dailySellLimit
                    metadataJson // itemMetadata — round-tripped by ShopDatabase
                );
                newItems.add(item);
            }
        }

        // Replace shop items
        shopData.getItems().clear();
        shopData.getItems().addAll(newItems);
        shopData.markDirty();

        // Persist to DB
        try {
            plugin.getDatabase().saveShop(shopData);
            plugin.getDatabase().saveShopItems(shopData.getId(), newItems);
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] Failed to save shop to DB: " + e.getMessage());
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.edit.save_failed")
            ).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        saved = true;
        dirty = false; // BUG #11: successful save clears pending-changes state

        // Push the (possibly renamed) shop name to the live NPC nameplate so
        // the change is visible immediately. Previously the user had to toggle
        // the show-name-tag flag off and on to force a refresh.
        if (nameChanged) {
            try {
                plugin.getNpcManager().refreshNameTag(shopData, player.getWorld());
            } catch (Exception e) {
                LOGGER.warning("[ShopEdit] NPC nameplate refresh failed: " + e.getMessage());
            }
        }

        // Unlock editor session
        plugin.getSessionManager().unlockEditor(shopData.getId(), playerRef.getUuid());

        player.sendMessage(Message.raw(
            i18n.get(playerRef, "shop.edit.saved", shopData.getName())
        ).color("#55FF55"));

        // Close page
        try {
            this.close();
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] Failed to close page after save: " + e.getMessage());
        }
    }

    // ==================== TOGGLE OPEN/CLOSED ====================

    private void handleToggleOpen() {
        boolean newState = !shopData.isOpen();
        shopData.setOpen(newState);
        shopData.markDirty();

        // FIX 3: Persist immediately. Previously the toggle only mutated the in-memory
        // ShopData, so a server crash before the next full save would lose the change.
        try {
            plugin.getDatabase().saveShop(shopData);
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] Failed to persist shop open state: " + e.getMessage());
        }

        refreshUI();
    }

    // ==================== TOGGLE NAME TAG ====================

    /**
     * Toggles the "show shop name above NPC" nameplate flag, persists it, and
     * pushes the update to the live NPC entity so the change is visible
     * immediately without a full respawn.
     */
    private void handleToggleNameTag() {
        boolean newState = !shopData.isShowNameTag();
        shopData.setShowNameTag(newState);
        shopData.markDirty();
        dirty = true;

        try {
            plugin.getDatabase().saveShop(shopData);
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] Failed to persist name tag toggle: " + e.getMessage());
        }

        try {
            plugin.getNpcManager().refreshNameTag(shopData, player.getWorld());
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] NPC nameplate refresh failed: " + e.getMessage());
        }

        refreshUI();
    }

    // ==================== DEPOSIT ====================

    private void handleDeposit(Ref<EntityStore> ref, Store<EntityStore> store) {
        ShopI18n i18n = plugin.getI18n();

        // Parse the amount from the text field. Default 100 if empty/invalid
        // so the button is still usable without typing anything.
        double depositAmount = 100;
        if (pendingDepositAmount != null && !pendingDepositAmount.isBlank()) {
            try {
                depositAmount = Double.parseDouble(pendingDepositAmount.trim());
            } catch (NumberFormatException ignored) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.deposit.invalid_amount")
                ).color("#FF5555"));
                return;
            }
        }
        if (depositAmount <= 0) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.deposit.invalid_amount")
            ).color("#FF5555"));
            return;
        }

        boolean success = plugin.getShopService().depositToShop(
            playerRef, shopData.getId(), depositAmount
        );

        if (success) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.deposit.success",
                    plugin.getEconomyBridge().format(depositAmount), shopData.getName())
            ).color("#55FF55"));
        } else {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.deposit.failed")
            ).color("#FF5555"));
        }

        refreshUI();
    }

    // ==================== REMOVE ITEM ====================

    private void handleRemoveItem() {
        if (selectedSlot < 0 || selectedSlot >= stagingContainer.getCapacity()) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // BUG #3 FIX: Before clearing the slot, return the item to the player's
        // inventory. Previously the item was silently destroyed -- if the owner
        // accidentally hit "Remove", the items were lost. Also critical for
        // originalItemCounts accounting: if we don't return the item to the
        // player, a subsequent discard would still leave the items in DB
        // (getStagingOnlyItems returns only NET additions), so the owner would
        // lose them without recourse.
        ItemStack removed = stagingContainer.getItemStack((short) selectedSlot);
        if (removed != null && !removed.isEmpty() && player != null) {
            try {
                com.kyuubisoft.shops.util.PlayerInventoryAccess inv =
                    com.kyuubisoft.shops.util.PlayerInventoryAccess.of(player);
                ItemContainer storage = inv.getStorage();
                if (storage != null) {
                    storage.addItemStack(removed);
                }
                inv.markChanged();
            } catch (Exception e) {
                LOGGER.warning("[ShopEdit] handleRemoveItem: failed to return item to player: "
                    + e.getMessage());
            }
        }

        // Clear item from staging container
        stagingContainer.setItemStackForSlot((short) selectedSlot, null);

        // Clear metadata
        if (selectedSlot < stagingMeta.length) {
            stagingMeta[selectedSlot] = null;
        }

        selectedSlot = -1;
        dirty = true; // BUG #11
        // FIX 5: Removing an item clears the selection -- push UI back to "no item selected".
        pushItemFields = true;
        refreshUI();
    }

    // ==================== DRAG & DROP ====================

    /**
     * Handle item drop between grid sections.
     * Manually moves items between staging container and player inventory.
     * Section IDs: 0 = shop staging, -2 = player storage, -1 = player hotbar
     */
    private void handleDrop(Ref<EntityStore> ref, Store<EntityStore> store,
                           Integer sourceSectionId, int sourceSlot,
                           int destSectionId, int destSlot) {
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) {
            refreshUI();
            return;
        }

        int srcSection = sourceSectionId != null ? sourceSectionId : 0;

        ItemContainer srcContainer = resolveContainer(p, srcSection);
        ItemContainer dstContainer = resolveContainer(p, destSectionId);

        if (srcContainer == null || dstContainer == null) {
            LOGGER.warning("[ShopEdit] Drop failed: null container (src=" + srcSection + " dst=" + destSectionId + ")");
            refreshUI();
            return;
        }

        if (sourceSlot < 0 || sourceSlot >= srcContainer.getCapacity() ||
            destSlot < 0 || destSlot >= dstContainer.getCapacity()) {
            LOGGER.warning("[ShopEdit] Drop failed: slot out of range");
            refreshUI();
            return;
        }

        ItemStack srcItem = srcContainer.getItemStack((short) sourceSlot);
        ItemStack dstItem = dstContainer.getItemStack((short) destSlot);

        if (srcItem == null || srcItem.isEmpty()) {
            refreshUI();
            return;
        }

        // Swap items between source and destination
        dstContainer.setItemStackForSlot((short) destSlot, srcItem);
        srcContainer.setItemStackForSlot((short) sourceSlot, dstItem);

        // Mark inventory dirty if player inventory was involved
        if (srcSection == -2 || srcSection == -1 || destSectionId == -2 || destSectionId == -1) {
            try {
                com.kyuubisoft.shops.util.PlayerInventoryAccess.of(p).markChanged();
            } catch (Exception ignored) {}
        }

        // BUG #11: Any drop that touches the shop grid (either direction) is a
        // pending edit. Mark dirty so onDismiss warns about unsaved changes.
        if (srcSection == 0 || destSectionId == 0) {
            dirty = true;
        }

        LOGGER.info("[ShopEdit] Moved item from section " + srcSection + "[" + sourceSlot
            + "] to section " + destSectionId + "[" + destSlot + "]");

        // Auto-select the destination slot if dropped into shop grid
        if (destSectionId == 0 && srcItem != null && !srcItem.isEmpty()) {
            selectedSlot = destSlot;
            // FIX 5: Auto-select after drop -- push the item's price/stock fields to UI.
            pushItemFields = true;
        }

        refreshUI();
    }

    /**
     * Resolve a container from a section ID.
     * 0 = shop staging, -1 = hotbar, -2 = storage
     */
    private ItemContainer resolveContainer(Player p, int sectionId) {
        com.kyuubisoft.shops.util.PlayerInventoryAccess inv =
            com.kyuubisoft.shops.util.PlayerInventoryAccess.of(p);
        return switch (sectionId) {
            case 0 -> stagingContainer;
            case -1 -> inv.getHotbar();
            case -2 -> inv.getStorage();
            default -> stagingContainer;
        };
    }

    // ==================== PAGINATION ====================

    private void handlePrevPage() {
        if (shopPage > 0) {
            shopPage--;
            selectedSlot = -1;
            pushItemFields = true; // FIX 5: re-arm so per-item fields refresh on page change
            refreshUI();
        } else {
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    private void handleNextPage() {
        int maxPage = getMaxShopPage();
        if (shopPage < maxPage) {
            shopPage++;
            selectedSlot = -1;
            pushItemFields = true; // FIX 5: re-arm so per-item fields refresh on page change
            refreshUI();
        } else {
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    private void handleHistPrev() {
        if (historyPage > 0) {
            historyPage--;
            refreshUI();
        } else {
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    private void handleHistNext() {
        historyPage++;
        refreshUI();
    }

    // ==================== REFRESH ====================

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindAllEvents(events);
        if (buildRef != null && buildStore != null) {
            buildUI(ui, buildRef, buildStore);
        }
        this.sendUpdate(ui, events, false);
    }

    // ==================== EVENT BINDING ====================

    private void bindAllEvents(UIEventBuilder events) {
        // Sub-tab buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SettingsTab",
            EventData.of("Tab", "settings"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PricingTab",
            EventData.of("Tab", "pricing"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RevenueTab",
            EventData.of("Tab", "revenue"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#HistoryTab",
            EventData.of("Tab", "history"), false);

        // Save & toggle buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveBtn",
            EventData.of("Button", "save"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleOpenBtn",
            EventData.of("Button", "toggleOpen"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RemoveItemBtn",
            EventData.of("Button", "removeItem"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DepositBtn",
            EventData.of("Button", "deposit"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleNameTagBtn",
            EventData.of("Button", "toggleNameTag"), false);

        // Shop grid pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevPageBtn",
            EventData.of("Button", "prevPage"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextPageBtn",
            EventData.of("Button", "nextPage"), false);

        // History pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#HistPrevBtn",
            EventData.of("Button", "histPrev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#HistNextBtn",
            EventData.of("Button", "histNext"), false);

        // Dropped events - native ItemGrid drag & drop between grids
        events.addEventBinding(CustomUIEventBindingType.Dropped, "#ShopGrid",
            EventData.of("Action", "drop_shop"), false);
        events.addEventBinding(CustomUIEventBindingType.Dropped, "#InvGrid",
            EventData.of("Action", "drop_inv"), false);
        events.addEventBinding(CustomUIEventBindingType.Dropped, "#HotbarGrid",
            EventData.of("Action", "drop_hotbar"), false);

        // SlotClicking events - click to select slot for price editing
        events.addEventBinding(CustomUIEventBindingType.SlotClicking, "#ShopGrid",
            EventData.of("Action", "click_shop"), false);
        events.addEventBinding(CustomUIEventBindingType.SlotClicking, "#InvGrid",
            EventData.of("Action", "click_inv"), false);

        // Text field changes
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#EditNameField",
            EventData.of("Field", "name")
                .append("@Name", "#EditNameField.Value"));

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#EditDescField",
            EventData.of("Field", "desc")
                .append("@Desc", "#EditDescField.Value"));

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#EditCategoryDropdown",
            EventData.of("@Category", "#EditCategoryDropdown.Value"));

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#BuyPriceField",
            EventData.of("Field", "buyPrice")
                .append("@BuyPrice", "#BuyPriceField.Value"));

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SellPriceField",
            EventData.of("Field", "sellPrice")
                .append("@SellPrice", "#SellPriceField.Value"));

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#BuyQuotaField",
            EventData.of("Field", "buyQuota")
                .append("@BuyQuota", "#BuyQuotaField.Value"));

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#StockLimitField",
            EventData.of("Field", "stockLimit")
                .append("@StockLimit", "#StockLimitField.Value"));

        // Mode buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeBuyBtn",
            EventData.of("Mode", "buy"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeSellBtn",
            EventData.of("Mode", "sell"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeBothBtn",
            EventData.of("Mode", "both"), false);

        // Icon picker tiles - one binding per tile, IDs match #IconPick0..#IconPick23 in ShopEdit.ui
        for (int i = 0; i < ICON_OPTIONS.length; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#IconPick" + i,
                EventData.of("IconPick", String.valueOf(i)), false);
        }

        // Shop NPC skin field: live-sync typed value into editedNpcSkin
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#NpcSkinField",
            EventData.of("Field", "npcSkin")
                .append("@NpcSkin", "#NpcSkinField.Value"));

        // Apply-skin button: commits the skin and respawns the NPC with it
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplySkinBtn",
            EventData.of("Button", "apply_skin"), false);

        // Pickup-shop button: packs the shop, refunds items + balance, returns a token
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PickupShopBtn",
            EventData.of("Button", "pickup_shop"), false);

        // Deposit amount field: live-sync typed value into pendingDepositAmount
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#DepositField",
            EventData.of("Field", "depositAmount")
                .append("@DepositAmount", "#DepositField.Value"));
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui, Ref<EntityStore> ref, Store<EntityStore> store) {
        buildTitleBar(ui);
        buildTabHighlights(ui);
        populateShopGrid(ui);
        populatePlayerInventory(ui, ref, store);
        buildShopPagination(ui);

        switch (activeTab) {
            case SETTINGS -> {
                ui.set("#SettingsPanel.Visible", true);
                ui.set("#PricingPanel.Visible", false);
                ui.set("#RevenuePanel.Visible", false);
                ui.set("#HistoryPanel.Visible", false);
                buildSettingsPanel(ui);
            }
            case PRICING -> {
                ui.set("#SettingsPanel.Visible", false);
                ui.set("#PricingPanel.Visible", true);
                ui.set("#RevenuePanel.Visible", false);
                ui.set("#HistoryPanel.Visible", false);
                buildPricingPanel(ui);
            }
            case REVENUE -> {
                ui.set("#SettingsPanel.Visible", false);
                ui.set("#PricingPanel.Visible", false);
                ui.set("#RevenuePanel.Visible", true);
                ui.set("#HistoryPanel.Visible", false);
                buildRevenuePanel(ui);
            }
            case HISTORY -> {
                ui.set("#SettingsPanel.Visible", false);
                ui.set("#PricingPanel.Visible", false);
                ui.set("#RevenuePanel.Visible", false);
                ui.set("#HistoryPanel.Visible", true);
                buildHistoryPanel(ui);
            }
        }
    }

    // ==================== TITLE BAR ====================

    private void buildTitleBar(UICommandBuilder ui) {
        String title = "SHOP EDITOR: " + editedName;
        ui.set("#ShopTitle.Text", title);

        // Currency balance
        double balance = plugin.getEconomyBridge().getBalance(playerRef.getUuid());
        ui.set("#CurrencyBalance.Text", plugin.getEconomyBridge().format(balance));

        // Toggle open/closed button
        if (shopData.isOpen()) {
            ui.set("#ToggleOpenLbl.Text", "OPEN");
            ui.set("#ToggleOpenLbl.Style.TextColor", "#44ff44");
        } else {
            ui.set("#ToggleOpenLbl.Text", "CLOSED");
            ui.set("#ToggleOpenLbl.Style.TextColor", "#ff4444");
        }
    }

    // ==================== TAB HIGHLIGHTS ====================

    private void buildTabHighlights(UICommandBuilder ui) {
        String activeColor = "#e8c547";
        String inactiveColor = "#96a9be";

        ui.set("#SettingsTabLbl.Style.TextColor", activeTab == Tab.SETTINGS ? activeColor : inactiveColor);
        ui.set("#PricingTabLbl.Style.TextColor", activeTab == Tab.PRICING ? activeColor : inactiveColor);
        ui.set("#RevenueTabLbl.Style.TextColor", activeTab == Tab.REVENUE ? activeColor : inactiveColor);
        ui.set("#HistoryTabLbl.Style.TextColor", activeTab == Tab.HISTORY ? activeColor : inactiveColor);
    }

    // ==================== SHOP GRID ====================

    /**
     * Populates the native ItemGrid with ItemGridSlot instances from the staging container.
     * PITFALL #121: openCustomPageWithWindows() does NOT auto-populate ItemGrid -- must manually set slots.
     */
    private void populateShopGrid(UICommandBuilder ui) {
        int totalSlots = stagingContainer.getCapacity();
        int startSlot = shopPage * SHOP_SLOTS_PER_PAGE;
        int displaySlots = Math.min(SHOP_SLOTS_PER_PAGE, totalSlots - startSlot);

        List<ItemGridSlot> slots = new ArrayList<>(displaySlots);
        for (int i = 0; i < displaySlots; i++) {
            int containerIdx = startSlot + i;
            ItemStack stack = stagingContainer.getItemStack((short) containerIdx);
            ItemGridSlot slot;

            if (stack != null && !stack.isEmpty()) {
                slot = safeSlot(stack);
                // Highlight selected slot
                if (containerIdx == selectedSlot) {
                    slot.setActivatable(true);
                }
            } else {
                slot = new ItemGridSlot();
            }
            slot.setActivatable(true);
            slots.add(slot);
        }

        ui.set("#ShopGrid.Slots", slots);
    }

    /**
     * Populates the player inventory grids (storage + hotbar).
     */
    private void populatePlayerInventory(UICommandBuilder ui, Ref<EntityStore> ref,
                                          Store<EntityStore> store) {
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;

        // Storage (3 rows of 9 = 27 slots)
        var storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        if (storageComp != null) {
            ItemContainer storage = storageComp.getInventory();
            List<ItemGridSlot> storageSlots = new ArrayList<>((int) storage.getCapacity());
            for (int i = 0; i < storage.getCapacity(); i++) {
                ItemStack stack = storage.getItemStack((short) i);
                ItemGridSlot slot = safeSlot(stack);
                slot.setActivatable(true);
                storageSlots.add(slot);
            }
            ui.set("#InvGrid.Slots", storageSlots);
        }

        // Hotbar (9 slots)
        var hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp != null) {
            ItemContainer hotbar = hotbarComp.getInventory();
            List<ItemGridSlot> hotbarSlots = new ArrayList<>((int) hotbar.getCapacity());
            for (int i = 0; i < hotbar.getCapacity(); i++) {
                ItemStack stack = hotbar.getItemStack((short) i);
                ItemGridSlot slot = safeSlot(stack);
                slot.setActivatable(true);
                hotbarSlots.add(slot);
            }
            ui.set("#HotbarGrid.Slots", hotbarSlots);
        }
    }

    /**
     * Create display-safe ItemGridSlot. Strips metadata and clamps 0-durability
     * to prevent client NullReferenceException (same pattern as BankPage).
     */
    private ItemGridSlot safeSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new ItemGridSlot();
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) return new ItemGridSlot();
        try {
            double maxDur = stack.getMaxDurability();
            if (maxDur > 0) {
                double dur = Math.max(stack.getDurability(), 1.0);
                return new ItemGridSlot(new ItemStack(itemId, stack.getQuantity(), dur, maxDur, null));
            }
            return new ItemGridSlot(new ItemStack(itemId, stack.getQuantity()));
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] safeSlot failed for item '" + itemId + "': " + e.getMessage());
            return new ItemGridSlot();
        }
    }

    // ==================== SHOP PAGINATION ====================

    private void buildShopPagination(UICommandBuilder ui) {
        int totalSlots = stagingContainer.getCapacity();
        int totalPages = Math.max(1, (int) Math.ceil(totalSlots / (double) SHOP_SLOTS_PER_PAGE));

        if (shopPage >= totalPages) shopPage = totalPages - 1;
        if (shopPage < 0) shopPage = 0;

        ui.set("#PageInfo.Text", (shopPage + 1) + " / " + totalPages);
        ui.set("#PrevPageBtn.Visible", shopPage > 0);
        ui.set("#NextPageBtn.Visible", shopPage < totalPages - 1);
    }

    // ==================== SETTINGS PANEL ====================

    private void buildSettingsPanel(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();

        // Global shop fields. Pushed once at open; after that the client owns
        // the value so refreshUI() does not clobber what the user is typing.
        if (pushGlobalFields) {
            ui.set("#EditNameField.Value", editedName);
            ui.set("#EditDescField.Value", editedDesc);
            ui.set("#EditCategoryDropdown.Value", editedCategory);
            ui.set("#NpcSkinField.Value", editedNpcSkin != null ? editedNpcSkin : "");
        }

        // Localised labels for the NPC skin panel. Kept ASCII-clean.
        ui.set("#NpcSkinLabel.Text", i18n.get(playerRef, "shop.edit.label.skin"));
        ui.set("#ApplySkinLbl.Text", i18n.get(playerRef, "shop.edit.skin.apply"));
        if (editedNpcSkin == null || editedNpcSkin.isBlank()) {
            ui.set("#NpcSkinStatusLabel.Text", "CURRENT: (default)");
        } else {
            ui.set("#NpcSkinStatusLabel.Text", "CURRENT: " + editedNpcSkin);
        }

        // Name tag toggle reflects current state live.
        if (shopData.isShowNameTag()) {
            ui.set("#ToggleNameTagLbl.Text",
                i18n.get(playerRef, "shop.edit.name_tag.on"));
            ui.set("#ToggleNameTagLbl.Style.TextColor", "#44ff44");
        } else {
            ui.set("#ToggleNameTagLbl.Text",
                i18n.get(playerRef, "shop.edit.name_tag.off"));
            ui.set("#ToggleNameTagLbl.Style.TextColor", "#888888");
        }

        // Pickup shop button label (localised).
        ui.set("#PickupShopLbl.Text",
            i18n.get(playerRef, "shop.edit.pickup_button"));

        // Icon picker: highlight the selected tile and update the status label.
        for (int i = 0; i < ICON_OPTIONS.length; i++) {
            boolean isSelected = ICON_OPTIONS[i].equals(editedIconItemId);
            ui.set("#IconPick" + i + "Selector.Visible", isSelected);
        }
        if (editedIconItemId != null && !editedIconItemId.isBlank()) {
            ui.set("#IconPickSelectedLabel.Text",
                "SELECTED: " + ShopBrowsePage.formatItemName(editedIconItemId));
        } else {
            ui.set("#IconPickSelectedLabel.Text", "SELECTED: (first item fallback)");
        }

        // Clear the global push flag - subsequent refreshes leave user typing alone.
        pushGlobalFields = false;
    }

    // ==================== PRICING PANEL ====================

    /**
     * Dedicated sub-tab for per-item pricing. Populates the buy/sell/quota/stock
     * fields + mode button state for the currently selected slot. Split out from
     * buildSettingsPanel so the Settings tab only shows shop-level config (name,
     * category, icon, NPC skin, name tag) and this tab owns the item-level state.
     */
    private void buildPricingPanel(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();

        ui.set("#BuyPriceLabel.Text", i18n.get(playerRef, "shop.edit.label.sell_to_customers"));
        ui.set("#SellPriceLabel.Text", i18n.get(playerRef, "shop.edit.label.buy_from_customers"));
        ui.set("#BuyQuotaLabel.Text", i18n.get(playerRef, "shop.edit.label.buyback_quota"));
        ui.set("#StockLimitLabel.Text", i18n.get(playerRef, "shop.edit.label.stock_available"));
        ui.set("#ModeLabel.Text", i18n.get(playerRef, "shop.edit.label.mode"));

        if (selectedSlot >= 0 && selectedSlot < stagingContainer.getCapacity()) {
            ItemStack stack = stagingContainer.getItemStack((short) selectedSlot);
            if (stack != null && !stack.isEmpty()) {
                ui.set("#ItemEditSection.Visible", true);
                ui.set("#NoItemSelected.Visible", false);

                String itemName = ShopBrowsePage.formatItemName(stack.getItemId());
                ui.set("#SelectedItemName.Text", itemName + " x" + stack.getQuantity());

                StagedItemMeta meta = getOrCreateMeta(selectedSlot);
                if (pushItemFields) {
                    ui.set("#BuyPriceField.Value", String.valueOf(meta.buyPrice));
                    ui.set("#SellPriceField.Value", String.valueOf(meta.sellPrice));
                    ui.set("#BuyQuotaField.Value", String.valueOf(meta.buyQuota));
                    ui.set("#StockLimitField.Value", String.valueOf(meta.maxStock));
                }

                // Mutually exclusive mode highlighting (BUG #2 fix).
                boolean isBoth = meta.buyEnabled && meta.sellEnabled;
                boolean isBuyOnly = meta.buyEnabled && !meta.sellEnabled;
                boolean isSellOnly = !meta.buyEnabled && meta.sellEnabled;
                String activeColor = "#ffd700";
                String inactiveColor = "#555555";
                ui.set("#ModeBuyLbl.Style.TextColor", isBuyOnly ? activeColor : inactiveColor);
                ui.set("#ModeSellLbl.Style.TextColor", isSellOnly ? activeColor : inactiveColor);
                ui.set("#ModeBothLbl.Style.TextColor", isBoth ? activeColor : inactiveColor);
            } else {
                showNoItemSelected(ui);
            }
        } else {
            showNoItemSelected(ui);
        }

        pushItemFields = false;
    }

    private void showNoItemSelected(UICommandBuilder ui) {
        ui.set("#ItemEditSection.Visible", false);
        ui.set("#NoItemSelected.Visible", true);
    }

    // ==================== REVENUE PANEL ====================

    private void buildRevenuePanel(UICommandBuilder ui) {
        // Buyback pool (current funds available for buybacks). Phase 3
        // mailbox refactor: shopBalance is no longer earnings — it is the
        // pool the owner deposits into so customers can sell back to the
        // shop. Earnings live in the mailbox now.
        String poolLabel = plugin.getI18n().get(playerRef, "shop.revenue.buyback_pool");
        ui.set("#ShopBalanceLbl.Text", poolLabel + ": "
            + plugin.getEconomyBridge().format(shopData.getShopBalance()) + " Gold");

        ui.set("#TotalRevenue.Text", plugin.getEconomyBridge().format(shopData.getTotalRevenue()));
        ui.set("#TaxPaid.Text", plugin.getEconomyBridge().format(shopData.getTotalTaxPaid()));

        // Per-period revenue from the transactions table (BUY only, summed).
        long now = System.currentTimeMillis();
        long todayStart = now - 24L * 60 * 60 * 1000;  // last 24h
        long weekStart = now - 7L * 24 * 60 * 60 * 1000;  // last 7 days
        try {
            double todayRev = plugin.getDatabase().getRevenueForShopSince(shopData.getId(), todayStart);
            double weekRev = plugin.getDatabase().getRevenueForShopSince(shopData.getId(), weekStart);
            ui.set("#TodayRevenue.Text", plugin.getEconomyBridge().format(todayRev));
            ui.set("#WeekRevenue.Text", plugin.getEconomyBridge().format(weekRev));
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] per-period revenue load failed: " + e.getMessage());
            ui.set("#TodayRevenue.Text", "--");
            ui.set("#WeekRevenue.Text", "--");
        }

        // Total sales (from player data)
        PlayerRef ownerRef = playerRef;
        var playerData = plugin.getPlayerData(ownerRef.getUuid());
        if (playerData != null) {
            ui.set("#TotalSales.Text", String.valueOf(playerData.getTotalSales()));
        } else {
            ui.set("#TotalSales.Text", "0");
        }

        // Rating
        if (shopData.getTotalRatings() > 0) {
            ui.set("#AvgRating.Text",
                String.format("%.1f / %d (%d ratings)",
                    shopData.getAverageRating(),
                    plugin.getShopConfig().getData().ratings.maxStars,
                    shopData.getTotalRatings()));
        } else {
            ui.set("#AvgRating.Text", "No ratings yet");
        }
    }

    // ==================== HISTORY PANEL ====================

    private void buildHistoryPanel(UICommandBuilder ui) {
        int totalTx;
        List<ShopDatabase.TransactionRecord> rows;
        try {
            totalTx = plugin.getDatabase().countTransactionsForShop(shopData.getId());
            rows = plugin.getDatabase().loadTransactionsForShop(
                shopData.getId(),
                historyPage * HISTORY_PER_PAGE,
                HISTORY_PER_PAGE);
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] history load failed: " + e.getMessage());
            totalTx = 0;
            rows = Collections.emptyList();
        }

        for (int i = 0; i < HISTORY_PER_PAGE; i++) {
            if (i < rows.size()) {
                ShopDatabase.TransactionRecord tx = rows.get(i);
                ui.set("#TxRow" + i + ".Visible", true);
                // Icon (ItemIcon widget expects ItemId, matching ShopNotificationsPage/ShopBrowsePage)
                try {
                    ui.set("#Tx" + i + "Icon.ItemId", tx.itemId);
                } catch (Exception ignored) {}
                // Detail line: "<buyer> bought 5x Iron Sword for 250G"
                String itemLabel = tx.itemId != null ? tx.itemId.replace('_', ' ') : "?";
                String detail;
                if ("BUY".equals(tx.type)) {
                    String buyer = tx.buyerName != null ? tx.buyerName : "someone";
                    detail = buyer + " bought " + tx.quantity + "x " + itemLabel + " for " + tx.totalPrice + "G";
                } else if ("SELL".equals(tx.type)) {
                    // sellItem uses buyer_name for the player in logTransaction
                    String seller = tx.buyerName != null ? tx.buyerName : "someone";
                    detail = seller + " sold " + tx.quantity + "x " + itemLabel + " for " + tx.totalPrice + "G";
                } else {
                    detail = tx.type + " " + tx.quantity + "x " + itemLabel;
                }
                ui.set("#Tx" + i + "Detail.Text", detail);
                ui.set("#Tx" + i + "Time.Text", formatRelativeTime(tx.timestamp));
                // Tint buy=green, sell=amber
                String bg = "BUY".equals(tx.type) ? "#44ff4425" : "#ffaa0025";
                ui.set("#Tx" + i + "IconBg.Background", bg);
            } else {
                ui.set("#TxRow" + i + ".Visible", false);
            }
        }

        boolean hasHistory = totalTx > 0;
        ui.set("#HistEmptyLabel.Visible", !hasHistory);
        if (!hasHistory) {
            ui.set("#HistEmptyLabel.Text", "No transactions yet");
        }

        if (hasHistory) {
            int totalPages = Math.max(1, (totalTx + HISTORY_PER_PAGE - 1) / HISTORY_PER_PAGE);
            if (historyPage >= totalPages) historyPage = totalPages - 1;
            ui.set("#HistNavBar.Visible", totalPages > 1);
            ui.set("#HistPageInfo.Text", (historyPage + 1) + " / " + totalPages);
        } else {
            ui.set("#HistNavBar.Visible", false);
            ui.set("#HistPageInfo.Text", "1 / 1");
        }
    }

    /**
     * Formats a timestamp into a relative time string like "5m ago", "2h ago", "3d ago".
     * Mirrors the helpers in ShopCommand.HistoryCmd and ShopNotificationsPage.
     */
    private String formatRelativeTime(long timestamp) {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        if (diff < 0) return "just now";

        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";

        long hours = TimeUnit.MILLISECONDS.toHours(diff);
        if (hours < 24) return hours + "h ago";

        long days = TimeUnit.MILLISECONDS.toDays(diff);
        if (days < 30) return days + "d ago";

        long months = days / 30;
        if (months < 12) return months + "mo ago";

        return (days / 365) + "y ago";
    }

    // ==================== STAGING ====================

    /**
     * Checks whether the given category is one of the values defined in
     * #EditCategoryDropdown in ShopEdit.ui. Setting .Value to a category that
     * isn't in the dropdown crashes the client with a NullReferenceException
     * because no matching DropdownEntry exists.
     */
    private static boolean isValidCategory(String cat) {
        if (cat == null) return false;
        switch (cat) {
            case "weapons":
            case "armor":
            case "tools":
            case "resources":
            case "potions":
            case "food":
            case "building":
            case "misc":
                return true;
            default:
                return false;
        }
    }

    /**
     * Copies existing shop items from ShopData into the staging container.
     *
     * <p>Restores any persisted BSON metadata so the editor shows the
     * enchanted / modified version of the item exactly as it was captured
     * at save time. Legacy rows (null itemMetadata) fall back to a vanilla
     * ItemStack without metadata.</p>
     */
    private void populateStagingFromShop() {
        List<ShopItem> items = shopData.getItems();
        for (ShopItem item : items) {
            int slot = item.getSlot();
            if (slot >= 0 && slot < stagingContainer.getCapacity()) {
                String itemId = item.getItemId();
                int quantity = item.isUnlimitedStock() ? 1 : Math.max(1, item.getStock());

                BsonDocument bsonMeta = BsonMetadataCodec.decode(item.getItemMetadata());
                ItemStack stack = (bsonMeta != null)
                    ? new ItemStack(itemId, quantity, bsonMeta)
                    : new ItemStack(itemId, quantity);
                stagingContainer.setItemStackForSlot((short) slot, stack);

                // Copy metadata
                StagedItemMeta meta = new StagedItemMeta(
                    item.getBuyPrice(),
                    item.getSellPrice(),
                    item.getMaxStock(),
                    item.isBuyEnabled(),
                    item.isSellEnabled()
                );
                meta.buyQuota = item.getDailyBuyLimit(); // Reused as buyback quota
                stagingMeta[slot] = meta;
            }
        }
    }

    /**
     * Returns the items that are in the staging container but were NOT in the original shop.
     * These items must be returned to the player on close without save.
     *
     * FIX 1 (CRITICAL: dupe prevention): Uses a multiset diff against the snapshot taken
     * at open time (originalItemCounts), NOT a per-slot equality check. Otherwise moving
     * an original item from slot A -> slot B and discarding would return the moved item
     * to the player AND leave the original in DB -> dupe.
     */
    private List<ItemStack> getStagingOnlyItems() {
        // Sum current quantities per itemId across the entire staging container
        Map<String, Integer> currentCounts = new HashMap<>();
        for (int i = 0; i < stagingContainer.getCapacity(); i++) {
            ItemStack stack = stagingContainer.getItemStack((short) i);
            if (stack != null && !stack.isEmpty()) {
                currentCounts.merge(stack.getItemId(), stack.getQuantity(), Integer::sum);
            }
        }

        // Anything beyond the original count is a NET addition by the user during this
        // editing session and must be returned to the inventory on discard.
        List<ItemStack> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : currentCounts.entrySet()) {
            int diff = entry.getValue() - originalItemCounts.getOrDefault(entry.getKey(), 0);
            if (diff > 0) {
                result.add(new ItemStack(entry.getKey(), diff));
            }
        }
        return result;
    }

    private StagedItemMeta getOrCreateMeta(int slot) {
        if (slot < 0 || slot >= stagingMeta.length) {
            return new StagedItemMeta();
        }
        if (stagingMeta[slot] == null) {
            stagingMeta[slot] = new StagedItemMeta();
        }
        return stagingMeta[slot];
    }

    private int getMaxShopPage() {
        int totalSlots = stagingContainer.getCapacity();
        return Math.max(0, (int) Math.ceil(totalSlots / (double) SHOP_SLOTS_PER_PAGE) - 1);
    }

    /**
     * Called by ContainerWindow.registerCloseEvent when the window closes.
     * Handles save-or-revert logic before the page is dismissed.
     */
    public void onWindowClose() {
        LOGGER.info("[ShopEdit] Window closed for shop: " + shopData.getName() + " (saved=" + saved + ")");
    }

    /**
     * BUG #2 FIX (CRITICAL: dupe prevention):
     *
     * When the owner dismisses WITHOUT saving, any items that were in the
     * original shop but are missing from staging must have been moved to the
     * player's inventory via drag & drop (which mutates the real player
     * containers directly). Because we do NOT overwrite shopData on discard,
     * the DB still believes the shop owns those items -- so the player ends
     * up with the items in their inventory AND the shop still has them in DB.
     * Net effect: item duplication (x2 items from nothing).
     *
     * Counter-measure: compute the reverse diff (originalCount - currentCount)
     * per itemId, and remove that many items from the player's hotbar+storage.
     * This restores the invariant: what's in DB matches what the player has.
     *
     * Called from {@link #onDismiss(Ref, Store)} ONLY when saved == false.
     */
    private void restoreOriginalShopItemsToStaging(Ref<EntityStore> ref, Store<EntityStore> store) {
        // Sum current quantities per itemId across the entire staging container
        Map<String, Integer> currentCounts = new HashMap<>();
        for (int i = 0; i < stagingContainer.getCapacity(); i++) {
            ItemStack stack = stagingContainer.getItemStack((short) i);
            if (stack != null && !stack.isEmpty()) {
                currentCounts.merge(stack.getItemId(), stack.getQuantity(), Integer::sum);
            }
        }

        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        com.kyuubisoft.shops.util.PlayerInventoryAccess inv =
            com.kyuubisoft.shops.util.PlayerInventoryAccess.of(p);

        boolean invChanged = false;
        for (Map.Entry<String, Integer> entry : originalItemCounts.entrySet()) {
            String itemId = entry.getKey();
            int originalQty = entry.getValue();
            int currentQty = currentCounts.getOrDefault(itemId, 0);
            int missing = originalQty - currentQty;

            if (missing > 0) {
                // Missing items from shop -> they must be in the player's inventory
                // (the drag&drop path wrote them there directly). Remove that amount
                // from hotbar + storage; the shop (DB) still owns them.
                int remaining = missing;
                remaining = removeFromContainer(inv.getHotbar(), itemId, remaining);
                if (remaining > 0) {
                    remaining = removeFromContainer(inv.getStorage(), itemId, remaining);
                }
                if (remaining > 0) {
                    LOGGER.warning("[ShopEdit] Dupe prevention: could not remove " + remaining
                        + "x " + itemId + " from player inventory (items already spent?)");
                }
                invChanged = true;
            }
        }
        if (invChanged) {
            inv.markChanged();
        }
    }

    /**
     * Removes up to {@code amount} of {@code itemId} from the given container.
     * Returns the leftover amount that could not be removed (0 = success).
     */
    private int removeFromContainer(ItemContainer container, String itemId, int amount) {
        if (container == null || amount <= 0) return amount;
        for (int i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack((short) i);
            if (stack != null && !stack.isEmpty() && itemId.equals(stack.getItemId())) {
                int take = Math.min(amount, stack.getQuantity());
                if (take >= stack.getQuantity()) {
                    container.removeItemStackFromSlot((short) i);
                } else {
                    ItemStack reduced = new ItemStack(stack.getItemId(), stack.getQuantity() - take);
                    container.setItemStackForSlot((short) i, reduced);
                }
                amount -= take;
                if (amount == 0) return 0;
            }
        }
        return amount;
    }

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[ShopEdit] onDismiss error: " + e.getMessage());
        }

        // BUG #11: If the owner closed the editor with unsaved changes, send a
        // chat warning so they know their edits were discarded. This is a
        // non-blocking notification: we don't prevent the dismiss or pop a
        // confirm dialog — the owner can simply reopen the editor.
        if (!saved && dirty) {
            try {
                Player p = store.getComponent(ref, Player.getComponentType());
                if (p != null) {
                    p.sendMessage(Message.raw(
                        plugin.getI18n().get(playerRef, "shop.edit.unsaved_warning")
                    ).color("#ffaa00"));
                }
            } catch (Exception ignored) {}
        }

        // If not saved, return new items to player inventory
        if (!saved) {
            try {
                List<ItemStack> returnItems = getStagingOnlyItems();
                if (!returnItems.isEmpty()) {
                    Player p = store.getComponent(ref, Player.getComponentType());
                    if (p != null) {
                        var storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
                        if (storageComp != null) {
                            ItemContainer storage = storageComp.getInventory();
                            for (ItemStack stack : returnItems) {
                                storage.addItemStack(stack);
                            }
                            storageComp.markDirty();
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("[ShopEdit] Failed to return items on dismiss: " + e.getMessage());
            }

            // BUG #2 FIX (CRITICAL: dupe prevention): On discard we also need to
            // handle the REVERSE direction: items the owner pulled OUT of the
            // shop grid into their own inventory. Because the drag&drop path
            // writes directly to the live player containers (and mutates them
            // via markChanged()), those items are already persisted on the
            // player. Since we do NOT overwrite shopData on discard, the DB
            // still thinks the shop owns them -- leading to duplication
            // (player keeps items + shop DB still lists them). Remove the
            // missing quantities from the player's inventory to restore the
            // invariant.
            try {
                restoreOriginalShopItemsToStaging(ref, store);
            } catch (Exception e) {
                LOGGER.warning("[ShopEdit] Failed to restore original shop items on dismiss: "
                    + e.getMessage());
            }
        }

        // Always unlock editor session
        plugin.getSessionManager().unlockEditor(shopData.getId(), playerRef.getUuid());

        LOGGER.fine("[ShopEdit] Page dismissed for " + playerRef.getUsername()
            + " (shop: " + shopData.getName() + ", saved: " + saved + ")");
    }

    // ==================== INNER CLASSES ====================

    /**
     * Holds staged price/mode metadata for a slot in the staging container.
     * Separate from the ItemStack since Hytale ItemStacks don't store shop metadata.
     */
    private static class StagedItemMeta {
        int buyPrice;
        int sellPrice;
        int maxStock;
        int buyQuota;       // How many more shop wants to buy from players (0 = unlimited)
        boolean buyEnabled;
        boolean sellEnabled;

        StagedItemMeta() {
            this.buyPrice = 10;
            this.sellPrice = 5;
            this.maxStock = -1;
            this.buyQuota = 0;
            this.buyEnabled = true;
            this.sellEnabled = false;
        }

        StagedItemMeta(int buyPrice, int sellPrice, int maxStock,
                       boolean buyEnabled, boolean sellEnabled) {
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.maxStock = maxStock;
            this.buyQuota = 0;
            this.buyEnabled = buyEnabled;
            this.sellEnabled = sellEnabled;
        }
    }

    // ==================== EVENT DATA CODEC ====================

    public static class EditData {
        public static final BuilderCodec<EditData> CODEC = BuilderCodec
            .<EditData>builder(EditData.class, EditData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .addField(new KeyedCodec<>("Tab", Codec.STRING),
                (data, value) -> data.tab = value,
                data -> data.tab)
            .addField(new KeyedCodec<>("Select", Codec.STRING),
                (data, value) -> data.select = value,
                data -> data.select)
            .addField(new KeyedCodec<>("Field", Codec.STRING),
                (data, value) -> data.field = value,
                data -> data.field)
            .addField(new KeyedCodec<>("Mode", Codec.STRING),
                (data, value) -> data.mode = value,
                data -> data.mode)
            .addField(new KeyedCodec<>("IconPick", Codec.STRING),
                (data, value) -> data.iconPick = value,
                data -> data.iconPick)
            .addField(new KeyedCodec<>("@Name", Codec.STRING),
                (data, value) -> data.nameVal = value,
                data -> data.nameVal)
            .addField(new KeyedCodec<>("@Desc", Codec.STRING),
                (data, value) -> data.descVal = value,
                data -> data.descVal)
            .addField(new KeyedCodec<>("@Price", Codec.STRING),
                (data, value) -> data.priceVal = value,
                data -> data.priceVal)
            .addField(new KeyedCodec<>("@BuyPrice", Codec.STRING),
                (data, value) -> data.buyPriceVal = value,
                data -> data.buyPriceVal)
            .addField(new KeyedCodec<>("@SellPrice", Codec.STRING),
                (data, value) -> data.sellPriceVal = value,
                data -> data.sellPriceVal)
            .addField(new KeyedCodec<>("@BuyQuota", Codec.STRING),
                (data, value) -> data.buyQuotaVal = value,
                data -> data.buyQuotaVal)
            .addField(new KeyedCodec<>("@StockLimit", Codec.STRING),
                (data, value) -> data.stockLimitVal = value,
                data -> data.stockLimitVal)
            .addField(new KeyedCodec<>("@Category", Codec.STRING),
                (data, value) -> data.categoryVal = value,
                data -> data.categoryVal)
            .addField(new KeyedCodec<>("@NpcSkin", Codec.STRING),
                (data, value) -> data.npcSkinVal = value,
                data -> data.npcSkinVal)
            .addField(new KeyedCodec<>("@DepositAmount", Codec.STRING),
                (data, value) -> data.depositAmountVal = value,
                data -> data.depositAmountVal)
            // Grid drop/click events (native ItemGrid interaction)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action)
            .addField(new KeyedCodec<>("SlotIndex", Codec.INTEGER),
                (data, value) -> data.slotIndex = value,
                data -> data.slotIndex)
            .addField(new KeyedCodec<>("SourceSlotId", Codec.INTEGER),
                (data, value) -> data.sourceSlotId = value,
                data -> data.sourceSlotId)
            .addField(new KeyedCodec<>("SourceInventorySectionId", Codec.INTEGER),
                (data, value) -> data.sourceSectionId = value,
                data -> data.sourceSectionId)
            .build();

        private String button;
        private String tab;
        private String select;
        private String field;
        private String mode;
        private String iconPick;
        private String nameVal;
        private String descVal;
        private String priceVal;
        private String buyPriceVal;
        private String sellPriceVal;
        private String buyQuotaVal;
        private String stockLimitVal;
        private String categoryVal;
        private String npcSkinVal;
        private String depositAmountVal;
        // Grid interaction fields
        private String action;
        private Integer slotIndex;
        private Integer sourceSlotId;
        private Integer sourceSectionId;

        public EditData() {}
    }
}
