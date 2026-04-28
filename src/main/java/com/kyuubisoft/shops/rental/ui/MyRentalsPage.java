package com.kyuubisoft.shops.rental.ui;

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
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.rental.RentalService;
import com.kyuubisoft.shops.rental.RentalSlotData;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Player self-service UI for active rentals. Shows up to 6 rows with
 * slot name, time remaining, item count, and per-row EXTEND / RELEASE
 * buttons. Empty state links to the station browser (command-only for
 * now). Registered with {@link RentalService} so the countdown ticks.
 */
public class MyRentalsPage extends InteractiveCustomUIPage<MyRentalsPage.MyRentalsData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final int MAX_ROWS = 6;

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;

    /** Slot UUIDs by row index from the last {@link #buildUI} call. */
    private final java.util.List<UUID> rowSlotIds = new java.util.ArrayList<>();

    public MyRentalsPage(PlayerRef playerRef, Player player, ShopPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, MyRentalsData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/MyRentals.ui");
        bindEvents(events);
        buildUI(ui);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull MyRentalsData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null) {
            if ("close".equals(data.action)) {
                try { this.close(); }
                catch (Exception e) {
                    LOGGER.warning("[MyRentals] close failed: " + e.getMessage());
                }
                return;
            }
            if (data.action.startsWith("extend_") || data.action.startsWith("release_")) {
                int sep = data.action.indexOf('_');
                String verb = data.action.substring(0, sep);
                int row;
                try { row = Integer.parseInt(data.action.substring(sep + 1)); }
                catch (NumberFormatException e) {
                    this.sendUpdate(new UICommandBuilder(), false);
                    return;
                }
                if (row < 0 || row >= rowSlotIds.size()) {
                    this.sendUpdate(new UICommandBuilder(), false);
                    return;
                }
                UUID slotId = rowSlotIds.get(row);
                if ("extend".equals(verb)) handleExtend(slotId);
                else handleRelease(slotId);
                return;
            }
        }

        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleExtend(UUID slotId) {
        ShopI18n i18n = plugin.getI18n();
        // Default: extend by 1 day. A fancier UI would prompt for days.
        var result = plugin.getRentalService().extendRental(playerRef, player, slotId, 1);
        if (result == RentalService.RentResult.SUCCESS) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.rental.extend_success", "1",
                    String.valueOf(java.time.LocalDate.now().plusDays(1)))
            ).color("#55ff55"));
            refreshUI();
        } else {
            String key = switch (result) {
                case NOT_ENOUGH_FUNDS -> "shop.rental.not_enough_funds";
                case ECONOMY_UNAVAILABLE -> "shop.create.economy_unavailable";
                case INVALID_DAYS -> "shop.rental.invalid_days";
                case SLOT_NOT_FOUND -> "shop.rental.slot_not_found";
                default -> "shop.rental.extend_failed";
            };
            player.sendMessage(Message.raw(i18n.get(playerRef, key)).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    private void handleRelease(UUID slotId) {
        ShopI18n i18n = plugin.getI18n();
        var slot = plugin.getRentalService().getSlot(slotId);
        String name = slot != null ? slot.getDisplayName() : slotId.toString();
        boolean ok = plugin.getRentalService().releaseEarly(playerRef, slotId);
        if (ok) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.rental.released_chat", name)
            ).color("#55ff55"));
            refreshUI();
        } else {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.rental.rent_failed")
            ).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindEvents(events);
        buildUI(ui);
        this.sendUpdate(ui, events, false);
    }

    private void bindEvents(UIEventBuilder events) {
        for (int i = 0; i < MAX_ROWS; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                "#Row" + i + " #ExtendBtn",
                EventData.of("Action", "extend_" + i), false);
            events.addEventBinding(CustomUIEventBindingType.Activating,
                "#Row" + i + " #ReleaseBtn",
                EventData.of("Action", "release_" + i), false);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn",
            EventData.of("Action", "close"), false);
    }

    private void buildUI(UICommandBuilder ui) {
        List<RentalSlotData> rentals = plugin.getRentalService()
            .getSlotsForRenter(playerRef.getUuid());

        rowSlotIds.clear();

        if (rentals.isEmpty()) {
            ui.set("#EmptyState.Visible", true);
            for (int i = 0; i < MAX_ROWS; i++) {
                ui.set("#Row" + i + ".Visible", false);
            }
            return;
        }

        ui.set("#EmptyState.Visible", false);

        long now = System.currentTimeMillis();
        for (int i = 0; i < MAX_ROWS; i++) {
            String rowId = "#Row" + i;
            if (i < rentals.size()) {
                RentalSlotData slot = rentals.get(i);
                rowSlotIds.add(slot.getId());
                ShopData shop = slot.getRentedShopId() != null
                    ? plugin.getShopManager().getShop(slot.getRentedShopId())
                    : null;
                long remaining = Math.max(0, slot.getRentedUntil() - now);
                String remainingStr = RentalService.formatRemainingTime(remaining);
                int itemCount = shop != null ? shop.getItems().size() : 0;
                double revenue = shop != null ? shop.getTotalRevenue() : 0;

                ui.set(rowId + ".Visible", true);
                ui.set(rowId + " #NameCell.Text", slot.getDisplayName());
                ui.set(rowId + " #ExpiresCell.Text", remainingStr + " left");
                ui.set(rowId + " #ItemsCell.Text", itemCount + " items");
                ui.set(rowId + " #RevenueCell.Text", ((int) revenue) + " Gold earned");
            } else {
                ui.set(rowId + ".Visible", false);
            }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try { super.onDismiss(ref, store); }
        catch (Exception e) {
            LOGGER.warning("[MyRentals] onDismiss error: " + e.getMessage());
        }
    }

    // ==================== EVENT DATA CODEC ====================

    public static class MyRentalsData {
        public static final BuilderCodec<MyRentalsData> CODEC = BuilderCodec
            .<MyRentalsData>builder(MyRentalsData.class, MyRentalsData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action)
            .build();

        private String action;

        public MyRentalsData() {}
    }
}
