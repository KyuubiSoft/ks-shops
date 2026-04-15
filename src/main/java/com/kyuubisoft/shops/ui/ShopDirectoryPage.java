package com.kyuubisoft.shops.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;

import org.bson.BsonDocument;

import com.hypixel.hytale.server.core.Message;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopItem;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.DirectoryService;
import com.kyuubisoft.shops.service.ShopService;
import com.kyuubisoft.shops.util.BsonMetadataCodec;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Shop Directory page -- the main hub where players browse ALL shops on the server.
 *
 * Displays a 4x2 card grid (8 shops per page) with tabs (All/Admin/Player/Featured),
 * search field, category/rating/sort filters, and pagination.
 *
 * Clicking a card opens the ShopBrowsePage for that shop.
 *
 * Usage:
 * <pre>
 *   ShopDirectoryPage page = new ShopDirectoryPage(playerRef, player, plugin);
 *   player.getPageManager().openCustomPage(ref, store, page);
 * </pre>
 */
public class ShopDirectoryPage extends InteractiveCustomUIPage<ShopDirectoryPage.DirData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    static final int CARDS_PER_PAGE = 8;
    /** Items mode uses a native ItemGrid sized 9x5, matching ShopBrowsePage. */
    static final int ITEMS_PER_PAGE = 45;

    // Sort options matching the dropdown indices
    private static final String[] SORT_OPTIONS = {
        "rating", "newest", "name", "most_sales", "price_low", "price_high"
    };

    // Category filter values matching the dropdown indices (index 0 = all)
    private static final String[] CATEGORY_OPTIONS = {
        "", "weapons", "armor", "tools", "resources", "potions", "food", "building", "misc"
    };

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;

    // Stored from build()/handleDataEvent() for opening sub-pages
    private Ref<EntityStore> lastRef;
    private Store<EntityStore> lastStore;

    private int currentPage = 0;
    private String currentTab = "all";       // "all", "admin", "player", "featured"
    private String currentSort = "rating";
    private String currentCategory = "";     // empty = all categories
    private int currentRatingFilter = 0;     // 0 = any, 1-4 = minimum stars
    private String searchQuery = "";
    private String searchMode = "shops";     // "shops" or "items" - top-level search mode

    private List<ShopData> filteredShops = new ArrayList<>();
    private List<ItemSearchResult> itemResults = new ArrayList<>();
    private int totalResults = 0;

    // In-place buy confirm overlay state (items-mode fast-buy dialog)
    private boolean buyConfirmActive = false;
    private ShopData buyConfirmShop = null;
    private ShopItem buyConfirmItem = null;
    private int buyConfirmQuantity = 1;

    private final UUID onlyOwnerUuid; // set via the /ksshop myshops entry point

    public ShopDirectoryPage(PlayerRef playerRef, Player player, ShopPlugin plugin) {
        this(playerRef, player, plugin, null);
    }

    /**
     * When {@code onlyOwnerUuid} is non-null, the directory is locked to showing
     * only shops owned by that player (used by /ksshop myshops). Tab switching
     * is suppressed and the title changes to "YOUR SHOPS".
     */
    public ShopDirectoryPage(PlayerRef playerRef, Player player, ShopPlugin plugin, UUID onlyOwnerUuid) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, DirData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.onlyOwnerUuid = onlyOwnerUuid;
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        this.lastRef = ref;
        this.lastStore = store;

        ui.append("Pages/Shop/ShopDirectory.ui");

        bindAllEvents(events);
        executeQuery();
        buildUI(ui);
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull DirData data) {
        super.handleDataEvent(ref, store, data);
        this.lastRef = ref;
        this.lastStore = store;

        if (data.mode != null) {
            handleModeSwitch(data.mode);
            return;
        }

        if (data.tab != null) {
            handleTabSwitch(data.tab);
            return;
        }

        if (data.card != null) {
            handleCardClick(data.card);
            return;
        }

        if (data.itemSlot != null) {
            handleItemSlotClick(data.itemSlot);
            return;
        }

        if (data.buyAction != null) {
            handleBuyAction(data.buyAction);
            return;
        }

        if (data.buyQty != null) {
            handleBuyQty(data.buyQty);
            return;
        }

        if (data.button != null) {
            handleButton(data.button);
            return;
        }

        if (data.search != null) {
            handleSearch(data.search);
            return;
        }

        if (data.sort != null) {
            handleSort(data.sort);
            return;
        }

        if (data.catFilter != null) {
            handleCategoryFilter(data.catFilter);
            return;
        }

        if (data.ratFilter != null) {
            handleRatingFilter(data.ratFilter);
            return;
        }

        // Catch-all: send empty response to prevent permanent "Loading..." state
        this.sendUpdate(new UICommandBuilder(), false);
    }

    // ==================== MODE SWITCHING ====================

    private void handleModeSwitch(String mode) {
        if (mode == null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        if (mode.equals(searchMode)) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        // Only accept the two known modes
        if (!"shops".equals(mode) && !"items".equals(mode)) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        searchMode = mode;
        currentPage = 0;
        executeQuery();
        refreshUI();
    }

    // ==================== TAB SWITCHING ====================

    private void handleTabSwitch(String tab) {
        // In myshops mode, tab switching is a no-op: we only ever show the
        // caller's own shops.
        if (onlyOwnerUuid != null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        if (tab.equals(currentTab)) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        currentTab = tab;
        currentPage = 0;
        executeQuery();
        refreshUI();
    }

    // ==================== SEARCH ====================

    private void handleSearch(String query) {
        searchQuery = (query != null) ? query.trim() : "";
        currentPage = 0;
        executeQuery();
        refreshUI();
    }

    // ==================== CARD CLICK ====================

    private void handleCardClick(String cardIndexStr) {
        try {
            int cardIndex = Integer.parseInt(cardIndexStr);
            int actualIndex = currentPage * CARDS_PER_PAGE + cardIndex;

            if (actualIndex >= 0 && actualIndex < filteredShops.size()) {
                ShopData shop = filteredShops.get(actualIndex);

                if (lastRef == null || lastStore == null) {
                    LOGGER.warning("[ShopDirectory] Cannot open shop: ref/store not available");
                    this.sendUpdate(new UICommandBuilder(), false);
                    return;
                }

                // Open the shop browse page (replaces current page)
                try {
                    ShopBrowsePage browsePage = new ShopBrowsePage(playerRef, player, plugin, shop);
                    player.getPageManager().openCustomPage(lastRef, lastStore, browsePage);
                } catch (Exception e) {
                    LOGGER.warning("[ShopDirectory] Failed to open shop browse page: " + e.getMessage());
                    this.sendUpdate(new UICommandBuilder(), false);
                }
            } else {
                this.sendUpdate(new UICommandBuilder(), false);
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("[ShopDirectory] Invalid card index: " + cardIndexStr);
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== ITEM SLOT CLICK ====================

    /**
     * A single click on a slot in {@code #ItemSearchGrid} opens the in-place
     * buy-confirm overlay prefilled with the clicked item. From the overlay
     * the user can BUY (instant purchase), VISIT SHOP (navigate to
     * {@link ShopBrowsePage}), or CANCEL.
     */
    private void handleItemSlotClick(Integer slotIndex) {
        if (slotIndex == null || slotIndex < 0 || slotIndex >= itemResults.size()) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        ItemSearchResult r = itemResults.get(slotIndex);
        if (r == null || r.shop == null || r.item == null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        buyConfirmActive = true;
        buyConfirmShop = r.shop;
        buyConfirmItem = r.item;
        buyConfirmQuantity = 1;
        refreshUI();
    }

    // ==================== BUY OVERLAY ACTIONS ====================

    /**
     * Handles the buttons on the in-place buy-confirm overlay:
     * {@code yes} (purchase), {@code visit} (open {@link ShopBrowsePage}),
     * {@code cancel} (close overlay), {@code plus}/{@code minus} (quantity).
     */
    private void handleBuyAction(String action) {
        if (action == null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        switch (action) {
            case "yes" -> {
                if (!buyConfirmActive || buyConfirmShop == null || buyConfirmItem == null) {
                    clearBuyConfirm();
                    refreshUI();
                    return;
                }
                ShopI18n i18n = plugin.getI18n();
                ShopService.PurchaseResult result = plugin.getShopService()
                    .purchaseItemWithReason(playerRef, buyConfirmShop.getId(),
                        buyConfirmItem.getSlot(), buyConfirmQuantity);
                if (result.isSuccess()) {
                    int totalPaid = buyConfirmItem.getBuyPrice() * buyConfirmQuantity;
                    String currencyName = plugin.getEconomyBridge().getCurrencyName();
                    player.sendMessage(Message.raw(
                        i18n.get(playerRef, "shop.buy.success",
                            buyConfirmQuantity,
                            ShopBrowsePage.formatItemName(buyConfirmItem.getItemId()),
                            totalPaid,
                            currencyName))
                        .color("#44FF44"));
                } else {
                    String key = result.getErrorKey() != null
                        ? result.getErrorKey()
                        : "shop.browse.purchase_failed";
                    player.sendMessage(Message.raw(
                        i18n.get(playerRef, key)).color("#FF5555"));
                }
                clearBuyConfirm();
                executeQuery();
                refreshUI();
            }
            case "visit" -> {
                ShopData shop = buyConfirmShop;
                clearBuyConfirm();
                if (shop == null || lastRef == null || lastStore == null) {
                    LOGGER.warning("[ShopDirectory] Cannot visit shop: no active buy target");
                    refreshUI();
                    return;
                }
                try {
                    ShopBrowsePage browsePage = new ShopBrowsePage(playerRef, player, plugin, shop);
                    player.getPageManager().openCustomPage(lastRef, lastStore, browsePage);
                } catch (Exception e) {
                    LOGGER.warning("[ShopDirectory] Failed to open shop browse page: " + e.getMessage());
                    refreshUI();
                }
            }
            case "cancel" -> {
                clearBuyConfirm();
                refreshUI();
            }
            default -> this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    /**
     * Slider-driven quantity update for the buy-confirm overlay. Clamps to
     * [1, max] where max = {@link #getBuyConfirmMaxQuantity()}; the static
     * slider range (1..64) may exceed the real stock, so we coerce silently
     * instead of rejecting the event.
     */
    private void handleBuyQty(int qty) {
        if (!buyConfirmActive || buyConfirmItem == null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        int max = getBuyConfirmMaxQuantity();
        if (qty < 1) qty = 1;
        if (qty > max) qty = max;
        if (qty == buyConfirmQuantity) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        buyConfirmQuantity = qty;
        refreshUI();
    }

    private void clearBuyConfirm() {
        buyConfirmActive = false;
        buyConfirmShop = null;
        buyConfirmItem = null;
        buyConfirmQuantity = 1;
    }

    private int getBuyConfirmMaxQuantity() {
        if (buyConfirmItem == null) return 1;
        if (buyConfirmItem.isUnlimitedStock()) return 64;
        return Math.max(1, buyConfirmItem.getStock());
    }

    // ==================== PAGINATION ====================

    private void handleButton(String button) {
        switch (button) {
            case "prev" -> {
                if (currentPage > 0) {
                    currentPage--;
                    executeQuery();
                    refreshUI();
                } else {
                    this.sendUpdate(new UICommandBuilder(), false);
                }
            }
            case "next" -> {
                int maxPage = getMaxPage();
                if (currentPage < maxPage) {
                    currentPage++;
                    executeQuery();
                    refreshUI();
                } else {
                    this.sendUpdate(new UICommandBuilder(), false);
                }
            }
            default -> this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== FILTER HANDLERS ====================

    private void handleSort(String sortIndexStr) {
        try {
            int idx = Integer.parseInt(sortIndexStr);
            if (idx >= 0 && idx < SORT_OPTIONS.length) {
                currentSort = SORT_OPTIONS[idx];
            }
        } catch (NumberFormatException ignored) {
            // If not a numeric index, try as a sort key directly
            currentSort = sortIndexStr;
        }
        currentPage = 0;
        executeQuery();
        refreshUI();
    }

    private void handleCategoryFilter(String catIndexStr) {
        try {
            int idx = Integer.parseInt(catIndexStr);
            if (idx >= 0 && idx < CATEGORY_OPTIONS.length) {
                currentCategory = CATEGORY_OPTIONS[idx];
            }
        } catch (NumberFormatException ignored) {
            currentCategory = catIndexStr;
        }
        currentPage = 0;
        executeQuery();
        refreshUI();
    }

    private void handleRatingFilter(String ratIndexStr) {
        try {
            currentRatingFilter = Integer.parseInt(ratIndexStr);
        } catch (NumberFormatException ignored) {
            currentRatingFilter = 0;
        }
        currentPage = 0;
        executeQuery();
        refreshUI();
    }

    // ==================== QUERY EXECUTION ====================

    private void executeQuery() {
        if ("items".equals(searchMode)) {
            executeItemQuery();
        } else {
            executeShopQuery();
        }
    }

    private void executeShopQuery() {
        // /ksshop myshops entry point: bypass DirectoryService entirely and return
        // just the shops owned by the requesting player. Applies the same category
        // and rating filters as the main directory so the filter bar still works.
        if (onlyOwnerUuid != null) {
            List<ShopData> owned = plugin.getShopManager().getShopsByOwner(onlyOwnerUuid);
            String needle = searchQuery.isEmpty() ? null : searchQuery.toLowerCase();
            List<ShopData> matching = new ArrayList<>();
            for (ShopData s : owned) {
                if (!currentCategory.isEmpty() && !currentCategory.equalsIgnoreCase(s.getCategory())) continue;
                if (currentRatingFilter > 0 && s.getAverageRating() < currentRatingFilter) continue;
                if (needle != null) {
                    String name = s.getName() != null ? s.getName().toLowerCase() : "";
                    if (!name.contains(needle)) continue;
                }
                matching.add(s);
            }
            totalResults = matching.size();
            int start = Math.min(currentPage * CARDS_PER_PAGE, matching.size());
            int end = Math.min(start + CARDS_PER_PAGE, matching.size());
            filteredShops = new ArrayList<>(matching.subList(start, end));
            return;
        }

        DirectoryService directory = plugin.getDirectoryService();

        String query = searchQuery.isEmpty() ? null : searchQuery;
        String category = currentCategory.isEmpty() ? null : currentCategory;

        // Resolve type filter from tab
        String typeFilter = switch (currentTab) {
            case "admin" -> "admin";
            case "player" -> "player";
            case "featured" -> "featured";
            default -> null;
        };

        filteredShops = directory.searchShopsFiltered(
            query, category, typeFilter, currentSort, currentRatingFilter,
            currentPage, CARDS_PER_PAGE
        );

        totalResults = directory.countShopsFiltered(
            query, category, typeFilter, currentRatingFilter
        );
    }

    /**
     * Items-mode query: flatten every open shop's item list, keep only the
     * entries whose buy side is enabled (and priced) and whose item id matches
     * the search needle, then apply the existing rating filter. Paginates the
     * result to fit the 12-card item grid.
     */
    private void executeItemQuery() {
        String needle = searchQuery.isEmpty() ? null : searchQuery.toLowerCase();
        List<ItemSearchResult> matches = new ArrayList<>();

        for (ShopData shop : plugin.getShopManager().getAllShops()) {
            if (!shop.isOpen()) continue;
            // In /ksshop myshops mode the directory is locked to one owner; do
            // the same filtering for the items view so the two modes stay
            // consistent.
            if (onlyOwnerUuid != null && !onlyOwnerUuid.equals(shop.getOwnerUuid())) continue;
            if (currentRatingFilter > 0 && shop.getAverageRating() < currentRatingFilter) continue;

            List<ShopItem> items = shop.getItems();
            if (items == null) continue;

            for (ShopItem item : items) {
                if (item == null) continue;
                if (!item.isBuyEnabled()) continue;       // only buyable from customer POV
                if (item.getBuyPrice() <= 0) continue;    // ignore free/placeholder entries
                if (item.getItemId() == null) continue;

                if (needle != null) {
                    String itemIdLower = item.getItemId().toLowerCase();
                    if (!itemIdLower.contains(needle)) continue;
                }

                matches.add(new ItemSearchResult(shop, item));
            }
        }

        // Sort cheapest first by default. The existing sort dropdown targets
        // shop-level keys which do not apply cleanly to items, so keep a fixed
        // price-ascending order for the items mode.
        matches.sort(Comparator.comparingInt(r -> r.item.getBuyPrice()));

        totalResults = matches.size();
        int start = Math.min(currentPage * ITEMS_PER_PAGE, matches.size());
        int end = Math.min(start + ITEMS_PER_PAGE, matches.size());
        itemResults = new ArrayList<>(matches.subList(start, end));
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
        // Mode switch buttons (shops vs items)
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeBar #ModeShops",
            EventData.of("Mode", "shops"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeBar #ModeItems",
            EventData.of("Mode", "items"), false);

        // Tab buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabAll",
            EventData.of("Tab", "all"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabAdmin",
            EventData.of("Tab", "admin"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabPlayer",
            EventData.of("Tab", "player"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabFeatured",
            EventData.of("Tab", "featured"), false);

        // Shop card clicks (shop mode grid) — flat unique IDs per card
        for (int i = 0; i < CARDS_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                "#DBtn" + i,
                EventData.of("Card", String.valueOf(i)), false);
        }

        // Item search grid click — native ItemGrid SlotClicking.
        // SlotIndex is auto-populated by Hytale into DirData.itemSlot via the
        // new KeyedCodec<>("SlotIndex", Codec.INTEGER) field on the codec.
        events.addEventBinding(CustomUIEventBindingType.SlotClicking,
            "#ItemSearchGrid",
            EventData.of("ItemSlot", ""), true);

        // Buy confirm overlay buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DirConfirmBuy",
            EventData.of("BuyAction", "yes"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DirConfirmVisit",
            EventData.of("BuyAction", "visit"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DirConfirmCancel",
            EventData.of("BuyAction", "cancel"), false);
        // Quantity slider (replaces the old +/- buttons)
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#DirConfirmQtySlider",
            EventData.of("@BuyQty", "#DirConfirmQtySlider.Value"), false);

        // Pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Footer #DPrevBtn",
            EventData.of("Button", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Footer #DNextBtn",
            EventData.of("Button", "next"), false);

        // Search field (ValueChanged)
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#FilterBar #SearchField",
            EventData.of("@Search", "#FilterBar #SearchField.Value"));

        // Dropdowns (ValueChanged)
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TabBar #SortDropdown",
            EventData.of("Sort", ""), true);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#FilterBar #CategoryFilter",
            EventData.of("CatFilter", ""), true);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#FilterBar #RatingFilter",
            EventData.of("RatFilter", ""), true);
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();

        // ---- Title ----
        // In myshops mode the title changes to "YOUR SHOPS" and the tab bar is
        // locked on the "all" (but pre-filtered) state.
        if (onlyOwnerUuid != null) {
            ui.set("#DirTitle.Text", i18n.get(playerRef, "shop.directory.title.myshops"));
        } else {
            ui.set("#DirTitle.Text", i18n.get(playerRef, "shop.directory.title"));
        }

        // ---- Mode highlights (SHOPS vs ITEMS) ----
        buildModeHighlights(ui);

        // ---- Mode labels (localized) ----
        ui.set("#ModeBar #ModeShopsLabel.Text", i18n.get(playerRef, "shop.directory.mode.shops"));
        ui.set("#ModeBar #ModeItemsLabel.Text", i18n.get(playerRef, "shop.directory.mode.items"));

        // ---- Filter / search state ----
        // Reflect the current Java filter state in the UI widgets so users always see
        // which sort / category / rating filter is active (even on first open).
        ui.set("#TabBar #SortDropdown.Value", currentSort);
        ui.set("#FilterBar #CategoryFilter.Value", currentCategory);
        ui.set("#FilterBar #RatingFilter.Value", String.valueOf(currentRatingFilter));
        ui.set("#FilterBar #SearchField.Value", searchQuery);
        String placeholderKey = "items".equals(searchMode)
            ? "shop.directory.search.placeholder_items"
            : "shop.directory.search.placeholder";
        ui.set("#FilterBar #SearchField.PlaceholderText", i18n.get(playerRef, placeholderKey));

        // ---- Tab labels (localized) ----
        ui.set("#TabBar #TabAllLabel.Text", i18n.get(playerRef, "shop.directory.tab.all"));
        ui.set("#TabBar #TabAdminLabel.Text", i18n.get(playerRef, "shop.directory.tab.admin"));
        ui.set("#TabBar #TabPlayerLabel.Text", i18n.get(playerRef, "shop.directory.tab.player"));
        ui.set("#TabBar #TabFeaturedLabel.Text", i18n.get(playerRef, "shop.directory.tab.featured"));

        // ---- Tab highlights ----
        buildTabHighlights(ui);

        // ---- Cards ----
        buildCards(ui, i18n);

        // ---- Pagination ----
        buildPagination(ui, i18n);

        // ---- Buy confirm overlay (items tab fast-buy) ----
        buildBuyConfirmOverlay(ui, i18n);
    }

    /**
     * Drives the in-place buy-confirm overlay visibility + content. Hides the
     * overlay when {@link #buyConfirmActive} is false. All info lines are
     * colored via the Label styles baked into ShopDirectory.ui - this method
     * only pushes the text content.
     */
    private void buildBuyConfirmOverlay(UICommandBuilder ui, ShopI18n i18n) {
        ui.set("#DirConfirmOverlay.Visible", buyConfirmActive);
        if (!buyConfirmActive || buyConfirmShop == null || buyConfirmItem == null) return;

        ShopData shop = buyConfirmShop;
        ShopItem item = buyConfirmItem;
        int unitPrice = item.getBuyPrice();

        int maxQty = getBuyConfirmMaxQuantity();
        if (buyConfirmQuantity > maxQty) buyConfirmQuantity = maxQty;
        if (buyConfirmQuantity < 1) buyConfirmQuantity = 1;
        int grandTotal = unitPrice * buyConfirmQuantity;

        ui.set("#DirConfirmTitle.Text", i18n.get(playerRef, "shop.directory.buy.title"));

        // Item icon
        ui.set("#DirConfirmIcon.ItemId", item.getItemId());

        // Every coloured line below uses Hytale's <color is="#RRGGBB">...
        // </color> markup because Label.Style.TextColor cannot be set
        // dynamically from Java (verified pitfall). Same markup format
        // DynamicTooltipsLib / weapon-mastery / item-control providers use.
        ui.set("#DirConfirmItemName.Text",
            "<color is=\"#ffffff\">"
                + ShopBrowsePage.formatItemName(item.getItemId())
                + "</color>");
        ui.set("#DirConfirmShop.Text",
            "<color is=\"#4fc3f7\">"
                + i18n.get(playerRef, "shop.directory.tooltip.shop",
                    shop.getName() != null ? shop.getName() : "Shop")
                + "</color>");
        if (shop.isAdminShop()) {
            ui.set("#DirConfirmOwner.Text",
                "<color is=\"#ce93d8\">"
                    + i18n.get(playerRef, "shop.directory.admin_shop")
                    + "</color>");
        } else if (shop.getOwnerName() != null) {
            ui.set("#DirConfirmOwner.Text",
                "<color is=\"#9fa8da\">"
                    + i18n.get(playerRef, "shop.directory.tooltip.owner",
                        shop.getOwnerName())
                    + "</color>");
        } else {
            ui.set("#DirConfirmOwner.Text", "");
        }

        // Price per unit (warm amber so it stands apart from the gold total
        // at the bottom of the dialog).
        ui.set("#DirConfirmPrice.Text",
            "<color is=\"#ffb74d\">"
                + i18n.get(playerRef, "shop.directory.buy.price_each", unitPrice)
                + "</color>");

        // Stock: dynamic color based on remaining quantity.
        //   unlimited -> cyan  (#26c6da)
        //   > 10      -> green (#66bb6a)
        //   3-10      -> amber (#ffb74d)
        //   1-2       -> red   (#ff5252)
        String stockText;
        String stockColor;
        if (item.isUnlimitedStock()) {
            stockText = i18n.get(playerRef, "shop.directory.buy.stock_unlimited");
            stockColor = "#26c6da";
        } else {
            int stock = item.getStock();
            stockText = i18n.get(playerRef, "shop.directory.buy.stock", stock);
            if (stock <= 2) stockColor = "#ff5252";
            else if (stock <= 10) stockColor = "#ffb74d";
            else stockColor = "#66bb6a";
        }
        ui.set("#DirConfirmStock.Text",
            "<color is=\"" + stockColor + "\">" + stockText + "</color>");

        // Quantity slider + big label (replaces the old +/- buttons). Slider
        // Min/Max are 1..64 static; Java clamps the real purchase to current
        // stock in handleBuyQty.
        ui.set("#DirConfirmQtySlider.Value", buyConfirmQuantity);
        ui.set("#DirConfirmQty.Text", String.valueOf(buyConfirmQuantity));

        // Total (gold)
        ui.set("#DirConfirmTotal.Text",
            "<color is=\"#ffd700\">"
                + i18n.get(playerRef, "shop.directory.buy.total", grandTotal)
                + "</color>");

        // Button labels
        ui.set("#DirConfirmBuy.Text", i18n.get(playerRef, "shop.directory.buy.confirm"));
        ui.set("#DirConfirmVisit.Text", i18n.get(playerRef, "shop.directory.buy.visit"));
        ui.set("#DirConfirmCancel.Text", i18n.get(playerRef, "shop.directory.buy.cancel"));
    }

    private void buildModeHighlights(UICommandBuilder ui) {
        // Text color swap is not possible at runtime (Style.TextColor is
        // not dynamically settable). The indicator bar below each label
        // communicates the active state visually.
        boolean shopsActive = !"items".equals(searchMode);
        boolean itemsActive = "items".equals(searchMode);
        ui.set("#ModeBar #ModeShopsInd.Visible", shopsActive);
        ui.set("#ModeBar #ModeItemsInd.Visible", itemsActive);
    }

    private void buildTabHighlights(UICommandBuilder ui) {
        // Text color swap is not possible at runtime (Style.TextColor is
        // not dynamically settable). The indicator bar under each tab
        // communicates the active state visually.
        ui.set("#TabBar #TabAllInd.Visible", "all".equals(currentTab));
        ui.set("#TabBar #TabAdminInd.Visible", "admin".equals(currentTab));
        ui.set("#TabBar #TabPlayerInd.Visible", "player".equals(currentTab));
        ui.set("#TabBar #TabFeaturedInd.Visible", "featured".equals(currentTab));
    }

    private void buildCards(UICommandBuilder ui, ShopI18n i18n) {
        if ("items".equals(searchMode)) {
            ui.set("#ShopGrid.Visible", false);
            ui.set("#ItemGrid.Visible", true);
            populateItemSearchGrid(ui, i18n);
        } else {
            ui.set("#ShopGrid.Visible", true);
            ui.set("#ItemGrid.Visible", false);
            buildShopCards(ui, i18n);
        }
    }

    private void buildShopCards(UICommandBuilder ui, ShopI18n i18n) {
        // Player position for distance calculation
        double playerX = 0;
        double playerZ = 0;
        try {
            TransformComponent tc = player.getTransformComponent();
            if (tc != null) {
                Vector3d pos = tc.getPosition();
                if (pos != null) {
                    playerX = pos.x;
                    playerZ = pos.z;
                }
            }
        } catch (Exception ignored) {
            // Position may not be available in all contexts
        }

        int startIndex = currentPage * CARDS_PER_PAGE;

        for (int i = 0; i < CARDS_PER_PAGE; i++) {
            int actualIndex = startIndex + i;

            if (actualIndex < filteredShops.size()) {
                ShopData shop = filteredShops.get(actualIndex);
                ui.set("#DCard" + i + ".Visible", true);

                // Avatar icon — flat 1-level selector on the uniquely-named ItemIcon.
                // Macro-instanced children silently fail to receive ItemId updates
                // even with 2-level selectors, so each card has its own #DAvatarN.
                // Falls back to a gold bar for shops with no chosen icon / no items,
                // because an empty #DAvatar renders as a blank square.
                String iconId = shop.getDisplayIconItemId();
                if (iconId == null || iconId.isBlank()) {
                    iconId = "Ingredient_Bar_Gold";
                }
                ui.set("#DAvatar" + i + ".ItemId", iconId);

                // Shop name
                String name = shop.getName() != null ? shop.getName() : "Shop";
                ui.set("#DName" + i + ".Text", name);

                // Owner name
                if (shop.isPlayerShop() && shop.getOwnerName() != null) {
                    ui.set("#DOwner" + i + ".Text",
                        i18n.get(playerRef, "shop.directory.by", shop.getOwnerName()));
                } else if (shop.isAdminShop()) {
                    ui.set("#DOwner" + i + ".Text",
                        i18n.get(playerRef, "shop.directory.admin_shop"));
                } else {
                    ui.set("#DOwner" + i + ".Text", "");
                }

                // Rating display
                double avgRating = shop.getAverageRating();
                int totalRatings = shop.getTotalRatings();
                if (totalRatings > 0) {
                    String stars = buildStarString(avgRating);
                    ui.set("#DRating" + i + ".Text",
                        stars + " (" + String.format("%.1f", avgRating) + ")");
                } else {
                    ui.set("#DRating" + i + ".Text",
                        i18n.get(playerRef, "shop.directory.no_ratings"));
                }

                // Item count
                int itemCount = (shop.getItems() != null) ? shop.getItems().size() : 0;
                ui.set("#DItems" + i + ".Text",
                    i18n.get(playerRef, "shop.directory.item_count", itemCount));

                // Distance from player
                double dx = shop.getPosX() - playerX;
                double dz = shop.getPosZ() - playerZ;
                double distance = Math.sqrt(dx * dx + dz * dz);
                ui.set("#DDistance" + i + ".Text", formatDistance(distance));

                // Status badge — dynamic color via <color> markup because
                // Label.Style.TextColor is not runtime-settable.
                String statusKey;
                String statusColor;
                if (shop.isFeatured() && shop.getFeaturedUntil() > System.currentTimeMillis()) {
                    statusKey = "shop.directory.status.featured";
                    statusColor = "#ffd700";
                } else if (shop.isOpen()) {
                    statusKey = "shop.directory.status.open";
                    statusColor = "#4caf50";
                } else {
                    statusKey = "shop.directory.status.closed";
                    statusColor = "#cc4444";
                }
                ui.set("#DStatus" + i + ".Text",
                    "<color is=\"" + statusColor + "\">"
                        + i18n.get(playerRef, statusKey)
                        + "</color>");

                // Category
                if (shop.getCategory() != null && !shop.getCategory().isEmpty()) {
                    ui.set("#DCategory" + i + ".Text", shop.getCategory());
                } else {
                    ui.set("#DCategory" + i + ".Text", "");
                }

                // Tooltip with description
                if (shop.getDescription() != null && !shop.getDescription().isEmpty()) {
                    ui.set("#DBtn" + i + ".TooltipText", shop.getDescription());
                } else {
                    ui.set("#DBtn" + i + ".TooltipText",
                        i18n.get(playerRef, "shop.directory.click_to_browse"));
                }
            } else {
                ui.set("#DCard" + i + ".Visible", false);
            }
        }
    }

    /**
     * Populates the native {@code #ItemSearchGrid} (9x5 = 45 slots) with the
     * current page of {@link #itemResults}. Each slot carries the full item
     * metadata so Hytale's native tooltip + DTT render enchantments, stats,
     * etc. on hover. Stock is shown as the native slot-quantity badge.
     *
     * Each slot also overrides {@link ItemGridSlot#setName(String)} and
     * {@link ItemGridSlot#setDescription(String)} so the hover tooltip shows
     * the selling shop + owner + price + stock — without that override the
     * native tooltip only shows the bare item name and the user has no way
     * to tell which shop offers the item.
     *
     * Clicking a slot fires a {@code SlotClicking} event with the slot index,
     * which routes to {@link #handleItemSlotClick(Integer)} and opens the
     * shop that sells the clicked item.
     *
     * PITFALL #121: {@code openCustomPage()} never auto-populates an ItemGrid;
     * slots MUST be set via {@code ui.set("#ItemSearchGrid.Slots", List)} and
     * every slot MUST be {@code setActivatable(true)} for SlotClicking to fire.
     */
    private void populateItemSearchGrid(UICommandBuilder ui, ShopI18n i18n) {
        // Grid flags: display-only (no drag), show stock as the slot quantity.
        ui.set("#ItemSearchGrid.AreItemsDraggable", false);
        ui.set("#ItemSearchGrid.DisplayItemQuantity", true);

        List<ItemGridSlot> slots = new ArrayList<>(ITEMS_PER_PAGE);
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            ItemGridSlot slot;
            if (i < itemResults.size()) {
                ItemSearchResult r = itemResults.get(i);
                ShopData shop = r.shop;
                ShopItem item = r.item;
                String itemId = item.getItemId();
                int quantityToShow = item.isUnlimitedStock()
                    ? 1
                    : Math.max(1, item.getStock());

                try {
                    BsonDocument meta = BsonMetadataCodec.decode(item.getItemMetadata());
                    ItemStack stack = (meta != null)
                        ? new ItemStack(itemId, quantityToShow, meta)
                        : new ItemStack(itemId, quantityToShow);
                    slot = new ItemGridSlot(stack);
                } catch (Exception e) {
                    LOGGER.warning("[ShopDirectory] Failed to build search slot for "
                        + itemId + ": " + e.getMessage());
                    slot = new ItemGridSlot();
                }

                // Override tooltip name + description so the user can see at
                // a glance WHICH SHOP sells this item. The item's native
                // tooltip (enchantments, custom stats, DTT extras) is still
                // rendered by the client; only the name/description lines
                // are overridden by the slot-level setName/setDescription
                // calls below.
                //
                // Uses Hytale's <color is="#RRGGBB">...</color> markup for
                // per-line coloring in the DESCRIPTION only - setName does
                // not parse the markup (it renders the tags literally), so
                // the name stays plain text. Markup format verified from
                // DynamicTooltipsLib, weapon-mastery/MasteryTooltipBuilder,
                // item-control SoulboundTooltipProvider, and claims map.
                slot.setName(ShopBrowsePage.formatItemName(itemId));

                StringBuilder desc = new StringBuilder();
                String shopName = shop.getName() != null ? shop.getName() : "Shop";
                desc.append("<color is=\"#4fc3f7\">Shop: ")
                    .append(shopName).append("</color>\n");

                if (shop.isAdminShop()) {
                    desc.append("<color is=\"#ce93d8\">Admin Shop</color>");
                } else if (shop.getOwnerName() != null) {
                    desc.append("<color is=\"#9fa8da\">by ")
                        .append(shop.getOwnerName()).append("</color>");
                }
                desc.append('\n');

                desc.append("<color is=\"#ffd700\">Price: ")
                    .append(item.getBuyPrice()).append(" Gold</color>\n");

                if (item.isUnlimitedStock()) {
                    desc.append("<color is=\"#26c6da\">Stock: Unlimited</color>");
                } else {
                    int s = item.getStock();
                    String stockColor;
                    if (s <= 2) stockColor = "#ff5252";
                    else if (s <= 5) stockColor = "#ffb74d";
                    else stockColor = "#66bb6a";
                    desc.append("<color is=\"").append(stockColor)
                        .append("\">Stock: ").append(s).append("</color>");
                }
                desc.append('\n');
                desc.append("<color is=\"#888888\">")
                    .append(i18n.get(playerRef, "shop.directory.tooltip.click_hint"))
                    .append("</color>");

                slot.setDescription(desc.toString());
            } else {
                slot = new ItemGridSlot();
            }
            slot.setActivatable(true);
            slots.add(slot);
        }

        ui.set("#ItemSearchGrid.Slots", slots);
    }

    private void buildPagination(UICommandBuilder ui, ShopI18n i18n) {
        int perPage = "items".equals(searchMode) ? ITEMS_PER_PAGE : CARDS_PER_PAGE;
        int totalPages = Math.max(1, (int) Math.ceil(totalResults / (double) perPage));

        // Clamp page
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        ui.set("#Footer #DPageInfo.Text",
            i18n.get(playerRef, "shop.directory.page", currentPage + 1, totalPages));
        ui.set("#Footer #DPrevBtn.Visible", currentPage > 0);
        ui.set("#Footer #DNextBtn.Visible", currentPage < totalPages - 1);

        // Result count
        ui.set("#Footer #DResultCount.Text",
            i18n.get(playerRef, "shop.directory.result_count", totalResults));
    }

    // ==================== HELPERS ====================

    private int getMaxPage() {
        int perPage = "items".equals(searchMode) ? ITEMS_PER_PAGE : CARDS_PER_PAGE;
        int totalPages = Math.max(1, (int) Math.ceil(totalResults / (double) perPage));
        return totalPages - 1;
    }

    /**
     * Builds a star string representation (filled/empty stars).
     * Uses ASCII characters: * for filled, . for empty.
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
     * Formats a distance in blocks to a human-readable string.
     * Under 1000: "Xm". 1000+: "X.Xkm".
     */
    private static String formatDistance(double distance) {
        if (distance < 1000) {
            return (int) distance + "m";
        } else {
            return String.format("%.1fkm", distance / 1000.0);
        }
    }

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[ShopDirectory] onDismiss error: " + e.getMessage());
        }

        LOGGER.fine("[ShopDirectory] Page dismissed for " + playerRef.getUsername());
    }

    // ==================== ITEM SEARCH RESULT ====================

    /**
     * A single hit returned by the "items" search mode: a specific {@link ShopItem}
     * instance living inside a specific {@link ShopData}. Items mode renders one
     * card per result and clicking the card opens the browse page for the shop.
     */
    public static class ItemSearchResult {
        public final ShopData shop;
        public final ShopItem item;

        public ItemSearchResult(ShopData shop, ShopItem item) {
            this.shop = shop;
            this.item = item;
        }
    }

    // ==================== EVENT DATA CODEC ====================

    public static class DirData {
        public static final BuilderCodec<DirData> CODEC = BuilderCodec
            .<DirData>builder(DirData.class, DirData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .addField(new KeyedCodec<>("Tab", Codec.STRING),
                (data, value) -> data.tab = value,
                data -> data.tab)
            .addField(new KeyedCodec<>("Card", Codec.STRING),
                (data, value) -> data.card = value,
                data -> data.card)
            .addField(new KeyedCodec<>("SlotIndex", Codec.INTEGER),
                (data, value) -> data.itemSlot = value,
                data -> data.itemSlot)
            .addField(new KeyedCodec<>("BuyAction", Codec.STRING),
                (data, value) -> data.buyAction = value,
                data -> data.buyAction)
            .addField(new KeyedCodec<>("@BuyQty", Codec.INTEGER),
                (data, value) -> data.buyQty = value,
                data -> data.buyQty)
            .addField(new KeyedCodec<>("Mode", Codec.STRING),
                (data, value) -> data.mode = value,
                data -> data.mode)
            .addField(new KeyedCodec<>("@Search", Codec.STRING),
                (data, value) -> data.search = value,
                data -> data.search)
            .addField(new KeyedCodec<>("Sort", Codec.STRING),
                (data, value) -> data.sort = value,
                data -> data.sort)
            .addField(new KeyedCodec<>("CatFilter", Codec.STRING),
                (data, value) -> data.catFilter = value,
                data -> data.catFilter)
            .addField(new KeyedCodec<>("RatFilter", Codec.STRING),
                (data, value) -> data.ratFilter = value,
                data -> data.ratFilter)
            .build();

        private String button;
        private String tab;
        private String card;
        private Integer itemSlot;
        private String buyAction;
        private Integer buyQty;
        private String mode;
        private String search;
        private String sort;
        private String catFilter;
        private String ratFilter;

        public DirData() {}
    }
}
