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
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.rental.RentalService;
import com.kyuubisoft.shops.rental.RentalSlotData;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Full auction bidding page opened when a player F-keys a vacant
 * AUCTION-mode rental slot. Shows a live countdown, the current high
 * bid + bidder, the last 6 bids, a bid slider, and PLACE BID / CLOSE
 * buttons. Registered with {@link RentalService} so the live-countdown
 * tick can poke {@link #refreshFromTick} every 2s to keep watchers in
 * sync with other bidders.
 */
public class RentalBidPage extends InteractiveCustomUIPage<RentalBidPage.BidData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private static final int BID_HISTORY_ROWS = 6;

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;
    private final UUID slotId;

    /** Staged bid slider value — starts at the minimum required bid. */
    private int bidAmount;

    /**
     * Cached last-seen auction end timestamp so the tick path can detect
     * when the auction closes underneath an open page and the bid button
     * should grey out.
     */
    private volatile long lastKnownAuctionEnd;

    public RentalBidPage(PlayerRef playerRef, Player player, ShopPlugin plugin, RentalSlotData slot) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, BidData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.slotId = slot.getId();
        this.bidAmount = nextMinimumBid(slot);
        this.lastKnownAuctionEnd = slot.getAuctionEndsAt();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/RentalBid.ui");
        bindEvents(events);
        buildUI(ui);
        plugin.getRentalService().registerOpenBidPage(playerRef.getUuid(), this);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull BidData data) {
        super.handleDataEvent(ref, store, data);

        if (data.bidValue != null) {
            RentalSlotData slot = plugin.getRentalService().getSlot(slotId);
            if (slot == null) {
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }
            int min = nextMinimumBid(slot);
            int max = bidSliderMax(slot);
            int clamped = Math.max(min, Math.min(max, data.bidValue));
            if (clamped != bidAmount) {
                bidAmount = clamped;
                refreshUI();
            } else {
                this.sendUpdate(new UICommandBuilder(), false);
            }
            return;
        }

        if (data.button != null) {
            switch (data.button) {
                case "bid" -> handleBid();
                case "close" -> handleClose();
                default -> this.sendUpdate(new UICommandBuilder(), false);
            }
            return;
        }
        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleBid() {
        ShopI18n i18n = plugin.getI18n();
        RentalService.BidResult result = plugin.getRentalService()
            .placeBid(playerRef, player, slotId, bidAmount);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Message.raw(
                    "Bid placed: " + bidAmount + " Gold"
                ).color("#55ff55"));
                refreshUI();
            }
            case NOT_AUCTION -> sendError("shop.rental.slot_not_found");
            case AUCTION_CLOSED -> sendError("shop.rental.auction_closed");
            case NO_PERMISSION -> sendError("shop.error.no_permission");
            case BID_TOO_LOW -> sendError("shop.rental.bid_too_low");
            case NOT_ENOUGH_FUNDS -> sendError("shop.rental.not_enough_funds");
            case ECONOMY_UNAVAILABLE -> sendError("shop.create.economy_unavailable");
            case OWN_BID -> sendError("shop.rental.own_bid");
            case SLOT_NOT_FOUND -> sendError("shop.rental.slot_not_found");
            default -> sendError("shop.rental.rent_failed");
        }
    }

    private void sendError(String key) {
        player.sendMessage(Message.raw(
            plugin.getI18n().get(playerRef, key)
        ).color("#FF5555"));
        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleClose() {
        try { this.close(); }
        catch (Exception e) {
            LOGGER.warning("[RentalBid] close failed: " + e.getMessage());
        }
    }

    /**
     * Live-countdown tick entry point. Called from
     * {@link RentalService#tickOpenPages()} every 2s. Rebuilds the UI
     * with the latest slot state so the countdown + high bid stay in
     * sync with other players' bids.
     */
    public void refreshFromTick() {
        RentalSlotData slot = plugin.getRentalService().getSlot(slotId);
        if (slot == null) return;
        // Auction state may have flipped to RENTED underneath us —
        // refresh once more so the UI reflects the close, then the
        // player can manually dismiss.
        refreshUI();
        this.lastKnownAuctionEnd = slot.getAuctionEndsAt();
    }

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindEvents(events);
        buildUI(ui);
        this.sendUpdate(ui, events, false);
    }

    private void bindEvents(UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#BidSlider",
            EventData.of("@Bid", "#BidSlider.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PlaceBidBtn",
            EventData.of("Button", "bid"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn",
            EventData.of("Button", "close"), false);
    }

    private void buildUI(UICommandBuilder ui) {
        RentalSlotData slot = plugin.getRentalService().getSlot(slotId);
        if (slot == null) {
            ui.set("#SlotName.Text", "(slot removed)");
            return;
        }

        ui.set("#SlotName.Text", slot.getDisplayName());

        // --- Countdown ---
        long auctionEndsAt = slot.getAuctionEndsAt();
        long now = System.currentTimeMillis();
        if (auctionEndsAt <= 0) {
            // No auction round currently armed (e.g. just expired, awaiting
            // restart by the scheduler tick or admin re-arm). Show that
            // explicitly instead of "0s" which reads like a frozen UI.
            ui.set("#CountdownValue.Text", "Auction not active");
        } else if (auctionEndsAt <= now) {
            ui.set("#CountdownValue.Text", "Closing...");
        } else {
            long remaining = auctionEndsAt - now;
            ui.set("#CountdownValue.Text", RentalService.formatRemainingTime(remaining));
        }

        // --- Rental period if won ---
        // Tell the bidder how long the slot will be theirs after winning.
        // Uses slot.getMaxDays() because that's the contract: winner pays
        // the high bid, gets exactly maxDays of rental.
        ui.set("#RentalPeriodValue.Text",
            slot.getMaxDays() + " day(s) if you win");

        // --- Current high bid ---
        int highBid = slot.getCurrentHighBid();
        if (highBid <= 0) {
            ui.set("#HighBidValue.Text", slot.getMinBid() + " Gold (min)");
            ui.set("#HighBidderValue.Text", "-- no bids yet --");
        } else {
            ui.set("#HighBidValue.Text", highBid + " Gold");
            ui.set("#HighBidderValue.Text",
                slot.getCurrentHighBidderName() != null
                    ? slot.getCurrentHighBidderName()
                    : "Unknown");
        }

        // --- Player status row ---
        UUID me = playerRef.getUuid();
        if (me.equals(slot.getCurrentHighBidder())) {
            ui.set("#StatusValue.Text", "You are the high bidder!");
        } else if (slot.getCurrentHighBidder() == null) {
            ui.set("#StatusValue.Text", "No bid placed yet");
        } else {
            ui.set("#StatusValue.Text", "You have been outbid");
        }

        // --- Balance check ---
        double balance = 0;
        try {
            balance = plugin.getEconomyBridge().getBalance(me);
        } catch (Exception ignored) {}
        ui.set("#BalanceValue.Text", ((int) balance) + " Gold");

        // --- Minimum required + slider ---
        int minRequired = nextMinimumBid(slot);
        int sliderMax = bidSliderMax(slot);
        if (bidAmount < minRequired) bidAmount = minRequired;
        if (bidAmount > sliderMax) bidAmount = sliderMax;
        ui.set("#MinBidValue.Text", minRequired + " Gold");
        ui.set("#BidValueLabel.Text", bidAmount + " Gold");
        ui.set("#BidSlider.Value", bidAmount);

        // --- Bid history (last 6) ---
        ShopDatabase db = plugin.getDatabase();
        List<ShopDatabase.RentalBidRecord> history = db.loadRecentBids(slotId, BID_HISTORY_ROWS);
        for (int i = 0; i < BID_HISTORY_ROWS; i++) {
            String rowId = "#BidHistoryRow" + i;
            if (i < history.size()) {
                ShopDatabase.RentalBidRecord rec = history.get(i);
                long age = Math.max(0, now - rec.timestamp);
                String ageStr = RentalService.formatRemainingTime(age) + " ago";
                ui.set(rowId + ".Visible", true);
                ui.set(rowId + " #BidderCell.Text", rec.bidderName);
                ui.set(rowId + " #AmountCell.Text", rec.amount + " Gold");
                ui.set(rowId + " #TimeCell.Text", ageStr);
            } else {
                ui.set(rowId + ".Visible", false);
            }
        }

        // --- Empty state ---
        if (history.isEmpty()) {
            ui.set("#EmptyHistory.Visible", true);
        } else {
            ui.set("#EmptyHistory.Visible", false);
        }
    }

    private int nextMinimumBid(RentalSlotData slot) {
        if (slot.getCurrentHighBidder() == null) {
            return slot.getMinBid();
        }
        return slot.getCurrentHighBid() + slot.getBidIncrement();
    }

    /**
     * Returns the upper bound of the bid slider. Capped at
     * {@code max(minRequired * 10, 10000)} so the range stays usable
     * even for cheap slots.
     */
    private int bidSliderMax(RentalSlotData slot) {
        int min = nextMinimumBid(slot);
        return Math.max(min * 10, 10_000);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            plugin.getRentalService().unregisterOpenBidPage(playerRef.getUuid());
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[RentalBid] onDismiss error: " + e.getMessage());
        }
    }

    // ==================== EVENT DATA CODEC ====================

    public static class BidData {
        public static final BuilderCodec<BidData> CODEC = BuilderCodec
            .<BidData>builder(BidData.class, BidData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .addField(new KeyedCodec<>("@Bid", Codec.INTEGER),
                (data, value) -> data.bidValue = value,
                data -> data.bidValue)
            .build();

        private String button;
        private Integer bidValue;

        public BidData() {}
    }
}
