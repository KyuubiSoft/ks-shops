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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.DirectoryService;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
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

    private List<ShopData> filteredShops = new ArrayList<>();
    private int totalResults = 0;

    public ShopDirectoryPage(PlayerRef playerRef, Player player, ShopPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, DirData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
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

        if (data.tab != null) {
            handleTabSwitch(data.tab);
            return;
        }

        if (data.card != null) {
            handleCardClick(data.card);
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

    // ==================== TAB SWITCHING ====================

    private void handleTabSwitch(String tab) {
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
        // Tab buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabAll",
            EventData.of("Tab", "all"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabAdmin",
            EventData.of("Tab", "admin"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabPlayer",
            EventData.of("Tab", "player"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabBar #TabFeatured",
            EventData.of("Tab", "featured"), false);

        // Card clicks
        for (int i = 0; i < CARDS_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                "#ShopGrid #DCard" + i + " #DBtn",
                EventData.of("Card", String.valueOf(i)), false);
        }

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
        ui.set("#DirTitle.Text", i18n.get(playerRef, "shop.directory.title"));

        // ---- Filter / search state ----
        // Reflect the current Java filter state in the UI widgets so users always see
        // which sort / category / rating filter is active (even on first open).
        ui.set("#TabBar #SortDropdown.Value", currentSort);
        ui.set("#FilterBar #CategoryFilter.Value", currentCategory);
        ui.set("#FilterBar #RatingFilter.Value", String.valueOf(currentRatingFilter));
        ui.set("#FilterBar #SearchField.Value", searchQuery);
        ui.set("#FilterBar #SearchField.PlaceholderText",
            i18n.get(playerRef, "shop.directory.search.placeholder"));

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
    }

    private void buildTabHighlights(UICommandBuilder ui) {
        // Active tab gets highlighted label color, inactive gets dim
        String activeColor = "#bfcdd5";
        String inactiveColor = "#778899";

        ui.set("#TabBar #TabAllLabel.Style.TextColor",
            "all".equals(currentTab) ? activeColor : inactiveColor);
        ui.set("#TabBar #TabAdminLabel.Style.TextColor",
            "admin".equals(currentTab) ? activeColor : inactiveColor);
        ui.set("#TabBar #TabPlayerLabel.Style.TextColor",
            "player".equals(currentTab) ? activeColor : inactiveColor);
        ui.set("#TabBar #TabFeaturedLabel.Style.TextColor",
            "featured".equals(currentTab) ? activeColor : inactiveColor);

        // Tab highlight: use Visible indicator bars instead of Style swap (Style is not dynamically settable)
        ui.set("#TabBar #TabAllInd.Visible", "all".equals(currentTab));
        ui.set("#TabBar #TabAdminInd.Visible", "admin".equals(currentTab));
        ui.set("#TabBar #TabPlayerInd.Visible", "player".equals(currentTab));
        ui.set("#TabBar #TabFeaturedInd.Visible", "featured".equals(currentTab));
    }

    private void buildCards(UICommandBuilder ui, ShopI18n i18n) {
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
            String prefix = "#ShopGrid #DCard" + i;
            int actualIndex = startIndex + i;

            if (actualIndex < filteredShops.size()) {
                ShopData shop = filteredShops.get(actualIndex);
                ui.set(prefix + ".Visible", true);

                // Avatar: prefer the owner-chosen icon, fall back to the first shop item
                String iconId = shop.getDisplayIconItemId();
                if (iconId != null) {
                    ui.set(prefix + " #DAvatar.ItemId", iconId);
                }

                // Shop name
                String name = shop.getName() != null ? shop.getName() : "Shop";
                ui.set(prefix + " #DName.Text", name);

                // Owner name
                if (shop.isPlayerShop() && shop.getOwnerName() != null) {
                    ui.set(prefix + " #DOwner.Text",
                        i18n.get(playerRef, "shop.directory.by", shop.getOwnerName()));
                } else if (shop.isAdminShop()) {
                    ui.set(prefix + " #DOwner.Text",
                        i18n.get(playerRef, "shop.directory.admin_shop"));
                } else {
                    ui.set(prefix + " #DOwner.Text", "");
                }

                // Rating display
                double avgRating = shop.getAverageRating();
                int totalRatings = shop.getTotalRatings();
                if (totalRatings > 0) {
                    String stars = buildStarString(avgRating);
                    ui.set(prefix + " #DRating.Text",
                        stars + " (" + String.format("%.1f", avgRating) + ")");
                } else {
                    ui.set(prefix + " #DRating.Text",
                        i18n.get(playerRef, "shop.directory.no_ratings"));
                }

                // Item count
                int itemCount = (shop.getItems() != null) ? shop.getItems().size() : 0;
                ui.set(prefix + " #DItems.Text",
                    i18n.get(playerRef, "shop.directory.item_count", itemCount));

                // Distance from player
                double dx = shop.getPosX() - playerX;
                double dz = shop.getPosZ() - playerZ;
                double distance = Math.sqrt(dx * dx + dz * dz);
                ui.set(prefix + " #DDistance.Text", formatDistance(distance));

                // Status badge
                if (shop.isFeatured() && shop.getFeaturedUntil() > System.currentTimeMillis()) {
                    ui.set(prefix + " #DStatus.Text", i18n.get(playerRef, "shop.directory.status.featured"));
                    ui.set(prefix + " #DStatus.Style.TextColor", "#ffd700");
                } else if (shop.isOpen()) {
                    ui.set(prefix + " #DStatus.Text", i18n.get(playerRef, "shop.directory.status.open"));
                    ui.set(prefix + " #DStatus.Style.TextColor", "#4caf50");
                } else {
                    ui.set(prefix + " #DStatus.Text", i18n.get(playerRef, "shop.directory.status.closed"));
                    ui.set(prefix + " #DStatus.Style.TextColor", "#cc4444");
                }

                // Category
                if (shop.getCategory() != null && !shop.getCategory().isEmpty()) {
                    ui.set(prefix + " #DCategory.Text", shop.getCategory());
                } else {
                    ui.set(prefix + " #DCategory.Text", "");
                }

                // Tooltip with description
                if (shop.getDescription() != null && !shop.getDescription().isEmpty()) {
                    ui.set(prefix + " #DBtn.TooltipText", shop.getDescription());
                } else {
                    ui.set(prefix + " #DBtn.TooltipText",
                        i18n.get(playerRef, "shop.directory.click_to_browse"));
                }
            } else {
                ui.set(prefix + ".Visible", false);
            }
        }
    }

    private void buildPagination(UICommandBuilder ui, ShopI18n i18n) {
        int totalPages = Math.max(1, (int) Math.ceil(totalResults / (double) CARDS_PER_PAGE));

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
        int totalPages = Math.max(1, (int) Math.ceil(totalResults / (double) CARDS_PER_PAGE));
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
        private String search;
        private String sort;
        private String catFilter;
        private String ratFilter;

        public DirData() {}
    }
}
