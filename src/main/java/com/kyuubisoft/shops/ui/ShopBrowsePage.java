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

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Buyer browse page for KS-Shops.
 *
 * Displays a 3x3 card grid (9 items per page) with pagination,
 * info bar (owner, rating, item count), buy/sell tabs, and
 * confirmation overlay with tax display.
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
    static final int ITEMS_PER_PAGE = 9;

    public enum Mode { BUY, SELL }

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;
    private final ShopData shopData;

    private Mode mode;
    private int currentPage = 0;
    private boolean confirmActive = false;
    private int confirmSlotIndex = -1;
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

        if (data.buy != null) {
            if (mode == Mode.BUY) {
                handleBuy(data.buy);
            } else {
                handleSell(data.buy);
            }
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
            default -> this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== BUY ====================

    private void handleBuy(String slotStr) {
        ShopI18n i18n = plugin.getI18n();
        try {
            int slotIndex = Integer.parseInt(slotStr);
            int actualIndex = currentPage * ITEMS_PER_PAGE + slotIndex;

            if (buyItems != null && actualIndex >= 0 && actualIndex < buyItems.size()) {
                ShopItem item = buyItems.get(actualIndex);

                // Validate stock
                if (!item.hasStock()) {
                    player.sendMessage(Message.raw(i18n.get(playerRef, "shop.browse.out_of_stock")).color("#FF5555"));
                    refreshUI();
                    return;
                }

                // Validate balance
                double balance = plugin.getEconomyBridge().getBalance(playerRef.getUuid());
                int price = item.getBuyPrice();

                // Apply tax
                ShopConfig.Tax taxConfig = plugin.getShopConfig().getData().tax;
                int tax = 0;
                if (taxConfig.enabled) {
                    tax = (int) Math.ceil(price * taxConfig.buyTaxPercent / 100.0);
                }
                int totalCost = price + tax;

                if (balance < totalCost) {
                    player.sendMessage(Message.raw(i18n.get(playerRef, "shop.browse.not_enough")).color("#FF5555"));
                    refreshUI();
                    return;
                }

                // Execute purchase via ShopService
                boolean success = plugin.getShopService().purchaseItem(
                    playerRef, shopData.getId(), item.getSlot(), 1
                );

                if (!success) {
                    player.sendMessage(Message.raw(i18n.get(playerRef, "shop.browse.purchase_failed")).color("#FF5555"));
                }

                // Rebuild item lists to reflect stock changes
                rebuildItemLists();
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("[ShopBrowse] Invalid buy slot: " + slotStr);
        }

        refreshUI();
    }

    // ==================== SELL ====================

    private void handleSell(String slotStr) {
        try {
            int slotIndex = Integer.parseInt(slotStr);
            int actualIndex = currentPage * ITEMS_PER_PAGE + slotIndex;

            if (sellItems != null && actualIndex >= 0 && actualIndex < sellItems.size()) {
                ShopItem item = sellItems.get(actualIndex);
                if (item.getSellPrice() > 0) {
                    // Show confirmation dialog
                    confirmActive = true;
                    confirmSlotIndex = actualIndex;
                    confirmQuantity = 1;
                    refreshUI();
                    return;
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("[ShopBrowse] Invalid sell slot: " + slotStr);
        }

        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleConfirm(String action) {
        switch (action) {
            case "yes" -> {
                if (confirmActive && confirmSlotIndex >= 0) {
                    List<ShopItem> activeItems = (mode == Mode.SELL) ? sellItems : buyItems;
                    if (activeItems != null && confirmSlotIndex < activeItems.size()) {
                        ShopItem item = activeItems.get(confirmSlotIndex);

                        if (mode == Mode.SELL) {
                            boolean success = plugin.getShopService().sellItem(
                                playerRef, shopData.getId(), item.getItemId(), confirmQuantity
                            );
                            if (!success) {
                                ShopI18n i18n = plugin.getI18n();
                                player.sendMessage(Message.raw(
                                    i18n.get(playerRef, "shop.browse.sell_failed")).color("#FF5555"));
                            }
                        } else {
                            // Buy with quantity
                            boolean success = plugin.getShopService().purchaseItem(
                                playerRef, shopData.getId(), item.getSlot(), confirmQuantity
                            );
                            if (!success) {
                                ShopI18n i18n = plugin.getI18n();
                                player.sendMessage(Message.raw(
                                    i18n.get(playerRef, "shop.browse.purchase_failed")).color("#FF5555"));
                            }
                        }

                        rebuildItemLists();
                    }
                }
                confirmActive = false;
                confirmSlotIndex = -1;
                refreshUI();
            }
            case "no" -> {
                confirmActive = false;
                confirmSlotIndex = -1;
                refreshUI();
            }
            case "plus" -> {
                if (confirmActive && confirmSlotIndex >= 0) {
                    int max = getConfirmMaxQuantity();
                    if (confirmQuantity < max) confirmQuantity++;
                }
                refreshUI();
            }
            case "minus" -> {
                if (confirmActive && confirmQuantity > 1) confirmQuantity--;
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

        // Card clicks (buy or sell depending on mode)
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                "#CardGrid #Card" + i + " #CardBtn",
                EventData.of("Buy", String.valueOf(i)), false);
        }

        // Tab buttons
        boolean hasSellItems = shopData.getItems().stream().anyMatch(ShopItem::isSellEnabled);
        boolean hasBuyItems = shopData.getItems().stream().anyMatch(ShopItem::isBuyEnabled);
        if (hasBuyItems && hasSellItems) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #BuyTab",
                EventData.of("Tab", "buy"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #SellTab",
                EventData.of("Tab", "sell"), false);
        }

        // Rate button
        if (shopData.isPlayerShop()) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RateButton",
                EventData.of("Rate", "rate"), false);
        }

        // Confirmation buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmYes",
            EventData.of("Confirm", "yes"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmNo",
            EventData.of("Confirm", "no"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmPlus",
            EventData.of("Confirm", "plus"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmMinus",
            EventData.of("Confirm", "minus"), false);
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
        double balance = plugin.getEconomyBridge().getBalance(playerRef.getUuid());

        // ---- Title bar ----
        String title = shopData.getName() != null ? shopData.getName() : i18n.get(playerRef, "shop.browse.title");
        ui.set("#Title #ShopTitle.Text", title);

        // Currency display
        String currencyName = plugin.getEconomyBridge().getCurrencyName();
        ui.set("#Title #CurrencyDisplay #CurrencyBalance.Text", plugin.getEconomyBridge().format(balance));

        // ---- Info bar ----
        buildInfoBar(ui, i18n);

        // ---- Tab bar ----
        boolean hasSellItems = shopData.getItems().stream().anyMatch(ShopItem::isSellEnabled);
        boolean hasBuyItems = shopData.getItems().stream().anyMatch(ShopItem::isBuyEnabled);
        boolean showTabs = hasBuyItems && hasSellItems;

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

        // ---- Cards ----
        buildCards(ui, i18n, balance);

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

    private void buildCards(UICommandBuilder ui, ShopI18n i18n, double balance) {
        List<ShopItem> activeItems = (mode == Mode.BUY) ? buyItems : sellItems;
        int totalItems = (activeItems != null) ? activeItems.size() : 0;
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) ITEMS_PER_PAGE));

        // Clamp current page
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        ShopConfig.Tax taxConfig = plugin.getShopConfig().getData().tax;
        int startIndex = currentPage * ITEMS_PER_PAGE;

        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            String prefix = "#CardGrid #Card" + i;
            int actualIndex = startIndex + i;

            if (activeItems != null && actualIndex < activeItems.size()) {
                ShopItem item = activeItems.get(actualIndex);
                ui.set(prefix + ".Visible", true);

                // Item icon
                ui.set(prefix + " #ItemIcon.ItemId", item.getItemId());

                // Quantity badge (for stock display or bundle size)
                if (mode == Mode.BUY) {
                    if (!item.isUnlimitedStock() && item.getStock() > 0) {
                        ui.set(prefix + " #Quantity.Text", item.getStock() + "x");
                    } else {
                        ui.set(prefix + " #Quantity.Text", "");
                    }
                } else {
                    ui.set(prefix + " #Quantity.Text", "");
                }

                // Name
                String itemName = formatItemName(item.getItemId());
                ui.set(prefix + " #Name.Text", itemName);

                // Cost
                int price = (mode == Mode.BUY) ? item.getBuyPrice() : item.getSellPrice();
                ui.set(prefix + " #Cost.Text", String.valueOf(price));

                // Limit info
                if (mode == Mode.BUY && item.getDailyBuyLimit() > 0) {
                    ui.set(prefix + " #LimitInfo.Text",
                        i18n.get(playerRef, "shop.browse.daily_limit", item.getDailyBuyLimit()));
                } else if (mode == Mode.SELL && item.getDailySellLimit() > 0) {
                    ui.set(prefix + " #LimitInfo.Text",
                        i18n.get(playerRef, "shop.browse.daily_limit", item.getDailySellLimit()));
                } else if (!item.isUnlimitedStock() && mode == Mode.BUY) {
                    ui.set(prefix + " #LimitInfo.Text",
                        i18n.get(playerRef, "shop.browse.stock", item.getStock()));
                } else {
                    ui.set(prefix + " #LimitInfo.Text", "");
                }

                // Tooltip with seller info
                if (shopData.isPlayerShop() && shopData.getOwnerName() != null) {
                    ui.set(prefix + " #CardBtn.TooltipText",
                        i18n.get(playerRef, "shop.browse.tooltip.seller", shopData.getOwnerName()));
                } else {
                    ui.set(prefix + " #CardBtn.TooltipText", "");
                }

                // Overlay: out of stock / can't afford
                if (mode == Mode.BUY) {
                    int tax = 0;
                    if (taxConfig.enabled) {
                        tax = (int) Math.ceil(price * taxConfig.buyTaxPercent / 100.0);
                    }
                    int totalCost = price + tax;

                    if (!item.hasStock()) {
                        ui.set(prefix + " #Overlay.Visible", true);
                        ui.set(prefix + " #OverlayText.Text",
                            i18n.get(playerRef, "shop.browse.out_of_stock"));
                    } else if (balance < totalCost) {
                        ui.set(prefix + " #Overlay.Visible", true);
                        ui.set(prefix + " #OverlayText.Text",
                            i18n.get(playerRef, "shop.browse.not_enough"));
                    } else {
                        ui.set(prefix + " #Overlay.Visible", false);
                    }
                } else {
                    // Sell mode: no overlay (player just needs items in inventory)
                    ui.set(prefix + " #Overlay.Visible", false);
                }
            } else {
                ui.set(prefix + ".Visible", false);
            }
        }

        // Pagination
        ui.set("#Footer #PageInfo.Text",
            i18n.get(playerRef, "shop.browse.page", currentPage + 1, totalPages));
        ui.set("#Footer #PrevButton.Visible", currentPage > 0);
        ui.set("#Footer #NextButton.Visible", currentPage < totalPages - 1);
    }

    private void buildConfirmOverlay(UICommandBuilder ui, ShopI18n i18n, double balance) {
        ui.set("#ConfirmOverlay.Visible", confirmActive);

        if (!confirmActive || confirmSlotIndex < 0) return;

        List<ShopItem> activeItems = (mode == Mode.SELL) ? sellItems : buyItems;
        if (activeItems == null || confirmSlotIndex >= activeItems.size()) return;

        ShopItem item = activeItems.get(confirmSlotIndex);
        String itemName = formatItemName(item.getItemId());
        int unitPrice = (mode == Mode.SELL) ? item.getSellPrice() : item.getBuyPrice();

        // Tax calculation
        ShopConfig.Tax taxConfig = plugin.getShopConfig().getData().tax;
        boolean hasTax = taxConfig.enabled;
        double taxPercent = (mode == Mode.BUY) ? taxConfig.buyTaxPercent : taxConfig.sellTaxPercent;
        int taxPerUnit = hasTax ? (int) Math.ceil(unitPrice * taxPercent / 100.0) : 0;

        int maxQty = getConfirmMaxQuantity();
        if (confirmQuantity > maxQty) confirmQuantity = maxQty;
        if (confirmQuantity < 1) confirmQuantity = 1;

        int subtotal = unitPrice * confirmQuantity;
        int totalTax = taxPerUnit * confirmQuantity;
        int grandTotal = subtotal + totalTax;

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

        // Tax display
        if (hasTax && taxPerUnit > 0) {
            ui.set("#ConfirmTax.Visible", true);
            ui.set("#ConfirmTax.Text",
                i18n.get(playerRef, "shop.browse.confirm.tax", totalTax,
                    String.format("%.1f", taxPercent) + "%"));
        } else {
            ui.set("#ConfirmTax.Visible", false);
        }

        // Quantity selector
        ui.set("#ConfirmQty.Text", String.valueOf(confirmQuantity));
        ui.set("#ConfirmMinus.Visible", confirmQuantity > 1);
        ui.set("#ConfirmPlus.Visible", confirmQuantity < maxQty);

        // Total
        if (hasTax && totalTax > 0) {
            ui.set("#ConfirmTotal.Text",
                i18n.get(playerRef, "shop.browse.confirm.total_with_tax", grandTotal, totalTax));
        } else {
            ui.set("#ConfirmTotal.Text",
                i18n.get(playerRef, "shop.browse.confirm.total", grandTotal));
        }

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
        List<ShopItem> activeItems = (mode == Mode.SELL) ? sellItems : buyItems;
        if (activeItems == null || confirmSlotIndex < 0 || confirmSlotIndex >= activeItems.size()) return 1;

        ShopItem item = activeItems.get(confirmSlotIndex);

        if (mode == Mode.BUY) {
            // Limited by stock (if not unlimited) and by player balance
            int max = item.isUnlimitedStock() ? 64 : item.getStock();

            ShopConfig.Tax taxConfig = plugin.getShopConfig().getData().tax;
            int price = item.getBuyPrice();
            int tax = taxConfig.enabled ? (int) Math.ceil(price * taxConfig.buyTaxPercent / 100.0) : 0;
            int costPerUnit = price + tax;

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
     * "Ingredient Bar Gold" by replacing underscores with spaces.
     */
    static String formatItemName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "";
        return itemId.replace('_', ' ');
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
            .addField(new KeyedCodec<>("Buy", Codec.STRING),
                (data, value) -> data.buy = value,
                data -> data.buy)
            .addField(new KeyedCodec<>("Tab", Codec.STRING),
                (data, value) -> data.tab = value,
                data -> data.tab)
            .addField(new KeyedCodec<>("Confirm", Codec.STRING),
                (data, value) -> data.confirm = value,
                data -> data.confirm)
            .addField(new KeyedCodec<>("Rate", Codec.STRING),
                (data, value) -> data.rate = value,
                data -> data.rate)
            .build();

        private String button;
        private String buy;
        private String tab;
        private String confirm;
        private String rate;

        public ShopBrowseData() {}
    }
}
