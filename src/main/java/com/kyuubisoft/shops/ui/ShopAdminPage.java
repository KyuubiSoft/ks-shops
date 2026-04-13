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

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.service.ShopManager;

import javax.annotation.Nonnull;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Shop Administration Panel -- comprehensive admin UI with 8 tabs:
 * General, Economy, Rent (settings), All Shops, Player Shops, Transactions (management),
 * Statistics, Blacklist (tools).
 *
 * Follows the sidebar+tabs pattern from SeasonPass and Bank admin panels.
 */
public class ShopAdminPage extends InteractiveCustomUIPage<ShopAdminPage.PageData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private static final String[] TABS = {
        "general", "economy", "rent", "shops", "players", "transactions", "stats", "blacklist"
    };
    private static final String[] TAB_IDS = {
        "SATabGeneral", "SATabEconomy", "SATabRent", "SATabShops",
        "SATabPlayers", "SATabTransactions", "SATabStats", "SATabBlacklist"
    };
    private static final String[] PANEL_IDS = {
        "SAPanelGeneral", "SAPanelEconomy", "SAPanelRent", "SAPanelShops",
        "SAPanelPlayers", "SAPanelTransactions", "SAPanelStats", "SAPanelBlacklist"
    };

    private static final int SHOPS_PER_PAGE = 8;
    private static final int PLAYERS_PER_PAGE = 6;
    private static final int TX_PER_PAGE = 10;
    private static final int BL_PER_PAGE = 10;

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;

    private String activeTab = "general";
    private String statusMessage = "";

    // Shops tab state
    private int shopsPage = 0;
    private String shopsSearch = "";
    private List<ShopData> cachedShopsList = new ArrayList<>();

    // Players tab state
    private int playersPage = 0;
    private String playersSearch = "";
    private List<ShopData> cachedPlayerShops = new ArrayList<>();

    // Transactions tab state
    private int txPage = 0;
    private String txSearch = "";
    private List<ShopDatabase.TransactionRecord> cachedTransactions = new ArrayList<>();

    // Blacklist state
    private int blPage = 0;

    public ShopAdminPage(PlayerRef playerRef, Player player, ShopPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/ShopAdmin.ui");

        bindAllEvents(events);
        populateTab(ui);
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull PageData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) {
            sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // Tab switching
        if (data.action.startsWith("tab_")) {
            String tab = data.action.substring(4);
            if (!tab.equals(activeTab)) {
                activeTab = tab;
                statusMessage = "";
            }
            refreshUI();
            return;
        }

        switch (data.action) {
            case "close" -> { close(); return; }
            case "reload" -> handleReload();

            // General settings
            case "save_general" -> handleGeneralSave(data);

            // Economy settings
            case "save_economy" -> handleEconomySave(data);

            // Rent settings
            case "save_rent" -> handleRentSave(data);

            // Shops management
            case "shops_search" -> {
                shopsSearch = data.value != null ? data.value.trim() : "";
                shopsPage = 0;
            }
            case "shops_prev" -> { if (shopsPage > 0) shopsPage--; }
            case "shops_next" -> shopsPage++;

            // Players management
            case "players_search" -> {
                playersSearch = data.value != null ? data.value.trim() : "";
                playersPage = 0;
            }
            case "players_prev" -> { if (playersPage > 0) playersPage--; }
            case "players_next" -> playersPage++;

            // Transactions
            case "tx_search" -> {
                txSearch = data.value != null ? data.value.trim() : "";
                txPage = 0;
            }
            case "tx_prev" -> { if (txPage > 0) txPage--; }
            case "tx_next" -> txPage++;

            // Blacklist
            case "bl_add" -> handleBlacklistAdd(data);
            case "bl_prev" -> { if (blPage > 0) blPage--; }
            case "bl_next" -> blPage++;

            default -> {
                // Row actions: shops_delete_N, shops_edit_N, players_close_N, etc.
                if (data.action.startsWith("shops_delete_")) handleShopDelete(data.action);
                else if (data.action.startsWith("players_close_")) handlePlayerClose(data.action);
                else if (data.action.startsWith("players_open_")) handlePlayerOpen(data.action);
                else if (data.action.startsWith("players_delete_")) handlePlayerDelete(data.action);
                else if (data.action.startsWith("bl_remove_")) handleBlacklistRemove(data.action);
            }
        }

        refreshUI();
    }

    // ==================== REFRESH ====================

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindAllEvents(events);
        populateTab(ui);
        this.sendUpdate(ui, events, false);
    }

    // ==================== TAB VISIBILITY ====================

    private void updateTabVisibility(UICommandBuilder ui) {
        for (int i = 0; i < TABS.length; i++) {
            boolean active = TABS[i].equals(activeTab);
            ui.set("#" + PANEL_IDS[i] + ".Visible", active);
            ui.set("#" + TAB_IDS[i] + "Ind.Background", active ? "#00bfff" : "#00000000");
        }
    }

    // ==================== POPULATE ====================

    private void populateTab(UICommandBuilder ui) {
        updateTabVisibility(ui);
        ui.set("#SAStatusMsg.Text", statusMessage);
        ui.set("#SAStatusMsg.Style.TextColor", statusMessage.startsWith("Error") ? "#ff4444" : "#44ff44");

        switch (activeTab) {
            case "general" -> populateGeneral(ui);
            case "economy" -> populateEconomy(ui);
            case "rent" -> populateRent(ui);
            case "shops" -> populateShops(ui);
            case "players" -> populatePlayers(ui);
            case "transactions" -> populateTransactions(ui);
            case "stats" -> populateStats(ui);
            case "blacklist" -> populateBlacklist(ui);
        }
    }

    // ==================== GENERAL TAB ====================

    private void populateGeneral(UICommandBuilder ui) {
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
        ui.set("#GMaxShops.Value", String.valueOf(cfg.playerShops.maxShopsPerPlayer));
        ui.set("#GMaxItems.Value", String.valueOf(cfg.playerShops.maxItemsPerShop));
        ui.set("#GCreationCost.Value", String.valueOf(cfg.playerShops.creationCost));
        ui.set("#GNameMaxLen.Value", String.valueOf(cfg.playerShops.nameMaxLength));
        ui.set("#GAllowPlayerShops.Value", cfg.features.playerShops);
        ui.set("#GAutoCloseDays.Value", "0");
        ui.set("#GAutoDeleteDays.Value", "0");
    }

    private void handleGeneralSave(PageData data) {
        try {
            ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
            if (data.gMaxShops != null) cfg.playerShops.maxShopsPerPlayer = Integer.parseInt(data.gMaxShops.trim());
            if (data.gMaxItems != null) cfg.playerShops.maxItemsPerShop = Integer.parseInt(data.gMaxItems.trim());
            if (data.gCreationCost != null) cfg.playerShops.creationCost = Integer.parseInt(data.gCreationCost.trim());
            if (data.gNameMaxLen != null) cfg.playerShops.nameMaxLength = Integer.parseInt(data.gNameMaxLen.trim());
            if (data.gAllowPlayerShops != null) cfg.features.playerShops = data.gAllowPlayerShops;
            cfg.validate();
            plugin.getShopConfig().save();
            statusMessage = "General settings saved.";
        } catch (NumberFormatException e) {
            statusMessage = "Error: Invalid number format.";
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    // ==================== ECONOMY TAB ====================

    private void populateEconomy(UICommandBuilder ui) {
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
        ui.set("#ETaxEnabled.Value", cfg.tax.enabled);
        ui.set("#ETaxRate.Value", String.valueOf(cfg.tax.buyTaxPercent));
        ui.set("#ESellTaxRate.Value", String.valueOf(cfg.tax.sellTaxPercent));
        ui.set("#EDisplayTax.Value", cfg.tax.showTaxInPrice);
    }

    private void handleEconomySave(PageData data) {
        try {
            ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
            if (data.eTaxEnabled != null) cfg.tax.enabled = data.eTaxEnabled;
            if (data.eTaxRate != null) cfg.tax.buyTaxPercent = Double.parseDouble(data.eTaxRate.trim());
            if (data.eSellTaxRate != null) cfg.tax.sellTaxPercent = Double.parseDouble(data.eSellTaxRate.trim());
            if (data.eDisplayTax != null) cfg.tax.showTaxInPrice = data.eDisplayTax;
            cfg.validate();
            plugin.getShopConfig().save();
            statusMessage = "Economy settings saved.";
        } catch (NumberFormatException e) {
            statusMessage = "Error: Invalid number format.";
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    // ==================== RENT TAB ====================

    private void populateRent(UICommandBuilder ui) {
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
        ui.set("#RRentEnabled.Value", cfg.rent.enabled);
        ui.set("#RDailyAmount.Value", String.valueOf(cfg.rent.costPerDay));
        ui.set("#RGraceDays.Value", String.valueOf(cfg.rent.gracePeriodDays));
        ui.set("#RAutoClose.Value", cfg.rent.autoCloseOnExpire);
        ui.set("#RAutoDeleteDays.Value", String.valueOf(cfg.rent.autoDeleteOnUnpaidDays));
    }

    private void handleRentSave(PageData data) {
        try {
            ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
            if (data.rRentEnabled != null) cfg.rent.enabled = data.rRentEnabled;
            if (data.rDailyAmount != null) cfg.rent.costPerDay = Double.parseDouble(data.rDailyAmount.trim());
            if (data.rGraceDays != null) cfg.rent.gracePeriodDays = Integer.parseInt(data.rGraceDays.trim());
            if (data.rAutoClose != null) cfg.rent.autoCloseOnExpire = data.rAutoClose;
            if (data.rAutoDeleteDays != null) cfg.rent.autoDeleteOnUnpaidDays = Integer.parseInt(data.rAutoDeleteDays.trim());
            cfg.validate();
            plugin.getShopConfig().save();
            statusMessage = "Rent settings saved.";
        } catch (NumberFormatException e) {
            statusMessage = "Error: Invalid number format.";
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    // ==================== ALL SHOPS TAB ====================

    private void populateShops(UICommandBuilder ui) {
        // Reflect the current search string in the UI so it survives refreshes.
        ui.set("#SSearchField.Value", shopsSearch);

        ShopManager manager = plugin.getShopManager();
        List<ShopData> allShops = new ArrayList<>(manager.getAllShops());

        // Filter by search
        if (!shopsSearch.isEmpty()) {
            String lower = shopsSearch.toLowerCase();
            allShops = allShops.stream()
                .filter(s -> (s.getName() != null && s.getName().toLowerCase().contains(lower))
                    || (s.getOwnerName() != null && s.getOwnerName().toLowerCase().contains(lower))
                    || s.getId().toString().toLowerCase().contains(lower))
                .collect(Collectors.toList());
        }

        // Sort by name
        allShops.sort(Comparator.comparing(s -> s.getName() != null ? s.getName() : "", String.CASE_INSENSITIVE_ORDER));
        cachedShopsList = allShops;

        int total = allShops.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) SHOPS_PER_PAGE));
        shopsPage = Math.min(shopsPage, totalPages - 1);
        shopsPage = Math.max(shopsPage, 0);

        int start = shopsPage * SHOPS_PER_PAGE;
        boolean anyVisible = false;

        for (int i = 0; i < SHOPS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx < total) {
                ShopData shop = allShops.get(idx);
                anyVisible = true;
                ui.set("#SRow" + i + ".Visible", true);
                ui.set("#SName" + i + ".Text", shop.getName() != null ? shop.getName() : "?");
                ui.set("#SOwner" + i + ".Text", shop.getOwnerName() != null ? shop.getOwnerName() : "-");
                ui.set("#SType" + i + ".Text", shop.getType() != null ? shop.getType().name() : "?");
                ui.set("#SItems" + i + ".Text", String.valueOf(shop.getItems() != null ? shop.getItems().size() : 0));

                if (shop.isOpen()) {
                    ui.set("#SStatus" + i + ".Text", "OPEN");
                    ui.set("#SStatus" + i + ".Style.TextColor", "#44ff44");
                } else {
                    ui.set("#SStatus" + i + ".Text", "CLOSED");
                    ui.set("#SStatus" + i + ".Style.TextColor", "#ff4444");
                }
            } else {
                ui.set("#SRow" + i + ".Visible", false);
            }
        }

        ui.set("#SEmpty.Visible", !anyVisible);
        ui.set("#SPageInfo.Text", (shopsPage + 1) + " / " + totalPages);
        ui.set("#SPrevBtn.Visible", shopsPage > 0);
        ui.set("#SNextBtn.Visible", shopsPage < totalPages - 1);
    }

    private void handleShopDelete(String action) {
        try {
            int rowIdx = Integer.parseInt(action.replace("shops_delete_", ""));
            int actualIdx = shopsPage * SHOPS_PER_PAGE + rowIdx;
            if (actualIdx >= 0 && actualIdx < cachedShopsList.size()) {
                ShopData shop = cachedShopsList.get(actualIdx);
                plugin.getShopManager().deleteShop(shop.getId());
                statusMessage = "Deleted shop: " + shop.getName();
            }
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    // ==================== PLAYER SHOPS TAB ====================

    private void populatePlayers(UICommandBuilder ui) {
        // Reflect the current search string in the UI so it survives refreshes.
        ui.set("#PSearchField.Value", playersSearch);

        ShopManager manager = plugin.getShopManager();
        List<ShopData> playerShops = manager.getAllShops().stream()
            .filter(ShopData::isPlayerShop)
            .collect(Collectors.toList());

        // Filter by search
        if (!playersSearch.isEmpty()) {
            String lower = playersSearch.toLowerCase();
            playerShops = playerShops.stream()
                .filter(s -> (s.getName() != null && s.getName().toLowerCase().contains(lower))
                    || (s.getOwnerName() != null && s.getOwnerName().toLowerCase().contains(lower)))
                .collect(Collectors.toList());
        }

        playerShops.sort(Comparator.comparing(s -> s.getName() != null ? s.getName() : "", String.CASE_INSENSITIVE_ORDER));
        cachedPlayerShops = playerShops;

        int total = playerShops.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) PLAYERS_PER_PAGE));
        playersPage = Math.min(playersPage, totalPages - 1);
        playersPage = Math.max(playersPage, 0);

        int start = playersPage * PLAYERS_PER_PAGE;
        boolean anyVisible = false;

        for (int i = 0; i < PLAYERS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx < total) {
                ShopData shop = playerShops.get(idx);
                anyVisible = true;
                ui.set("#PRow" + i + ".Visible", true);
                ui.set("#PShopName" + i + ".Text", shop.getName() != null ? shop.getName() : "?");
                ui.set("#POwnerName" + i + ".Text", shop.getOwnerName() != null ? shop.getOwnerName() : "-");

                if (shop.isOpen()) {
                    ui.set("#PStatus" + i + ".Text", "OPEN");
                    ui.set("#PStatus" + i + ".Style.TextColor", "#44ff44");
                } else {
                    ui.set("#PStatus" + i + ".Text", "CLOSED");
                    ui.set("#PStatus" + i + ".Style.TextColor", "#ff4444");
                }

                double rating = shop.getAverageRating();
                ui.set("#PRating" + i + ".Text", shop.getTotalRatings() > 0
                    ? String.format("%.1f", rating)
                    : "-");
            } else {
                ui.set("#PRow" + i + ".Visible", false);
            }
        }

        ui.set("#PEmpty.Visible", !anyVisible);
        ui.set("#PPageInfo.Text", (playersPage + 1) + " / " + totalPages);
        ui.set("#PPrevBtn.Visible", playersPage > 0);
        ui.set("#PNextBtn.Visible", playersPage < totalPages - 1);
    }

    private void handlePlayerClose(String action) {
        try {
            int rowIdx = Integer.parseInt(action.replace("players_close_", ""));
            int actualIdx = playersPage * PLAYERS_PER_PAGE + rowIdx;
            if (actualIdx >= 0 && actualIdx < cachedPlayerShops.size()) {
                ShopData shop = cachedPlayerShops.get(actualIdx);
                shop.setOpen(false);
                statusMessage = "Closed shop: " + shop.getName();
            }
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    private void handlePlayerOpen(String action) {
        try {
            int rowIdx = Integer.parseInt(action.replace("players_open_", ""));
            int actualIdx = playersPage * PLAYERS_PER_PAGE + rowIdx;
            if (actualIdx >= 0 && actualIdx < cachedPlayerShops.size()) {
                ShopData shop = cachedPlayerShops.get(actualIdx);
                shop.setOpen(true);
                statusMessage = "Opened shop: " + shop.getName();
            }
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    private void handlePlayerDelete(String action) {
        try {
            int rowIdx = Integer.parseInt(action.replace("players_delete_", ""));
            int actualIdx = playersPage * PLAYERS_PER_PAGE + rowIdx;
            if (actualIdx >= 0 && actualIdx < cachedPlayerShops.size()) {
                ShopData shop = cachedPlayerShops.get(actualIdx);
                plugin.getShopManager().deleteShop(shop.getId());
                statusMessage = "Deleted player shop: " + shop.getName();
            }
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    // ==================== TRANSACTIONS TAB ====================

    private void populateTransactions(UICommandBuilder ui) {
        // Reflect the current search string in the UI so it survives refreshes.
        ui.set("#TSearchField.Value", txSearch);

        // Load transactions via database — search by player name if provided
        ShopDatabase db = plugin.getDatabase();
        List<ShopDatabase.TransactionRecord> transactions;

        if (!txSearch.isEmpty()) {
            // Find player UUID by name from playerData map
            UUID targetUuid = null;
            for (var entry : plugin.getPlayerDataMap().entrySet()) {
                if (entry.getValue() != null && entry.getValue().getUsername() != null
                    && entry.getValue().getUsername().equalsIgnoreCase(txSearch)) {
                    try {
                        targetUuid = UUID.fromString(entry.getKey());
                    } catch (IllegalArgumentException ignored) {}
                    break;
                }
            }
            if (targetUuid != null) {
                transactions = db.loadTransactions(targetUuid, 500);
            } else {
                transactions = new ArrayList<>();
            }
        } else {
            // Load recent transactions for all (use a dummy bulk query via iterating shops)
            // We'll load from all known player transactions up to a limit
            transactions = new ArrayList<>();
            for (ShopData shop : plugin.getShopManager().getAllShops()) {
                if (shop.getOwnerUuid() != null) {
                    List<ShopDatabase.TransactionRecord> shopTx = db.loadTransactions(shop.getOwnerUuid(), 50);
                    transactions.addAll(shopTx);
                }
            }
            // Deduplicate by timestamp + buyer (crude but functional)
            Set<String> seen = new HashSet<>();
            transactions = transactions.stream()
                .filter(t -> seen.add(t.timestamp + "_" + t.buyerUuid + "_" + t.itemId))
                .sorted(Comparator.comparingLong((ShopDatabase.TransactionRecord t) -> t.timestamp).reversed())
                .limit(500)
                .collect(Collectors.toList());
        }

        cachedTransactions = transactions;

        int total = transactions.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) TX_PER_PAGE));
        txPage = Math.min(txPage, totalPages - 1);
        txPage = Math.max(txPage, 0);

        int start = txPage * TX_PER_PAGE;
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm");
        boolean anyVisible = false;

        for (int i = 0; i < TX_PER_PAGE; i++) {
            int idx = start + i;
            if (idx < total) {
                ShopDatabase.TransactionRecord tx = transactions.get(idx);
                anyVisible = true;
                ui.set("#TRow" + i + ".Visible", true);
                ui.set("#TTime" + i + ".Text", sdf.format(new Date(tx.timestamp)));
                ui.set("#TBuyer" + i + ".Text", tx.buyerName != null ? tx.buyerName : "?");

                // Find seller name from shop data
                String sellerName = "-";
                if (tx.sellerUuid != null) {
                    try {
                        ShopData shop = plugin.getShopManager().getShop(UUID.fromString(tx.shopId));
                        if (shop != null && shop.getOwnerName() != null) {
                            sellerName = shop.getOwnerName();
                        }
                    } catch (Exception ignored) {}
                }
                ui.set("#TSeller" + i + ".Text", sellerName);

                // Simplify item ID (remove namespace prefix)
                String itemId = tx.itemId;
                if (itemId != null && itemId.contains("_")) {
                    // Keep as-is for readability
                }
                ui.set("#TItem" + i + ".Text", itemId != null ? itemId : "?");
                ui.set("#TQty" + i + ".Text", String.valueOf(tx.quantity));
                ui.set("#TPrice" + i + ".Text", String.valueOf(tx.totalPrice));
                ui.set("#TTax" + i + ".Text", String.valueOf(tx.taxAmount));
            } else {
                ui.set("#TRow" + i + ".Visible", false);
            }
        }

        ui.set("#TEmpty.Visible", !anyVisible);
        ui.set("#TPageInfo.Text", (txPage + 1) + " / " + totalPages);
        ui.set("#TPrevBtn.Visible", txPage > 0);
        ui.set("#TNextBtn.Visible", txPage < totalPages - 1);
    }

    // ==================== STATISTICS TAB ====================

    private void populateStats(UICommandBuilder ui) {
        Collection<ShopData> allShops = plugin.getShopManager().getAllShops();

        long totalShops = allShops.size();
        long playerShops = allShops.stream().filter(ShopData::isPlayerShop).count();
        long adminShops = allShops.stream().filter(ShopData::isAdminShop).count();
        long activeShops = allShops.stream().filter(ShopData::isOpen).count();
        long closedShops = totalShops - activeShops;

        double totalVolume = allShops.stream().mapToDouble(ShopData::getTotalRevenue).sum();
        double totalTax = allShops.stream().mapToDouble(ShopData::getTotalTaxPaid).sum();

        // Calculate rent collected from shops that have rent set
        double rentCollected = allShops.stream()
            .filter(s -> s.getRentCostPerCycle() > 0)
            .mapToDouble(s -> s.getRentCostPerCycle() * Math.max(0, s.getRentCycleDays()))
            .sum();

        ui.set("#StatTotalShops.Text", String.valueOf(totalShops));
        ui.set("#StatPlayerShops.Text", String.valueOf(playerShops));
        ui.set("#StatAdminShops.Text", String.valueOf(adminShops));
        ui.set("#StatActiveShops.Text", String.valueOf(activeShops));
        ui.set("#StatClosedShops.Text", String.valueOf(closedShops));
        ui.set("#StatTotalVolume.Text", formatCurrency(totalVolume));
        ui.set("#StatTaxCollected.Text", formatCurrency(totalTax));
        ui.set("#StatRentCollected.Text", formatCurrency(rentCollected));

        // Find top seller (player with most total revenue across their shops)
        Map<String, Double> sellerRevenue = new HashMap<>();
        for (ShopData shop : allShops) {
            if (shop.isPlayerShop() && shop.getOwnerName() != null) {
                sellerRevenue.merge(shop.getOwnerName(), shop.getTotalRevenue(), Double::sum);
            }
        }
        String topSeller = sellerRevenue.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> e.getKey() + " (" + formatCurrency(e.getValue()) + ")")
            .orElse("-");
        ui.set("#StatTopSeller.Text", topSeller);

        // Find most sold item (across all shops by counting items in shops)
        Map<String, Integer> itemCounts = new HashMap<>();
        for (ShopData shop : allShops) {
            if (shop.getItems() != null) {
                for (var item : shop.getItems()) {
                    itemCounts.merge(item.getItemId(), 1, Integer::sum);
                }
            }
        }
        String topItem = itemCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> e.getKey() + " (" + e.getValue() + " shops)")
            .orElse("-");
        ui.set("#StatTopItem.Text", topItem);

        // Transactions today placeholder (would need timestamp query)
        ui.set("#StatTransactionsToday.Text", "-");
    }

    // ==================== BLACKLIST TAB ====================

    private void populateBlacklist(UICommandBuilder ui) {
        List<String> blacklist = plugin.getShopConfig().getData().itemBlacklist;
        if (blacklist == null) blacklist = new ArrayList<>();

        int total = blacklist.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) BL_PER_PAGE));
        blPage = Math.min(blPage, totalPages - 1);
        blPage = Math.max(blPage, 0);

        int start = blPage * BL_PER_PAGE;
        boolean anyVisible = false;

        for (int i = 0; i < BL_PER_PAGE; i++) {
            int idx = start + i;
            if (idx < total) {
                anyVisible = true;
                ui.set("#BRow" + i + ".Visible", true);
                ui.set("#BItemId" + i + ".Text", blacklist.get(idx));
            } else {
                ui.set("#BRow" + i + ".Visible", false);
            }
        }

        ui.set("#BEmpty.Visible", !anyVisible);
        ui.set("#BPageInfo.Text", (blPage + 1) + " / " + totalPages);
        ui.set("#BPrevBtn.Visible", blPage > 0);
        ui.set("#BNextBtn.Visible", blPage < totalPages - 1);
    }

    private void handleBlacklistAdd(PageData data) {
        if (data.value == null || data.value.trim().isEmpty()) {
            statusMessage = "Error: Item ID cannot be empty.";
            return;
        }
        String itemId = data.value.trim();
        List<String> blacklist = plugin.getShopConfig().getData().itemBlacklist;
        if (blacklist == null) {
            blacklist = new ArrayList<>();
            plugin.getShopConfig().getData().itemBlacklist = blacklist;
        }
        if (blacklist.contains(itemId)) {
            statusMessage = "Item already blacklisted: " + itemId;
            return;
        }
        blacklist.add(itemId);
        plugin.getShopConfig().save();
        statusMessage = "Blacklisted: " + itemId;
    }

    private void handleBlacklistRemove(String action) {
        try {
            int rowIdx = Integer.parseInt(action.replace("bl_remove_", ""));
            int actualIdx = blPage * BL_PER_PAGE + rowIdx;
            List<String> blacklist = plugin.getShopConfig().getData().itemBlacklist;
            if (blacklist != null && actualIdx >= 0 && actualIdx < blacklist.size()) {
                String removed = blacklist.remove(actualIdx);
                plugin.getShopConfig().save();
                statusMessage = "Removed from blacklist: " + removed;
            }
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
        }
    }

    // ==================== RELOAD ====================

    private void handleReload() {
        try {
            plugin.getShopConfig().reload();
            plugin.getI18n().load();
            statusMessage = "Config and localization reloaded.";
        } catch (Exception e) {
            statusMessage = "Error: Reload failed: " + e.getMessage();
        }
    }

    // ==================== HELPERS ====================

    private static String formatCurrency(double amount) {
        if (amount >= 1_000_000) {
            return String.format("%.1fM", amount / 1_000_000.0);
        } else if (amount >= 1_000) {
            return String.format("%.1fK", amount / 1_000.0);
        } else {
            return String.valueOf((int) amount);
        }
    }

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[ShopAdmin] onDismiss error: " + e.getMessage());
        }
    }

    // ==================== EVENT BINDING ====================

    private void bindAllEvents(UIEventBuilder events) {
        // Tab buttons
        for (int i = 0; i < TABS.length; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#" + TAB_IDS[i],
                EventData.of("Action", "tab_" + TABS[i]), false);
        }

        // Close + Reload
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SACloseBtn",
            EventData.of("Action", "close"), true);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SAReloadBtn",
            EventData.of("Action", "reload"), false);

        // General save
        events.addEventBinding(CustomUIEventBindingType.Activating, "#GSaveBtn",
            EventData.of("Action", "save_general")
                .append("@GMaxShops", "#GMaxShops.Value")
                .append("@GMaxItems", "#GMaxItems.Value")
                .append("@GCreationCost", "#GCreationCost.Value")
                .append("@GNameMaxLen", "#GNameMaxLen.Value")
                .append("@GAllowPlayerShops", "#GAllowPlayerShops.Value")
                .append("@GAutoCloseDays", "#GAutoCloseDays.Value")
                .append("@GAutoDeleteDays", "#GAutoDeleteDays.Value"),
            false);

        // Economy save
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ESaveBtn",
            EventData.of("Action", "save_economy")
                .append("@ETaxEnabled", "#ETaxEnabled.Value")
                .append("@ETaxRate", "#ETaxRate.Value")
                .append("@ESellTaxRate", "#ESellTaxRate.Value")
                .append("@EDisplayTax", "#EDisplayTax.Value"),
            false);

        // Rent save
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RSaveBtn",
            EventData.of("Action", "save_rent")
                .append("@RRentEnabled", "#RRentEnabled.Value")
                .append("@RDailyAmount", "#RDailyAmount.Value")
                .append("@RGraceDays", "#RGraceDays.Value")
                .append("@RAutoClose", "#RAutoClose.Value")
                .append("@RAutoDeleteDays", "#RAutoDeleteDays.Value"),
            false);

        // Shops search + pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SSearchBtn",
            EventData.of("Action", "shops_search")
                .append("@Value", "#SSearchField.Value"),
            false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SPrevBtn",
            EventData.of("Action", "shops_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SNextBtn",
            EventData.of("Action", "shops_next"), false);

        // Shop row buttons (edit + delete)
        for (int i = 0; i < SHOPS_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#SDeleteBtn" + i,
                EventData.of("Action", "shops_delete_" + i), false);
        }

        // Players search + pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PSearchBtn",
            EventData.of("Action", "players_search")
                .append("@Value", "#PSearchField.Value"),
            false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PPrevBtn",
            EventData.of("Action", "players_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PNextBtn",
            EventData.of("Action", "players_next"), false);

        // Player row buttons (close, open, delete)
        for (int i = 0; i < PLAYERS_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PCloseBtn" + i,
                EventData.of("Action", "players_close_" + i), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#POpenBtn" + i,
                EventData.of("Action", "players_open_" + i), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PDeleteBtn" + i,
                EventData.of("Action", "players_delete_" + i), false);
        }

        // Transactions search + pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TSearchBtn",
            EventData.of("Action", "tx_search")
                .append("@Value", "#TSearchField.Value"),
            false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TPrevBtn",
            EventData.of("Action", "tx_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TNextBtn",
            EventData.of("Action", "tx_next"), false);

        // Blacklist add + row remove + pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BAddBtn",
            EventData.of("Action", "bl_add")
                .append("@Value", "#BItemField.Value"),
            false);
        for (int i = 0; i < BL_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#BRemoveBtn" + i,
                EventData.of("Action", "bl_remove_" + i), false);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BPrevBtn",
            EventData.of("Action", "bl_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BNextBtn",
            EventData.of("Action", "bl_next"), false);
    }

    // ==================== EVENT DATA CODEC ====================

    public static class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec
            .<PageData>builder(PageData.class, PageData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, v) -> d.action = v, d -> d.action)
            .addField(new KeyedCodec<>("@Value", Codec.STRING),
                (d, v) -> d.value = v, d -> d.value)
            // General fields
            .addField(new KeyedCodec<>("@GMaxShops", Codec.STRING),
                (d, v) -> d.gMaxShops = v, d -> d.gMaxShops)
            .addField(new KeyedCodec<>("@GMaxItems", Codec.STRING),
                (d, v) -> d.gMaxItems = v, d -> d.gMaxItems)
            .addField(new KeyedCodec<>("@GCreationCost", Codec.STRING),
                (d, v) -> d.gCreationCost = v, d -> d.gCreationCost)
            .addField(new KeyedCodec<>("@GNameMaxLen", Codec.STRING),
                (d, v) -> d.gNameMaxLen = v, d -> d.gNameMaxLen)
            .addField(new KeyedCodec<>("@GAllowPlayerShops", Codec.BOOLEAN),
                (d, v) -> d.gAllowPlayerShops = v, d -> d.gAllowPlayerShops)
            .addField(new KeyedCodec<>("@GAutoCloseDays", Codec.STRING),
                (d, v) -> d.gAutoCloseDays = v, d -> d.gAutoCloseDays)
            .addField(new KeyedCodec<>("@GAutoDeleteDays", Codec.STRING),
                (d, v) -> d.gAutoDeleteDays = v, d -> d.gAutoDeleteDays)
            // Economy fields
            .addField(new KeyedCodec<>("@ETaxEnabled", Codec.BOOLEAN),
                (d, v) -> d.eTaxEnabled = v, d -> d.eTaxEnabled)
            .addField(new KeyedCodec<>("@ETaxRate", Codec.STRING),
                (d, v) -> d.eTaxRate = v, d -> d.eTaxRate)
            .addField(new KeyedCodec<>("@ESellTaxRate", Codec.STRING),
                (d, v) -> d.eSellTaxRate = v, d -> d.eSellTaxRate)
            .addField(new KeyedCodec<>("@EDisplayTax", Codec.BOOLEAN),
                (d, v) -> d.eDisplayTax = v, d -> d.eDisplayTax)
            // Rent fields
            .addField(new KeyedCodec<>("@RRentEnabled", Codec.BOOLEAN),
                (d, v) -> d.rRentEnabled = v, d -> d.rRentEnabled)
            .addField(new KeyedCodec<>("@RDailyAmount", Codec.STRING),
                (d, v) -> d.rDailyAmount = v, d -> d.rDailyAmount)
            .addField(new KeyedCodec<>("@RGraceDays", Codec.STRING),
                (d, v) -> d.rGraceDays = v, d -> d.rGraceDays)
            .addField(new KeyedCodec<>("@RAutoClose", Codec.BOOLEAN),
                (d, v) -> d.rAutoClose = v, d -> d.rAutoClose)
            .addField(new KeyedCodec<>("@RAutoDeleteDays", Codec.STRING),
                (d, v) -> d.rAutoDeleteDays = v, d -> d.rAutoDeleteDays)
            .build();

        private String action;
        private String value;
        // General
        private String gMaxShops;
        private String gMaxItems;
        private String gCreationCost;
        private String gNameMaxLen;
        private Boolean gAllowPlayerShops;
        private String gAutoCloseDays;
        private String gAutoDeleteDays;
        // Economy
        private Boolean eTaxEnabled;
        private String eTaxRate;
        private String eSellTaxRate;
        private Boolean eDisplayTax;
        // Rent
        private Boolean rRentEnabled;
        private String rDailyAmount;
        private String rGraceDays;
        private Boolean rAutoClose;
        private String rAutoDeleteDays;

        public PageData() {}
    }
}
