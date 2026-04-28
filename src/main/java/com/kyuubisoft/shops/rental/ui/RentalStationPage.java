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
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.rental.RentalService;
import com.kyuubisoft.shops.rental.RentalSlotData;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Multi-slot station browse page. Opened via
 * {@code /ksshop rentalstations} — lists every rental slot in the
 * caller's current world, shows mode badge, state, and a per-row
 * RENT / BID / OCCUPIED action button. Clicking an action routes to
 * {@link RentalRentConfirmPage} / {@link RentalBidPage} for that slot.
 */
public class RentalStationPage extends InteractiveCustomUIPage<RentalStationPage.StationData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final int ROWS_PER_PAGE = 6;

    private enum Tab { ALL, VACANT, AUCTION, RENTED }

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;
    private final String worldName;
    private Tab activeTab = Tab.ALL;
    private int pageIndex = 0;

    /** Slot UUIDs by row index from the last {@link #buildUI} call. */
    private final List<UUID> rowSlotIds = new ArrayList<>();

    public RentalStationPage(PlayerRef playerRef, Player player, ShopPlugin plugin, String worldName) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, StationData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.worldName = worldName;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/RentalStation.ui");
        bindEvents(events);
        buildUI(ui);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull StationData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // Tab switching
        if (data.action.startsWith("tab_")) {
            try { activeTab = Tab.valueOf(data.action.substring(4)); }
            catch (IllegalArgumentException ignored) { activeTab = Tab.ALL; }
            pageIndex = 0;
            refreshUI();
            return;
        }

        // Row actions
        if (data.action.startsWith("open_")) {
            int row;
            try { row = Integer.parseInt(data.action.substring(5)); }
            catch (NumberFormatException e) {
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }
            if (row < 0 || row >= rowSlotIds.size()) {
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }
            handleSlotAction(ref, store, rowSlotIds.get(row));
            return;
        }

        switch (data.action) {
            case "next" -> {
                pageIndex++;
                refreshUI();
            }
            case "prev" -> {
                if (pageIndex > 0) pageIndex--;
                refreshUI();
            }
            case "close" -> {
                try { this.close(); }
                catch (Exception e) {
                    LOGGER.warning("[RentalStation] close failed: " + e.getMessage());
                }
            }
            default -> this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    private void handleSlotAction(Ref<EntityStore> ref, Store<EntityStore> store, UUID slotId) {
        RentalSlotData slot = plugin.getRentalService().getSlot(slotId);
        if (slot == null) {
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        if (slot.getRentedBy() != null) {
            player.sendMessage(Message.raw(
                "Slot is already rented."
            ).color("#FFD700"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        try {
            this.close();
        } catch (Exception ignored) {}

        try {
            if (slot.getMode() == RentalSlotData.Mode.AUCTION) {
                RentalBidPage bidPage = new RentalBidPage(playerRef, player, plugin, slot);
                player.getPageManager().openCustomPage(ref, store, bidPage);
            } else {
                RentalRentConfirmPage confirm = new RentalRentConfirmPage(
                    playerRef, player, plugin, slot);
                player.getPageManager().openCustomPage(ref, store, confirm);
            }
        } catch (Exception e) {
            LOGGER.warning("[RentalStation] failed to open sub-page: " + e.getMessage());
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
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabAll",
            EventData.of("Action", "tab_ALL"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabVacant",
            EventData.of("Action", "tab_VACANT"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabAuction",
            EventData.of("Action", "tab_AUCTION"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabRented",
            EventData.of("Action", "tab_RENTED"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevBtn",
            EventData.of("Action", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextBtn",
            EventData.of("Action", "next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn",
            EventData.of("Action", "close"), false);

        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                "#StationRow" + i + " #ActionBtn",
                EventData.of("Action", "open_" + i), false);
        }
    }

    private void buildUI(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();
        ui.set("#TitleText.Text", i18n.get(playerRef, "shop.rental.station.title"));

        // Build filtered + sorted list.
        List<RentalSlotData> filtered = new ArrayList<>();
        for (RentalSlotData slot : plugin.getRentalService().getAllSlots()) {
            if (!worldName.equals(slot.getWorldName())) continue;
            if (!matchesTab(slot)) continue;
            filtered.add(slot);
        }
        filtered.sort(Comparator.comparing(RentalSlotData::getDisplayName,
            String.CASE_INSENSITIVE_ORDER));

        int totalPages = Math.max(1, (filtered.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        if (pageIndex >= totalPages) pageIndex = totalPages - 1;
        int start = pageIndex * ROWS_PER_PAGE;

        ui.set("#PageLabel.Text", "Page " + (pageIndex + 1) + " / " + totalPages);

        rowSlotIds.clear();
        long now = System.currentTimeMillis();
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            String rowId = "#StationRow" + i;
            int idx = start + i;
            if (idx >= filtered.size()) {
                ui.set(rowId + ".Visible", false);
                continue;
            }
            RentalSlotData slot = filtered.get(idx);
            rowSlotIds.add(slot.getId());
            ui.set(rowId + ".Visible", true);
            ui.set(rowId + " #NameCell.Text", slot.getDisplayName());
            ui.set(rowId + " #ModeCell.Text", slot.getMode().name());

            String stateText;
            String actionLabel;
            if (slot.getRentedBy() != null) {
                long remaining = Math.max(0, slot.getRentedUntil() - now);
                stateText = "Rented by " + slot.getRentedByName() + " ("
                    + RentalService.formatRemainingTime(remaining) + " left)";
                actionLabel = i18n.get(playerRef, "shop.rental.station.action_occupied");
            } else if (slot.getMode() == RentalSlotData.Mode.AUCTION && slot.isAuctionOpen()) {
                long remaining = Math.max(0, slot.getAuctionEndsAt() - now);
                int bid = slot.getCurrentHighBid() > 0
                    ? slot.getCurrentHighBid()
                    : slot.getMinBid();
                stateText = bid + " Gold - ends " + RentalService.formatRemainingTime(remaining);
                actionLabel = i18n.get(playerRef, "shop.rental.station.action_bid");
            } else {
                stateText = slot.getMode() == RentalSlotData.Mode.AUCTION
                    ? "Auction idle"
                    : slot.getPricePerDay() + " Gold/day";
                actionLabel = i18n.get(playerRef, "shop.rental.station.action_rent");
            }
            ui.set(rowId + " #StateCell.Text", stateText);
            ui.set(rowId + " #ActionBtn.Text", actionLabel);
        }

        if (filtered.isEmpty()) {
            ui.set("#EmptyLabel.Visible", true);
            ui.set("#EmptyLabel.Text", i18n.get(playerRef, "shop.rental.station.empty"));
        } else {
            ui.set("#EmptyLabel.Visible", false);
        }
    }

    private boolean matchesTab(RentalSlotData slot) {
        switch (activeTab) {
            case VACANT -> {
                return slot.getRentedBy() == null
                    && (slot.getMode() == RentalSlotData.Mode.FIXED
                        || !slot.isAuctionOpen());
            }
            case AUCTION -> {
                return slot.getMode() == RentalSlotData.Mode.AUCTION
                    && slot.isAuctionOpen();
            }
            case RENTED -> {
                return slot.getRentedBy() != null;
            }
            default -> {
                return true;
            }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try { super.onDismiss(ref, store); }
        catch (Exception e) {
            LOGGER.warning("[RentalStation] onDismiss error: " + e.getMessage());
        }
    }

    // ==================== EVENT DATA CODEC ====================

    public static class StationData {
        public static final BuilderCodec<StationData> CODEC = BuilderCodec
            .<StationData>builder(StationData.class, StationData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action)
            .build();

        private String action;

        public StationData() {}
    }
}
