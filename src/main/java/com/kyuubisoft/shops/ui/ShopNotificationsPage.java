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
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.i18n.ShopI18n;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Notifications page for KS-Shops.
 *
 * Displays offline sale notifications with a summary (total earnings, items sold)
 * and paginated sale rows. Allows marking all as read.
 *
 * Opened via /ksshop notifications.
 *
 * Usage:
 * <pre>
 *   ShopNotificationsPage page = new ShopNotificationsPage(playerRef, player, plugin);
 *   player.getPageManager().openCustomPage(ref, store, page);
 * </pre>
 */
public class ShopNotificationsPage extends InteractiveCustomUIPage<ShopNotificationsPage.NotificationData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final int SALES_PER_PAGE = 8;

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;

    private int currentPage = 0;
    private List<ShopDatabase.SaleNotification> notifications;

    // Cached summary
    private int totalEarnings = 0;
    private int totalItemsSold = 0;

    public ShopNotificationsPage(PlayerRef playerRef, Player player, ShopPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, NotificationData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.notifications = new ArrayList<>();
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/ShopNotifications.ui");

        // Load notifications from DB
        loadNotifications();

        bindAllEvents(events);
        buildUI(ui);
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull NotificationData data) {
        super.handleDataEvent(ref, store, data);

        if (data.button != null) {
            switch (data.button) {
                case "markRead" -> {
                    handleMarkRead();
                    return;
                }
                case "close" -> {
                    handleClose(ref, store);
                    return;
                }
                case "prev" -> {
                    handlePagination(-1);
                    return;
                }
                case "next" -> {
                    handlePagination(1);
                    return;
                }
            }
        }

        // Catch-all: prevent permanent "Loading..." state
        this.sendUpdate(new UICommandBuilder(), false);
    }

    // ==================== MARK READ ====================

    private void handleMarkRead() {
        UUID playerUuid = playerRef.getUuid();

        try {
            plugin.getDatabase().markNotificationsRead(playerUuid);
            notifications.clear();
            totalEarnings = 0;
            totalItemsSold = 0;
            currentPage = 0;

            ShopI18n i18n = plugin.getI18n();
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.notifications.marked_read")).color("#55FF55"));
        } catch (Exception e) {
            LOGGER.warning("[ShopNotifications] Failed to mark notifications read: " + e.getMessage());
        }

        refreshUI();
    }

    // ==================== CLOSE ====================

    private void handleClose(Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            this.close();
        } catch (Exception e) {
            LOGGER.warning("[ShopNotifications] Failed to close page: " + e.getMessage());
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== PAGINATION ====================

    private void handlePagination(int direction) {
        int totalPages = getTotalPages();
        int newPage = currentPage + direction;
        if (newPage >= 0 && newPage < totalPages) {
            currentPage = newPage;
        }
        refreshUI();
    }

    // ==================== DATA LOADING ====================

    private void loadNotifications() {
        try {
            UUID playerUuid = playerRef.getUuid();
            notifications = plugin.getDatabase().loadNotifications(playerUuid);
        } catch (Exception e) {
            LOGGER.warning("[ShopNotifications] Failed to load notifications: " + e.getMessage());
            notifications = new ArrayList<>();
        }

        // Calculate summary
        totalEarnings = 0;
        totalItemsSold = 0;
        for (ShopDatabase.SaleNotification n : notifications) {
            totalEarnings += n.earned;
            totalItemsSold += n.quantity;
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
        // Mark All Read button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#MarkReadBtn",
            EventData.of("Button", "markRead"), false);

        // Close button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn",
            EventData.of("Button", "close"), false);

        // Pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NotifPrev",
            EventData.of("Button", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NotifNext",
            EventData.of("Button", "next"), false);
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui) {
        // Summary section
        buildSummary(ui);

        // Sales list
        buildSalesList(ui);

        // Pagination
        buildPagination(ui);

        // Mark Read button state
        ui.set("#MarkReadBtn.Visible", !notifications.isEmpty());
    }

    private void buildSummary(UICommandBuilder ui) {
        if (notifications.isEmpty()) {
            ui.set("#SummaryHeader.Text", "NO NEW NOTIFICATIONS");
            ui.set("#TotalEarnings.Text", "");
            ui.set("#ItemsSold.Text", "All caught up!");
        } else {
            ui.set("#SummaryHeader.Text", "SINCE LAST LOGIN");
            String formatted = plugin.getEconomyBridge().format(totalEarnings);
            ui.set("#TotalEarnings.Text", "+" + formatted);
            ui.set("#ItemsSold.Text", totalItemsSold + " item" + (totalItemsSold != 1 ? "s" : "") + " sold");
        }
    }

    private void buildSalesList(UICommandBuilder ui) {
        int totalNotifications = notifications.size();
        int startIndex = currentPage * SALES_PER_PAGE;

        for (int i = 0; i < SALES_PER_PAGE; i++) {
            String prefix = "#Sale" + i;
            int actualIndex = startIndex + i;

            if (actualIndex < totalNotifications) {
                ShopDatabase.SaleNotification sale = notifications.get(actualIndex);
                ui.set(prefix + ".Visible", true);

                // Item icon
                ui.set(prefix + " #SaleIcon.ItemId", sale.itemId);

                // Buyer name
                ui.set(prefix + " #SaleBuyer.Text", sale.buyerName);

                // Detail text
                String itemName = formatItemName(sale.itemId);
                String detail = "bought " + itemName;
                if (sale.quantity > 1) {
                    detail += " x" + sale.quantity;
                }
                ui.set(prefix + " #SaleDetail.Text", detail);

                // Price
                String priceFormatted = plugin.getEconomyBridge().format(sale.earned);
                ui.set(prefix + " #SalePrice.Text", "+" + priceFormatted);

                // Time
                ui.set(prefix + " #SaleTime.Text", formatRelativeTime(sale.timestamp));
            } else {
                ui.set(prefix + ".Visible", false);
            }
        }
    }

    private void buildPagination(UICommandBuilder ui) {
        int totalPages = getTotalPages();

        // Clamp current page
        if (currentPage >= totalPages) currentPage = Math.max(0, totalPages - 1);

        ui.set("#NotifPageInfo.Text", (currentPage + 1) + "/" + Math.max(1, totalPages));
        ui.set("#NotifPrev.Visible", currentPage > 0);
        ui.set("#NotifNext.Visible", currentPage < totalPages - 1);
    }

    // ==================== HELPERS ====================

    private int getTotalPages() {
        int total = (notifications != null) ? notifications.size() : 0;
        return Math.max(1, (int) Math.ceil(total / (double) SALES_PER_PAGE));
    }

    /**
     * Converts an item ID like "Ingredient_Bar_Gold" to "Ingredient Bar Gold".
     */
    private String formatItemName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "";
        return itemId.replace('_', ' ');
    }

    /**
     * Formats a timestamp into a relative time string.
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

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[ShopNotifications] onDismiss error: " + e.getMessage());
        }
        LOGGER.fine("[ShopNotifications] Page dismissed for " + playerRef.getUsername());
    }

    // ==================== EVENT DATA CODEC ====================

    public static class NotificationData {
        public static final BuilderCodec<NotificationData> CODEC = BuilderCodec
            .<NotificationData>builder(NotificationData.class, NotificationData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .build();

        private String button;

        public NotificationData() {}
    }
}
