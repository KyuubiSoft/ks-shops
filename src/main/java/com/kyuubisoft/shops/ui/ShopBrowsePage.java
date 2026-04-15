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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopItem;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.ShopService;
import com.kyuubisoft.shops.util.BsonMetadataCodec;

import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Buyer browse page for KS-Shops.
 *
 * Displays a native 9x5 ItemGrid (45 items per page) with pagination,
 * info bar (owner, rating, item count), buy/sell tabs, and
 * confirmation overlay with tax display. Clicking a slot opens the
 * confirmation dialog to purchase (BUY mode) or sell (SELL mode).
 *
 * Opened via ShopService.openShopForBuyer() or NPC interaction.
 *
 * Usage:
 * <pre>
 *   ShopBrowsePage page = new ShopBrowsePage(playerRef, player, plugin, shopData);
 *   player.getPageManager().openCustomPage(ref, store, page);
 * </pre>
 */
public class ShopBrowsePage extends InteractiveCustomUIPage<ShopBrowsePage.ShopBrowseData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    // Native ItemGrid is 9 cols x 5 rows = 45 slots per page. Prices and
    // stock are surfaced via ItemGridSlot.setName / setDescription so the
    // native tooltip shows them instantly on hover.
    static final int ITEMS_PER_PAGE = 45;

    public enum Mode { BUY, SELL }

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;
    private final ShopData shopData;

    private Mode mode;
    private int currentPage = 0;
    private boolean confirmActive = false;
    private int confirmSlotIndex = -1;
    private ShopItem confirmItem;
    private int confirmQuantity = 1;

    // Stored from build() for opening sub-pages
    private Ref<EntityStore> lastRef;
    private Store<EntityStore> lastStore;

    // Cached filtered item lists per mode
    private List<ShopItem> buyItems;
    private List<ShopItem> sellItems;

    public ShopBrowsePage(PlayerRef playerRef, Player player, ShopPlugin plugin, ShopData shopData) {
        this(playerRef, player, plugin, shopData, Mode.BUY);
    }

    public ShopBrowsePage(PlayerRef playerRef, Player player, ShopPlugin plugin,
                          ShopData shopData, Mode initialMode) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ShopBrowseData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.shopData = shopData;
        this.mode = initialMode;
        rebuildItemLists();
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        this.lastRef = ref;
        this.lastStore = store;

        ui.append("Pages/Shop/ShopBrowse.ui");

        // Configure native ItemGrid (display-only, click-to-buy).
        // No ContainerWindow is backing this grid -- it is pure display
        // driven by ui.set("#BrowseGrid.Slots", ...). DisplayItemQuantity
        // is on so the native slot quantity badge shows the stock count
        // (we set ItemStack.quantity = stock in BUY mode below).
        ui.set("#BrowseGrid.AreItemsDraggable", false);
        ui.set("#BrowseGrid.DisplayItemQuantity", true);

        bindAllEvents(events);
        buildUI(ui);
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull ShopBrowseData data) {
        super.handleDataEvent(ref, store, data);
        this.lastRef = ref;
        this.lastStore = store;

        if (data.tab != null) {
            handleTab(data.tab);
            return;
        }

        if (data.confirm != null) {
            handleConfirm(data.confirm);
            return;
        }

        if (data.button != null) {
            handleButton(data.button);
            return;
        }

        // Native ItemGrid slot click (BUY or SELL depending on mode)
        if ("browse_click".equals(data.action)) {
            handleBrowseSlotClick(data.slotIndex);
            return;
        }

        // Slider-driven quantity (replaces the old +/- buttons)
        if (data.confirmQty != null) {
            handleConfirmQty(data.confirmQty);
            return;
        }

        if (data.rate != null) {
            handleRate();
            return;
        }

        // Catch-all: send empty response to prevent permanent "Loading..." state
        this.sendUpdate(new UICommandBuilder(), false);
    }

    // ==================== TAB SWITCHING ====================

    private void handleTab(String tab) {
        Mode newMode = "sell".equals(tab) ? Mode.SELL : Mode.BUY;
        if (newMode == mode) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        mode = newMode;
        currentPage = 0;
        confirmActive = false;
        confirmSlotIndex = -1;
        confirmItem = null;

        rebuildItemLists();
        refreshUI();
    }

    // ==================== BUTTON HANDLING ====================

    private void handleButton(String button) {
        switch (button) {
            case "prev_page" -> {
                if (currentPage > 0) {
                    currentPage--;
                    refreshUI();
                } else {
                    this.sendUpdate(new UICommandBuilder(), false);
                }
            }
            case "next_page" -> {
                int maxPage = getMaxPage();
                if (currentPage < maxPage) {
                    currentPage++;
                    refreshUI();
                } else {
                    this.sendUpdate(new UICommandBuilder(), false);
                }
            }
            case "back" -> {
                if (lastRef == null || lastStore == null) {
                    this.sendUpdate(new UICommandBuilder(), false);
                    return;
                }
                try {
                    ShopDirectoryPage dir = new ShopDirectoryPage(playerRef, player, plugin);
                    player.getPageManager().openCustomPage(lastRef, lastStore, dir);
                } catch (Exception e) {
                    LOGGER.warning("[ShopBrowse] Failed to open directory page: " + e.getMessage());
                    this.sendUpdate(new UICommandBuilder(), false);
                }
            }
            default -> this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    /**
     * Slider-driven quantity update. Clamps to [1, max] where max comes from
     * {@link #getConfirmMaxQuantity()}; values outside the range are silently
     * coerced so the slider's static 1..64 range never lets the user overshoot
     * the real stock / stack limit.
     */
    private void handleConfirmQty(int qty) {
        if (!confirmActive || confirmItem == null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        int max = getConfirmMaxQuantity();
        if (qty < 1) qty = 1;
        if (qty > max) qty = max;
        if (qty == confirmQuantity) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        confirmQuantity = qty;
        refreshUI();
    }

    // ==================== BROWSE GRID CLICK ====================

    /**
     * Handles a click on a native ItemGrid slot in BUY or SELL mode.
     * Pre-fills the confirmation dialog with the clicked ShopItem so that
     * the existing #ConfirmYes / slider wiring can drive the transaction.
     */
    private void handleBrowseSlotClick(Integer slotIndex) {
        if (slotIndex == null || slotIndex < 0 || slotIndex >= ITEMS_PER_PAGE) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        int actualIndex = currentPage * ITEMS_PER_PAGE + slotIndex;
        if (mode == Mode.BUY) {
            handleBuyClick(actualIndex);
        } else {
            handleSellClick(actualIndex);
        }
    }

    private void handleBuyClick(int actualIndex) {
        ShopI18n i18n = plugin.getI18n();

        if (buyItems != null && actualIndex >= 0 && actualIndex < buyItems.size()) {
            ShopItem item = buyItems.get(actualIndex);

            // Validate stock before opening confirmation
            if (!item.hasStock()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.browse.out_of_stock")).color("#FF5555"));
                refreshUI();
                return;
            }

            // Show confirmation dialog (actual purchase happens in handleConfirm("yes"))
            confirmActive = true;
            confirmSlotIndex = actualIndex;
            confirmItem = item;
            confirmQuantity = 1;
            refreshUI();
            return;
        }

        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleSellClick(int actualIndex) {
        if (sellItems != null && actualIndex >= 0 && actualIndex < sellItems.size()) {
            ShopItem item = sellItems.get(actualIndex);
            if (item.getSellPrice() > 0) {
                // Show confirmation dialog
                confirmActive = true;
                confirmSlotIndex = actualIndex;
                confirmItem = item;
                confirmQuantity = 1;
                refreshUI();
                return;
            }
        }

        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleConfirm(String action) {
        switch (action) {
            case "yes" -> {
                if (confirmActive && confirmItem != null) {
                    ShopI18n i18n = plugin.getI18n();
                    ShopItem item = confirmItem;

                    if (mode == Mode.SELL) {
                        boolean success = plugin.getShopService().sellItem(
                            playerRef, shopData.getId(), item.getItemId(), confirmQuantity
                        );
                        if (!success) {
                            player.sendMessage(Message.raw(
                                i18n.get(playerRef, "shop.browse.sell_failed")).color("#FF5555"));
                        }
                    } else {
                        // Buy with quantity. Use the typed result so failures
                        // surface a specific reason (own shop, no funds, out
                        // of stock, etc) instead of a generic "purchase failed".
                        ShopService.PurchaseResult result = plugin.getShopService()
                            .purchaseItemWithReason(playerRef, shopData.getId(),
                                item.getSlot(), confirmQuantity);
                        if (result.isSuccess()) {
                            int totalPaid = item.getBuyPrice() * confirmQuantity;
                            String currencyName = plugin.getEconomyBridge().getCurrencyName();
                            player.sendMessage(Message.raw(
                                i18n.get(playerRef, "shop.buy.success",
                                    confirmQuantity,
                                    formatItemName(item.getItemId()),
                                    totalPaid,
                                    currencyName)).color("#44FF44"));
                        } else {
                            String key = result.getErrorKey() != null
                                ? result.getErrorKey()
                                : "shop.browse.purchase_failed";
                            player.sendMessage(Message.raw(
                                i18n.get(playerRef, key)).color("#FF5555"));
                        }
                    }

                    rebuildItemLists();
                }
                confirmActive = false;
                confirmSlotIndex = -1;
                confirmItem = null;
                refreshUI();
            }
            case "no" -> {
                confirmActive = false;
                confirmSlotIndex = -1;
                confirmItem = null;
                refreshUI();
            }
            default -> this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== RATE ====================

    private void handleRate() {
        LOGGER.fine("[ShopBrowse] Rate requested for shop " + shopData.getId()
            + " by " + playerRef.getUsername());

        if (lastRef == null || lastStore == null) {
            LOGGER.warning("[ShopBrowse] Cannot open rating page: ref/store not available");
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        try {
            ShopRatingPage ratingPage = new ShopRatingPage(playerRef, player, plugin, shopData);
            player.getPageManager().openCustomPage(lastRef, lastStore, ratingPage);
        } catch (Exception e) {
            LOGGER.warning("[ShopBrowse] Failed to open rating page: " + e.getMessage());
            player.sendMessage(Message.raw(
                plugin.getI18n().get(playerRef, "shop.error.open_failed")).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== REFRESH ====================

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindAllEvents(events);
        buildUI(ui);
        this.sendUpdate(ui, events, false);
    }

    // ==================== EVENT BINDING ====================

    private void bindAllEvents(UIEventBuilder events) {
        // Pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevButton",
            EventData.of("Button", "prev_page"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton",
            EventData.of("Button", "next_page"), false);

        // Native ItemGrid slot clicks -- buy (BUY mode) or sell (SELL mode).
        // SlotIndex is auto-populated by Hytale on SlotClicking events via
        // the "SlotIndex" KeyedCodec field in ShopBrowseData.
        events.addEventBinding(CustomUIEventBindingType.SlotClicking, "#BrowseGrid",
            EventData.of("Action", "browse_click"), false);

        // Tab buttons
        boolean hasSellItems = shopData.getItems().stream().anyMatch(ShopItem::isSellEnabled);
        boolean hasBuyItems = shopData.getItems().stream().anyMatch(ShopItem::isBuyEnabled);
        if (hasBuyItems && hasSellItems) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #BuyTab",
                EventData.of("Tab", "buy"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #SellTab",
                EventData.of("Tab", "sell"), false);
        }

        // Back button (navigates back to the Shop Directory)
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn",
            EventData.of("Button", "back"), false);

        // Rate button
        if (shopData.isPlayerShop()) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RateButton",
                EventData.of("Rate", "rate"), false);
        }

        // Confirmation buttons (SliderNumberField replaces the old +/- buttons)
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmYes",
            EventData.of("Confirm", "yes"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmNo",
            EventData.of("Confirm", "no"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ConfirmQtySlider",
            EventData.of("@ConfirmQty", "#ConfirmQtySlider.Value"), false);
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
        double balance = plugin.getEconomyBridge().getBalance(playerRef.getUuid());

        // Ensure item lists are in sync with current shop data
        rebuildItemLists();

        // ---- Title bar ----
        String title = shopData.getName() != null ? shopData.getName() : i18n.get(playerRef, "shop.browse.title");
        ui.set("#Title #ShopTitle.Text", title);

        // Shop avatar: prefer owner-picked icon, fall back to first shop item.
        // Use the FLAT selector (#ShopAvatar.ItemId) because #Title is a
        // DecoratedContainer macro slot, not a normal element. 3-level
        // selectors through #Title silently drop on the client.
        String avatarIconId = shopData.getDisplayIconItemId();
        if (avatarIconId != null) {
            ui.set("#ShopAvatar.ItemId", avatarIconId);
        }

        // Currency display
        String currencyName = plugin.getEconomyBridge().getCurrencyName();
        ui.set("#Title #CurrencyDisplay #CurrencyBalance.Text", plugin.getEconomyBridge().format(balance));
        ui.set("#Title #CurrencyDisplay #CurrencyIcon.ItemId", "Ingredient_Bar_Gold");

        // ---- Info bar ----
        buildInfoBar(ui, i18n);

        // ---- Tab bar ----
        boolean hasSellItems = shopData.getItems().stream().anyMatch(ShopItem::isSellEnabled);
        boolean hasBuyItems = shopData.getItems().stream().anyMatch(ShopItem::isBuyEnabled);
        boolean showTabs = hasBuyItems && hasSellItems;

        // Auto-switch to the only available mode if one tab has no items
        if (!hasBuyItems && hasSellItems && mode == Mode.BUY) {
            mode = Mode.SELL;
        } else if (!hasSellItems && hasBuyItems && mode == Mode.SELL) {
            mode = Mode.BUY;
        }

        if (showTabs) {
            ui.set("#TabBar.Visible", true);
            String buyLabel = i18n.get(playerRef, "shop.browse.tab.buy");
            String sellLabel = i18n.get(playerRef, "shop.browse.tab.sell");
            if (mode == Mode.BUY) {
                ui.set("#TabBar #BuyTab.Text", "[ " + buyLabel + " ]");
                ui.set("#TabBar #SellTab.Text", sellLabel);
            } else {
                ui.set("#TabBar #BuyTab.Text", buyLabel);
                ui.set("#TabBar #SellTab.Text", "[ " + sellLabel + " ]");
            }
        } else {
            ui.set("#TabBar.Visible", false);
        }

        // ---- Empty state (when active list is empty) ----
        List<ShopItem> currentList = (mode == Mode.BUY) ? buyItems : sellItems;
        boolean isEmpty = (currentList == null || currentList.isEmpty());
        ui.set("#EmptyStateLabel.Visible", isEmpty);
        ui.set("#CardContainer.Visible", !isEmpty);

        // ---- Browse grid ----
        populateBrowseGrid(ui);

        // ---- Confirmation overlay ----
        buildConfirmOverlay(ui, i18n, balance);
    }

    private void buildInfoBar(UICommandBuilder ui, ShopI18n i18n) {
        // Owner info
        if (shopData.isPlayerShop() && shopData.getOwnerName() != null) {
            ui.set("#InfoBar #InfoOwner.Text",
                i18n.get(playerRef, "shop.browse.by", shopData.getOwnerName()));
        } else if (shopData.isAdminShop()) {
            ui.set("#InfoBar #InfoOwner.Text",
                i18n.get(playerRef, "shop.browse.admin_shop"));
        } else {
            ui.set("#InfoBar #InfoOwner.Text", "");
        }

        // Star rating
        double avgRating = shopData.getAverageRating();
        int totalRatings = shopData.getTotalRatings();
        if (totalRatings > 0) {
            String stars = buildStarString(avgRating);
            ui.set("#InfoBar #InfoRating.Text",
                stars + " (" + String.format("%.1f", avgRating) + ")");
        } else {
            ui.set("#InfoBar #InfoRating.Text",
                i18n.get(playerRef, "shop.browse.no_ratings"));
        }

        // Item count
        List<ShopItem> activeItems = (mode == Mode.BUY) ? buyItems : sellItems;
        int itemCount = (activeItems != null) ? activeItems.size() : 0;
        ui.set("#InfoBar #InfoItemCount.Text",
            i18n.get(playerRef, "shop.browse.item_count", itemCount));

        // Rate button (only for player shops, not own shop)
        boolean canRate = shopData.isPlayerShop()
            && !playerRef.getUuid().equals(shopData.getOwnerUuid());
        ui.set("#InfoBar #RateButton.Visible", canRate);
        if (canRate) {
            ui.set("#InfoBar #RateButton.Text", i18n.get(playerRef, "shop.browse.rate"));
        }
    }

    /**
     * Builds the native ItemGrid slot list from the active buy/sell item
     * list and pushes it to the client via ui.set("#BrowseGrid.Slots", ...).
     *
     * Each slot overrides {@link ItemGridSlot#setName(String)} and
     * {@link ItemGridSlot#setDescription(String)} so the hover tooltip
     * shows the price and stock instantly for every item - no clicking
     * required. Uses Minecraft-style §-codes for per-line coloring, same
     * pattern as ShopDirectoryPage.populateItemSearchGrid().
     *
     * PITFALL #121: openCustomPage() never auto-populates ItemGrid -- slots
     * MUST be set manually. Each slot is marked activatable so SlotClicking
     * events fire even though AreItemsDraggable is false.
     */
    private void populateBrowseGrid(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();
        List<ShopItem> activeItems = (mode == Mode.BUY) ? buyItems : sellItems;
        int totalItems = (activeItems != null) ? activeItems.size() : 0;
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) ITEMS_PER_PAGE));

        // Clamp current page
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        int startIndex = currentPage * ITEMS_PER_PAGE;
        String currencyName = plugin.getEconomyBridge().getCurrencyName();

        List<ItemGridSlot> slots = new ArrayList<>(ITEMS_PER_PAGE);
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int actualIndex = startIndex + i;
            ItemGridSlot slot;

            if (activeItems != null && actualIndex < activeItems.size()) {
                ShopItem item = activeItems.get(actualIndex);
                String itemId = item.getItemId();
                int unitPrice = (mode == Mode.SELL) ? item.getSellPrice() : item.getBuyPrice();

                // ItemStack quantity drives the native slot badge when
                // DisplayItemQuantity is on. BUY mode shows the remaining
                // stock as the badge (unlimited -> 1 since the badge cannot
                // render "infinite"); SELL mode is always a single unit.
                int quantityToShow;
                if (mode == Mode.BUY) {
                    quantityToShow = item.isUnlimitedStock() ? 1 : Math.max(1, item.getStock());
                } else {
                    quantityToShow = 1;
                }

                try {
                    BsonDocument meta = BsonMetadataCodec.decode(item.getItemMetadata());
                    ItemStack stack = (meta != null)
                        ? new ItemStack(itemId, quantityToShow, meta)
                        : new ItemStack(itemId, quantityToShow);
                    slot = new ItemGridSlot(stack);
                } catch (Exception e) {
                    LOGGER.warning("[ShopBrowse] Failed to build slot for item " + itemId
                        + ": " + e.getMessage());
                    slot = new ItemGridSlot();
                }

                // Tooltip header: item name with price inline so buyers see
                // the cost the moment they hover over a slot. Plain text -
                // Hytale's slot tooltip renderer does not parse Minecraft
                // §-color codes, they render literally.
                slot.setName(formatItemName(itemId)
                    + "  -  " + unitPrice + " " + currencyName);

                // Tooltip description: stock + mode-aware click hint.
                StringBuilder desc = new StringBuilder();
                if (item.isUnlimitedStock()) {
                    desc.append("Stock: Unlimited");
                } else {
                    desc.append("Stock: ").append(item.getStock());
                }
                desc.append('\n');
                if (mode == Mode.SELL) {
                    desc.append("Click to sell");
                } else {
                    desc.append("Click to buy");
                }
                slot.setDescription(desc.toString());
            } else {
                slot = new ItemGridSlot();
            }

            // PFLICHT: setActivatable(true) so SlotClicking fires on click
            // even when AreItemsDraggable = false.
            slot.setActivatable(true);
            slots.add(slot);
        }

        ui.set("#BrowseGrid.Slots", slots);

        // Pagination
        ui.set("#Footer #PageInfo.Text",
            i18n.get(playerRef, "shop.browse.page", currentPage + 1, totalPages));
        ui.set("#Footer #PrevButton.Visible", currentPage > 0);
        ui.set("#Footer #NextButton.Visible", currentPage < totalPages - 1);
    }

    private void buildConfirmOverlay(UICommandBuilder ui, ShopI18n i18n, double balance) {
        ui.set("#ConfirmOverlay.Visible", confirmActive);

        if (!confirmActive || confirmItem == null) return;

        ShopItem item = confirmItem;
        String itemName = formatItemName(item.getItemId());
        int unitPrice = (mode == Mode.SELL) ? item.getSellPrice() : item.getBuyPrice();

        // BUG #7 fix: Tax is currently disabled in ShopService (taxAmount forced to 0).
        // UI must match — no tax surcharge is added to the grand total.
        int maxQty = getConfirmMaxQuantity();
        if (confirmQuantity > maxQty) confirmQuantity = maxQty;
        if (confirmQuantity < 1) confirmQuantity = 1;

        int grandTotal = unitPrice * confirmQuantity;

        // Title
        String confirmTitle = (mode == Mode.SELL)
            ? i18n.get(playerRef, "shop.browse.confirm.sell")
            : i18n.get(playerRef, "shop.browse.confirm.buy");
        ui.set("#ConfirmTitle.Text", confirmTitle);

        // Item info
        ui.set("#ConfirmIcon.ItemId", item.getItemId());
        ui.set("#ConfirmItemName.Text", itemName);
        ui.set("#ConfirmPrice.Text",
            i18n.get(playerRef, "shop.browse.confirm.price", unitPrice));

        // Stock line with dynamic color: green/amber/red for low stock,
        // cyan for unlimited. Same palette as the directory buy overlay.
        if (item.isUnlimitedStock()) {
            ui.set("#ConfirmStock.Text",
                i18n.get(playerRef, "shop.browse.stock_unlimited"));
            ui.set("#ConfirmStock.Style.TextColor", "#26c6da");
        } else {
            int stock = item.getStock();
            ui.set("#ConfirmStock.Text",
                i18n.get(playerRef, "shop.browse.stock_count", stock));
            String stockColor;
            if (stock <= 2) stockColor = "#ff5252";
            else if (stock <= 10) stockColor = "#ffb74d";
            else stockColor = "#66bb6a";
            ui.set("#ConfirmStock.Style.TextColor", stockColor);
        }

        // Quantity slider + big label (replaces the old +/- buttons).
        // SliderNumberField.Min/Max are static in the .ui (1..64); Java
        // clamps purchase to the real stock in handleConfirmQty.
        ui.set("#ConfirmQtySlider.Value", confirmQuantity);
        ui.set("#ConfirmQty.Text", String.valueOf(confirmQuantity));

        // Total (no tax — grandTotal == subtotal)
        ui.set("#ConfirmTotal.Text",
            i18n.get(playerRef, "shop.browse.confirm.total", grandTotal));

        // Buttons
        ui.set("#ConfirmYes.Text", i18n.get(playerRef, "shop.browse.confirm.yes"));
        ui.set("#ConfirmNo.Text", i18n.get(playerRef, "shop.browse.confirm.no"));
    }

    // ==================== HELPERS ====================

    private void rebuildItemLists() {
        List<ShopItem> allItems = shopData.getItems();

        buyItems = allItems.stream()
            .filter(ShopItem::isBuyEnabled)
            .collect(Collectors.toList());

        sellItems = allItems.stream()
            .filter(ShopItem::isSellEnabled)
            .filter(i -> i.getSellPrice() > 0)
            .collect(Collectors.toList());
    }

    private int getMaxPage() {
        List<ShopItem> activeItems = (mode == Mode.SELL) ? sellItems : buyItems;
        int totalItems = (activeItems != null) ? activeItems.size() : 0;
        return Math.max(0, (int) Math.ceil(totalItems / (double) ITEMS_PER_PAGE) - 1);
    }

    private int getConfirmMaxQuantity() {
        if (confirmItem == null) return 1;

        ShopItem item = confirmItem;

        if (mode == Mode.BUY) {
            // Limited by stock (if not unlimited) and by player balance
            int max = item.isUnlimitedStock() ? 64 : item.getStock();

            // BUG #7 fix: Tax disabled in ShopService — costPerUnit is just the buy price.
            int costPerUnit = item.getBuyPrice();

            if (costPerUnit > 0) {
                double balance = plugin.getEconomyBridge().getBalance(playerRef.getUuid());
                int affordable = (int) (balance / costPerUnit);
                if (affordable < max) max = affordable;
            }

            if (item.getDailyBuyLimit() > 0 && item.getDailyBuyLimit() < max) {
                max = item.getDailyBuyLimit();
            }

            return Math.max(1, Math.min(max, 64));
        } else {
            // Sell mode: limited by stack size (64) or daily sell limit
            int max = 64;
            if (item.getDailySellLimit() > 0 && item.getDailySellLimit() < max) {
                max = item.getDailySellLimit();
            }
            // Stock capacity: if max stock is set and stock is close to full
            if (!item.isUnlimitedStock() && item.getMaxStock() > 0) {
                int room = item.getMaxStock() - item.getStock();
                if (room < max) max = room;
            }
            return Math.max(1, max);
        }
    }

    /**
     * Builds a star string representation (filled/empty stars).
     * Uses ASCII characters only: * for filled, . for empty.
     */
    private String buildStarString(double rating) {
        int maxStars = plugin.getShopConfig().getData().ratings.maxStars;
        int filled = (int) Math.round(rating);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxStars; i++) {
            sb.append(i < filled ? "*" : ".");
        }
        return sb.toString();
    }

    /**
     * Converts an item ID like "Ingredient_Bar_Gold" to a human-readable name
     * "Bar Gold" by stripping common prefixes and title-casing each word.
     */
    static String formatItemName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "Unknown";

        // Remove common prefixes
        String name = itemId;
        String[] prefixes = {"Ingredient_", "Weapon_", "Armor_", "Tool_", "Consumable_", "Block_"};
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }

        // Replace underscores with spaces
        name = name.replace("_", " ");

        // Title case each word
        String[] words = name.split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(" ");
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1).toLowerCase());
            }
        }
        return result.toString();
    }

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[ShopBrowse] onDismiss error: " + e.getMessage());
        }

        LOGGER.fine("[ShopBrowse] Page dismissed for " + playerRef.getUsername()
            + " at shop " + shopData.getName());
    }

    // ==================== EVENT DATA CODEC ====================

    public static class ShopBrowseData {
        public static final BuilderCodec<ShopBrowseData> CODEC = BuilderCodec
            .<ShopBrowseData>builder(ShopBrowseData.class, ShopBrowseData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .addField(new KeyedCodec<>("Tab", Codec.STRING),
                (data, value) -> data.tab = value,
                data -> data.tab)
            .addField(new KeyedCodec<>("Confirm", Codec.STRING),
                (data, value) -> data.confirm = value,
                data -> data.confirm)
            .addField(new KeyedCodec<>("Rate", Codec.STRING),
                (data, value) -> data.rate = value,
                data -> data.rate)
            // Native ItemGrid interaction fields
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action)
            .addField(new KeyedCodec<>("SlotIndex", Codec.INTEGER),
                (data, value) -> data.slotIndex = value,
                data -> data.slotIndex)
            .addField(new KeyedCodec<>("@ConfirmQty", Codec.INTEGER),
                (data, value) -> data.confirmQty = value,
                data -> data.confirmQty)
            .build();

        private String button;
        private String tab;
        private String confirm;
        private String rate;
        // Grid interaction fields
        private String action;
        private Integer slotIndex;
        // Slider-driven quantity (replaces the old +/- buttons)
        private Integer confirmQty;

        public ShopBrowseData() {}
    }
}
