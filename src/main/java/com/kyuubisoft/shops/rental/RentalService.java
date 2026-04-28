package com.kyuubisoft.shops.rental;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.hypixel.hytale.server.core.Message;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.ShopItem;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.mailbox.MailboxService;
import com.kyuubisoft.shops.rental.event.RentalAuctionWonEvent;
import com.kyuubisoft.shops.rental.event.RentalBidPlacedEvent;
import com.kyuubisoft.shops.rental.event.RentalExpiredEvent;
import com.kyuubisoft.shops.rental.event.RentalStartedEvent;
import com.kyuubisoft.shops.rental.ui.RentalBidPage;
import com.kyuubisoft.shops.event.ShopEventBus;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.ShopManager;
import com.kyuubisoft.shops.util.PermissionLimits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Core logic for the rental-station feature. Owns the
 * {@link RentalSlotData} cache and serialises all mutation paths
 * (rent / release / expire / bid) per-slot via {@link #slotLocks}.
 *
 * <p>Phase 1 focuses on FIXED-price rentals: admin creates slots via
 * {@code /kssa createrental}, players interact with the slot NPC and
 * open a confirm dialog, and the expiry tick returns their items to
 * the mailbox when the rental window closes. Auction finalize lives
 * in Phase 2 (see {@link RentalExpiryTask}).
 */
public class RentalService {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private final ShopPlugin plugin;
    private final ShopConfig config;
    private final ShopManager shopManager;
    private final ShopDatabase database;
    private final MailboxService mailboxService;

    private final ConcurrentHashMap<UUID, RentalSlotData> slots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> slotLocks = new ConcurrentHashMap<>();

    /**
     * Open {@link RentalBidPage} instances keyed by viewer UUID. The
     * live-countdown tick iterates this map and calls {@code refreshFromTick}
     * so watchers see new bids / time-remaining updates without polling.
     */
    private final ConcurrentHashMap<UUID, RentalBidPage> openBidPages = new ConcurrentHashMap<>();

    public RentalService(ShopPlugin plugin,
                         ShopConfig config,
                         ShopManager shopManager,
                         ShopDatabase database,
                         MailboxService mailboxService) {
        this.plugin = plugin;
        this.config = config;
        this.shopManager = shopManager;
        this.database = database;
        this.mailboxService = mailboxService;
    }

    // ==================== LIFECYCLE ====================

    public void loadAll() {
        slots.clear();
        List<RentalSlotData> loaded = database.loadAllRentalSlots();
        for (RentalSlotData slot : loaded) {
            slots.put(slot.getId(), slot);
        }
        LOGGER.info("Loaded " + slots.size() + " rental slot(s)");
    }

    public void saveDirty() {
        int saved = 0;
        for (RentalSlotData slot : slots.values()) {
            if (slot.isDirty()) {
                database.saveRentalSlot(slot);
                saved++;
            }
        }
        if (saved > 0) LOGGER.fine("Saved " + saved + " dirty rental slot(s)");
    }

    // ==================== QUERIES ====================

    public RentalSlotData getSlot(UUID slotId) {
        return slotId == null ? null : slots.get(slotId);
    }

    public Collection<RentalSlotData> getAllSlots() {
        return Collections.unmodifiableCollection(slots.values());
    }

    public List<RentalSlotData> getSlotsForRenter(UUID renterUuid) {
        List<RentalSlotData> result = new ArrayList<>();
        if (renterUuid == null) return result;
        for (RentalSlotData slot : slots.values()) {
            if (renterUuid.equals(slot.getRentedBy())) {
                result.add(slot);
            }
        }
        return result;
    }

    public int countActiveRentalsFor(UUID renterUuid) {
        if (renterUuid == null) return 0;
        int count = 0;
        for (RentalSlotData slot : slots.values()) {
            if (renterUuid.equals(slot.getRentedBy()) && slot.getRentedUntil() > 0L) {
                count++;
            }
        }
        return count;
    }

    // ==================== ADMIN: CREATE / DELETE ====================

    /**
     * Creates a new FIXED-price rental slot at the given position,
     * persists it, and creates a "vacant shell" {@link ShopData} row
     * (type ADMIN, no owner) so the NPC system can track + spawn the
     * vacant-slot NPC automatically. The caller must dispatch the NPC
     * spawn to the world thread (see {@link #spawnVacantNpc}).
     */
    public RentalSlotData createFixedSlot(String displayName, String worldName,
                                          double x, double y, double z, float rotY,
                                          int pricePerDay, int maxDays, String stationId) {
        RentalSlotData slot = new RentalSlotData();
        slot.setWorldName(worldName);
        slot.setPosX(x);
        slot.setPosY(y);
        slot.setPosZ(z);
        slot.setNpcRotY(rotY);
        slot.setDisplayName(displayName);
        slot.setStationId(stationId);
        slot.setMode(RentalSlotData.Mode.FIXED);
        slot.setPricePerDay(Math.max(0, pricePerDay));
        slot.setMaxDays(Math.max(1, maxDays));
        database.saveRentalSlot(slot);
        slots.put(slot.getId(), slot);

        // Create the vacant shell ShopData so the NPC spawns on world load.
        createVacantShellShop(slot);

        LOGGER.info("Created FIXED rental slot '" + displayName + "' at " + worldName
            + " [" + (int) x + "," + (int) y + "," + (int) z + "] - "
            + pricePerDay + "g/day, max " + maxDays + "d");
        return slot;
    }

    /**
     * Deletes a slot entirely. If it's currently rented, the renter gets
     * a full mailbox refund first via the normal expiry path.
     */
    public boolean deleteSlot(UUID slotId) {
        RentalSlotData slot = slots.get(slotId);
        if (slot == null) return false;
        synchronized (lockFor(slotId)) {
            if (slot.getRentedBy() != null) {
                expireSlot(slot, RentalExpiredEvent.Reason.FORCE_EXPIRED);
            }
            // Delete the vacant shell shop + despawn NPC (if not already
            // cleaned up by expireSlot replacing the shell with a renter shop).
            deleteVacantShellShop(slot);
            database.deleteRentalSlot(slotId);
            database.deleteBidsForSlot(slotId);
            slots.remove(slotId);
            slotLocks.remove(slotId);
        }
        LOGGER.info("Deleted rental slot " + slotId);
        return true;
    }

    /**
     * Creates a new AUCTION-mode rental slot at the given position and
     * immediately starts the first auction. The slot is persisted
     * immediately; the vacant NPC is spawned by the caller after the
     * return (on the world thread).
     */
    public RentalSlotData createAuctionSlot(String displayName, String worldName,
                                            double x, double y, double z, float rotY,
                                            int minBid, int bidIncrement,
                                            int auctionDurationMinutes, int rentalDays,
                                            String stationId) {
        RentalSlotData slot = new RentalSlotData();
        slot.setWorldName(worldName);
        slot.setPosX(x);
        slot.setPosY(y);
        slot.setPosZ(z);
        slot.setNpcRotY(rotY);
        slot.setDisplayName(displayName);
        slot.setStationId(stationId);
        slot.setMode(RentalSlotData.Mode.AUCTION);
        slot.setMinBid(Math.max(0, minBid));
        slot.setBidIncrement(Math.max(1, bidIncrement));
        slot.setAuctionDurationMinutes(Math.max(1, auctionDurationMinutes));
        slot.setMaxDays(Math.max(1, rentalDays));
        slot.setAuctionEndsAt(System.currentTimeMillis() + (long) auctionDurationMinutes * 60_000L);
        database.saveRentalSlot(slot);
        slots.put(slot.getId(), slot);

        createVacantShellShop(slot);

        LOGGER.info("Created AUCTION rental slot '" + displayName + "' at " + worldName
            + " [" + (int) x + "," + (int) y + "," + (int) z + "] - "
            + "min " + minBid + "g, +" + bidIncrement + "g, " + auctionDurationMinutes
            + "min duration, " + rentalDays + " rental days");
        return slot;
    }

    // ==================== PLAYER: RENT FIXED SLOT ====================

    public enum RentResult {
        SUCCESS,
        SLOT_NOT_FOUND,
        NOT_VACANT,
        LIMIT_REACHED,
        NO_PERMISSION,
        INVALID_DAYS,
        NOT_ENOUGH_FUNDS,
        ECONOMY_UNAVAILABLE,
        FAILED
    }

    public enum BidResult {
        SUCCESS,
        SLOT_NOT_FOUND,
        NOT_AUCTION,
        AUCTION_CLOSED,
        NO_PERMISSION,
        BID_TOO_LOW,
        NOT_ENOUGH_FUNDS,
        ECONOMY_UNAVAILABLE,
        OWN_BID,
        FAILED
    }

    /**
     * Rent a FIXED-price slot for {@code days} days. Withdraws the total
     * cost from the player, creates a backing {@link ShopData} row, links
     * the two, and updates {@link RentalSlotData} runtime fields. The
     * caller is responsible for spawning the NPC from the world thread.
     *
     * @return a typed {@link RentResult} describing success or failure
     */
    public RentResult rentSlot(PlayerRef renter, Player player, UUID slotId, int days) {
        if (renter == null || slotId == null) return RentResult.FAILED;
        RentalSlotData slot = slots.get(slotId);
        if (slot == null) return RentResult.SLOT_NOT_FOUND;
        if (slot.getMode() != RentalSlotData.Mode.FIXED) return RentResult.FAILED;

        if (player != null) {
            try {
                if (!player.hasPermission("ks.rental.rent", true)) {
                    return RentResult.NO_PERMISSION;
                }
            } catch (Exception ignored) {
            }
        }
        if (days < 1 || days > slot.getMaxDays()) return RentResult.INVALID_DAYS;

        ShopConfig.ConfigData cfg = config.getData();
        int rentalCap = PermissionLimits.resolveMaxRentals(player,
            cfg.rentalStations.maxConcurrentRentalsDefault);

        synchronized (lockFor(slotId)) {
            // Re-fetch inside the lock so concurrent renters race cleanly.
            RentalSlotData live = slots.get(slotId);
            if (live == null) return RentResult.SLOT_NOT_FOUND;
            if (!live.isVacant() || live.getRentedBy() != null) return RentResult.NOT_VACANT;

            if (countActiveRentalsFor(renter.getUuid()) >= rentalCap) {
                return RentResult.LIMIT_REACHED;
            }

            int totalCost = days * live.getPricePerDay();
            if (!plugin.getEconomyBridge().isAvailable()) {
                return RentResult.ECONOMY_UNAVAILABLE;
            }
            if (totalCost > 0) {
                if (!plugin.getEconomyBridge().has(renter.getUuid(), totalCost)) {
                    return RentResult.NOT_ENOUGH_FUNDS;
                }
                if (!plugin.getEconomyBridge().withdraw(renter.getUuid(), totalCost)) {
                    return RentResult.NOT_ENOUGH_FUNDS;
                }
            }

            long rentedUntil = System.currentTimeMillis() + (long) days * 86_400_000L;

            // Delete the vacant shell shop + despawn its NPC before
            // creating the renter's real shop at the same position.
            deleteVacantShellShop(live);

            // Build a backing ShopData — treated as a normal PLAYER shop so
            // all existing buy/sell/edit/NPC flows apply without forks.
            ShopData shop = new ShopData(
                live.getDisplayName(),
                "",
                ShopType.PLAYER,
                renter.getUuid(),
                renter.getUsername(),
                live.getWorldName(),
                live.getPosX(), live.getPosY(), live.getPosZ(),
                live.getNpcRotY()
            );
            shop.setRentalSlotId(live.getId());
            shop.setRentalExpiresAt(rentedUntil);
            // Rental-backed shops skip the listing fee when configured so
            // the rental price already covers directory visibility.
            if (cfg.rentalStations.rentalShopsListFree) {
                shop.setListedUntil(rentedUntil);
            }
            shopManager.createShop(shop);

            // Spawn the renter's NPC at the slot position (replaces the
            // vacant shell NPC that was just despawned above).
            spawnShopNpcFromPlayer(shop, player);

            live.setRentedBy(renter.getUuid());
            live.setRentedByName(renter.getUsername());
            live.setRentedShopId(shop.getId());
            live.setRentedUntil(rentedUntil);
            database.saveRentalSlot(live);
            live.clearDirty();

            ShopEventBus.getInstance().fire(new RentalStartedEvent(
                live.getId(), shop.getId(), renter.getUuid(), renter.getUsername(),
                live.getDisplayName(), rentedUntil, totalCost
            ));

            LOGGER.info("Rental started: '" + live.getDisplayName() + "' -> "
                + renter.getUsername() + " for " + days + "d, " + totalCost + "g");
            return RentResult.SUCCESS;
        }
    }

    /**
     * Extend an active rental by additional days. Same price/day as the
     * slot config. Clamped at {@code extendMaxDays} per call.
     */
    public RentResult extendRental(PlayerRef renter, Player player, UUID slotId, int days) {
        if (renter == null || slotId == null) return RentResult.FAILED;
        RentalSlotData slot = slots.get(slotId);
        if (slot == null) return RentResult.SLOT_NOT_FOUND;
        if (slot.getMode() != RentalSlotData.Mode.FIXED) return RentResult.FAILED;

        ShopConfig.ConfigData cfg = config.getData();
        int maxExtend = Math.max(1, cfg.rentalStations.extendMaxDays);
        if (days < 1 || days > maxExtend) return RentResult.INVALID_DAYS;

        synchronized (lockFor(slotId)) {
            RentalSlotData live = slots.get(slotId);
            if (live == null) return RentResult.SLOT_NOT_FOUND;
            if (live.getRentedBy() == null
                || !live.getRentedBy().equals(renter.getUuid())) {
                return RentResult.FAILED;
            }

            int totalCost = days * live.getPricePerDay();
            if (!plugin.getEconomyBridge().isAvailable()) {
                return RentResult.ECONOMY_UNAVAILABLE;
            }
            if (totalCost > 0) {
                if (!plugin.getEconomyBridge().has(renter.getUuid(), totalCost)) {
                    return RentResult.NOT_ENOUGH_FUNDS;
                }
                if (!plugin.getEconomyBridge().withdraw(renter.getUuid(), totalCost)) {
                    return RentResult.NOT_ENOUGH_FUNDS;
                }
            }

            long newExpiry = live.getRentedUntil() + (long) days * 86_400_000L;
            live.setRentedUntil(newExpiry);
            ShopData shop = shopManager.getShop(live.getRentedShopId());
            if (shop != null) {
                shop.setRentalExpiresAt(newExpiry);
                if (cfg.rentalStations.rentalShopsListFree) {
                    shop.setListedUntil(Math.max(shop.getListedUntil(), newExpiry));
                }
            }
            database.saveRentalSlot(live);

            LOGGER.info("Rental extended: '" + live.getDisplayName() + "' by "
                + days + "d (new expiry " + newExpiry + ")");
            return RentResult.SUCCESS;
        }
    }

    /**
     * Release a rental early. Items are mailed back, gold refund follows
     * {@code releaseEarlyGoldRefundFraction} config. Fires a
     * {@link RentalExpiredEvent} with reason {@code RELEASED_EARLY}.
     */
    public boolean releaseEarly(PlayerRef renter, UUID slotId) {
        if (renter == null || slotId == null) return false;
        RentalSlotData slot = slots.get(slotId);
        if (slot == null) return false;
        synchronized (lockFor(slotId)) {
            RentalSlotData live = slots.get(slotId);
            if (live == null) return false;
            if (live.getRentedBy() == null
                || !live.getRentedBy().equals(renter.getUuid())) {
                return false;
            }
            expireSlot(live, RentalExpiredEvent.Reason.RELEASED_EARLY);
            return true;
        }
    }

    // ==================== PLAYER: PLACE BID (AUCTION MODE) ====================

    /**
     * Place a bid on an AUCTION-mode slot. Money is NOT withdrawn at bid
     * time — the winner is charged at auction finalize. This avoids
     * refund-on-outbid gymnastics and lets bidders compete freely while
     * their gold stays liquid.
     *
     * <p>Anti-sniping: if the bid arrives inside the last
     * {@code auctionAntiSnipingSeconds} seconds, the auction end time is
     * bumped by the same amount. Capped once per tick to prevent runaway
     * extensions from spam bidding.
     *
     * @return a typed {@link BidResult} describing success or failure
     */
    public BidResult placeBid(PlayerRef bidder, Player player, UUID slotId, int amount) {
        if (bidder == null || slotId == null) return BidResult.FAILED;
        RentalSlotData slot = slots.get(slotId);
        if (slot == null) return BidResult.SLOT_NOT_FOUND;
        if (slot.getMode() != RentalSlotData.Mode.AUCTION) return BidResult.NOT_AUCTION;

        if (player != null) {
            try {
                if (!player.hasPermission("ks.rental.bid", true)) {
                    return BidResult.NO_PERMISSION;
                }
            } catch (Exception ignored) {
            }
        }

        ShopConfig.ConfigData cfg = config.getData();

        synchronized (lockFor(slotId)) {
            RentalSlotData live = slots.get(slotId);
            if (live == null) return BidResult.SLOT_NOT_FOUND;
            if (live.getMode() != RentalSlotData.Mode.AUCTION) return BidResult.NOT_AUCTION;
            if (live.getAuctionEndsAt() <= 0
                || System.currentTimeMillis() >= live.getAuctionEndsAt()) {
                return BidResult.AUCTION_CLOSED;
            }

            int minRequired = live.getCurrentHighBidder() == null
                ? live.getMinBid()
                : live.getCurrentHighBid() + live.getBidIncrement();
            if (amount < minRequired) return BidResult.BID_TOO_LOW;

            if (bidder.getUuid().equals(live.getCurrentHighBidder())) {
                return BidResult.OWN_BID;
            }

            if (!plugin.getEconomyBridge().isAvailable()) {
                return BidResult.ECONOMY_UNAVAILABLE;
            }
            // Affordability check only — no withdraw at bid time.
            if (!plugin.getEconomyBridge().has(bidder.getUuid(), amount)) {
                return BidResult.NOT_ENOUGH_FUNDS;
            }

            UUID previousHighBidder = live.getCurrentHighBidder();
            String previousHighBidderName = live.getCurrentHighBidderName();
            int previousHighBid = live.getCurrentHighBid();

            live.setCurrentHighBidder(bidder.getUuid());
            live.setCurrentHighBidderName(bidder.getUsername());
            live.setCurrentHighBid(amount);

            // Anti-sniping: bump auction end if we're in the warning window.
            int antiSnipe = cfg.rentalStations.auctionAntiSnipingSeconds;
            if (antiSnipe > 0) {
                long remaining = live.getAuctionEndsAt() - System.currentTimeMillis();
                if (remaining > 0 && remaining <= antiSnipe * 1000L) {
                    live.setAuctionEndsAt(live.getAuctionEndsAt() + antiSnipe * 1000L);
                    live.setEndingSoonBroadcast(false); // re-arm broadcast after extension
                    LOGGER.info("Auction '" + live.getDisplayName()
                        + "' extended by " + antiSnipe + "s (anti-sniping)");
                }
            }

            database.saveRentalSlot(live);
            database.insertRentalBid(
                live.getId(), bidder.getUuid(), bidder.getUsername(),
                amount, System.currentTimeMillis());
            live.clearDirty();

            ShopEventBus.getInstance().fire(new RentalBidPlacedEvent(
                live.getId(), live.getDisplayName(),
                bidder.getUuid(), bidder.getUsername(), amount,
                previousHighBidder, previousHighBidderName, previousHighBid,
                live.getAuctionEndsAt()
            ));

            // Outbid chat notification to the displaced bidder (if online).
            if (previousHighBidder != null
                && !previousHighBidder.equals(bidder.getUuid())) {
                sendChatMessage(previousHighBidder,
                    plugin.getI18n().get("shop.rental.auction_outbid",
                        live.getDisplayName(), amount),
                    "#ff9966");
            }

            LOGGER.info("Bid placed: " + bidder.getUsername() + " -> "
                + live.getDisplayName() + " (" + amount + "g)");
            return BidResult.SUCCESS;
        }
    }

    /**
     * Send a chat message to a player identified by UUID. Best-effort:
     * silently swallows if the player is offline / entity is gone. Used
     * for out-of-band notifications (outbid, auction won, ending soon).
     */
    private void sendChatMessage(UUID playerUuid, String text, String hexColor) {
        try {
            Player online = plugin.getOnlinePlayer(playerUuid);
            if (online != null) {
                online.sendMessage(Message.raw(text).color(hexColor));
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== EXPIRY ====================

    /**
     * End a rental: mail stocked items + shop balance back to the renter,
     * delete the backing ShopData, clear runtime state, persist. Safe to
     * call while holding {@link #slotLocks} for this slot; callers that
     * don't already hold the lock should acquire it first.
     */
    public void expireSlot(RentalSlotData slot, RentalExpiredEvent.Reason reason) {
        if (slot == null || slot.getRentedBy() == null) return;

        UUID renterUuid = slot.getRentedBy();
        String renterName = slot.getRentedByName();
        UUID shopId = slot.getRentedShopId();
        String displayName = slot.getDisplayName();

        int mailedItems = 0;
        double mailedBalance = 0;

        ShopData shop = shopId != null ? shopManager.getShop(shopId) : null;
        if (shop != null) {
            // --- Refund items via mailbox ---
            List<ShopItem> itemsCopy = new ArrayList<>(shop.getItems());
            for (ShopItem item : itemsCopy) {
                if (item == null || item.isUnlimitedStock()) continue;
                int stock = item.getStock();
                if (stock <= 0) continue;
                try {
                    mailboxService.createItemMail(
                        renterUuid, shopId, shop.getName(), "[Rental Expired]",
                        item.getItemId(), stock, item.getItemMetadata());
                    mailedItems += stock;
                } catch (Exception e) {
                    LOGGER.warning("expireSlot: failed to mail " + stock + "x "
                        + item.getItemId() + " to " + renterName + ": " + e.getMessage());
                }
            }
            // --- Refund shop balance ---
            double balance = shop.getShopBalance();
            if (balance > 0) {
                try {
                    mailboxService.createMoneyMail(
                        renterUuid, shopId, shop.getName(), "[Rental Expired]", balance);
                    mailedBalance = balance;
                    shop.setShopBalance(0);
                } catch (Exception e) {
                    LOGGER.warning("expireSlot: failed to mail balance to "
                        + renterName + ": " + e.getMessage());
                }
            }

            // --- Release-early partial gold refund ---
            if (reason == RentalExpiredEvent.Reason.RELEASED_EARLY) {
                double fraction = config.getData().rentalStations.releaseEarlyGoldRefundFraction;
                if (fraction > 0 && slot.getMode() == RentalSlotData.Mode.FIXED) {
                    long remaining = Math.max(0L, slot.getRentedUntil()
                        - System.currentTimeMillis());
                    long msPerDay = 86_400_000L;
                    double remainingDays = remaining / (double) msPerDay;
                    int refund = (int) Math.floor(
                        remainingDays * slot.getPricePerDay() * fraction);
                    if (refund > 0) {
                        try {
                            mailboxService.createMoneyMail(
                                renterUuid, shopId, shop.getName(),
                                "[Rental Refund]", refund);
                            mailedBalance += refund;
                        } catch (Exception e) {
                            LOGGER.warning("expireSlot: refund mail failed: " + e.getMessage());
                        }
                    }
                }
            }

            // --- Despawn + delete backing shop ---
            try { plugin.getNpcManager().despawnNpc(shopId); }
            catch (Exception e) {
                LOGGER.warning("expireSlot: despawn failed for " + shopId + ": " + e.getMessage());
            }
            try { shopManager.deleteShop(shopId); }
            catch (Exception e) {
                LOGGER.warning("expireSlot: deleteShop failed for " + shopId + ": " + e.getMessage());
            }
        }

        // --- Clear slot runtime state ---
        slot.clearRuntimeState();

        // --- Auto-arm a new auction round for AUCTION-mode slots ---
        // Without this an auction slot would sit idle ("Auction not active"
        // in the bid page) after every rental expiry until an admin
        // re-armed it manually. The onEmptyAuction config governs the
        // *no-bidder* path (handleEmptyAuction); the post-rental path
        // simply mirrors that behaviour for RESTART.
        if (slot.getMode() == RentalSlotData.Mode.AUCTION) {
            String onEmpty = config.getData().rentalStations.onEmptyAuction;
            if ("RESTART".equalsIgnoreCase(onEmpty) || onEmpty == null) {
                long newEnd = System.currentTimeMillis()
                    + (long) Math.max(1, slot.getAuctionDurationMinutes()) * 60_000L;
                slot.setAuctionEndsAt(newEnd);
                LOGGER.info("Auction '" + slot.getDisplayName()
                    + "' re-armed after rental expiry, new end: " + newEnd);
            }
        }

        database.saveRentalSlot(slot);
        slot.clearDirty();

        // --- Recreate the vacant shell shop + NPC so the slot is
        // visible again at its map position.
        createVacantShellShop(slot);

        ShopEventBus.getInstance().fire(new RentalExpiredEvent(
            slot.getId(), shopId, renterUuid, renterName,
            displayName, mailedItems, mailedBalance, reason
        ));

        LOGGER.info("Rental expired (" + reason + "): '" + displayName + "' - "
            + mailedItems + " items + " + mailedBalance + "g mailed to "
            + renterName);
    }

    /**
     * Scan for rental slots whose window has closed and run their expiry
     * or auction-finalize logic. Called from {@link RentalExpiryTask}
     * every tick interval.
     */
    public int scanExpired() {
        long now = System.currentTimeMillis();
        int processed = 0;
        for (Map.Entry<UUID, RentalSlotData> entry : slots.entrySet()) {
            RentalSlotData slot = entry.getValue();

            // --- Rental window closed ---
            if (slot.getRentedBy() != null && slot.getRentedUntil() > 0
                && slot.getRentedUntil() <= now) {
                synchronized (lockFor(slot.getId())) {
                    RentalSlotData live = slots.get(slot.getId());
                    if (live != null && live.isRentalExpired()) {
                        expireSlot(live, RentalExpiredEvent.Reason.EXPIRED);
                        processed++;
                    }
                }
                continue;
            }

            // --- Auction closed ---
            if (slot.getMode() == RentalSlotData.Mode.AUCTION
                && slot.getRentedBy() == null
                && slot.getAuctionEndsAt() > 0
                && slot.getAuctionEndsAt() <= now) {
                synchronized (lockFor(slot.getId())) {
                    RentalSlotData live = slots.get(slot.getId());
                    if (live != null
                        && live.getAuctionEndsAt() > 0
                        && live.getAuctionEndsAt() <= now
                        && live.getRentedBy() == null) {
                        finalizeAuction(live);
                        processed++;
                    }
                }
                continue;
            }

            // --- Ending-soon broadcast (auction only, not yet broadcast) ---
            if (slot.getMode() == RentalSlotData.Mode.AUCTION
                && slot.getAuctionEndsAt() > now
                && !slot.isEndingSoonBroadcast()) {
                int endingSoon = config.getData().rentalStations.auctionEndingSoonSeconds;
                if (endingSoon > 0) {
                    long remaining = slot.getAuctionEndsAt() - now;
                    if (remaining <= endingSoon * 1000L) {
                        broadcastEndingSoon(slot);
                    }
                }
            }
        }
        return processed;
    }

    /**
     * Close out an expired auction: charge the winner, start a rental,
     * fire the won event. If the winner can't pay (or no bids), fall
     * back to the config-driven {@code onEmptyAuction} behavior.
     *
     * <p>Caller MUST hold the slot lock.
     */
    private void finalizeAuction(RentalSlotData slot) {
        ShopConfig.ConfigData cfg = config.getData();
        String displayName = slot.getDisplayName();

        if (slot.getCurrentHighBidder() == null) {
            // No bids — fall back to onEmptyAuction behavior.
            handleEmptyAuction(slot);
            return;
        }

        UUID winnerUuid = slot.getCurrentHighBidder();
        String winnerName = slot.getCurrentHighBidderName();
        int winningBid = slot.getCurrentHighBid();

        // Attempt to charge the winner. Economy can fail if their wallet
        // dropped below the bid between bid-time and finalize.
        if (!plugin.getEconomyBridge().isAvailable()
            || !plugin.getEconomyBridge().withdraw(winnerUuid, winningBid)) {
            LOGGER.warning("Auction finalize: winner " + winnerName
                + " could not pay " + winningBid + "g for '" + displayName + "'");
            sendChatMessage(winnerUuid,
                "Your auction win was forfeited - insufficient funds for "
                + displayName, "#ff5555");
            // Clear auction state and restart per config.
            slot.setCurrentHighBidder(null);
            slot.setCurrentHighBidderName(null);
            slot.setCurrentHighBid(0);
            database.deleteBidsForSlot(slot.getId());
            restartAuction(slot);
            return;
        }

        long rentedUntil = System.currentTimeMillis()
            + (long) slot.getMaxDays() * 86_400_000L;

        // Delete the vacant shell shop + despawn NPC before creating
        // the winner's real shop at the same position.
        deleteVacantShellShop(slot);

        // Build the backing ShopData — same pattern as the FIXED rent path.
        ShopData shop = new ShopData(
            displayName,
            "",
            ShopType.PLAYER,
            winnerUuid,
            winnerName,
            slot.getWorldName(),
            slot.getPosX(), slot.getPosY(), slot.getPosZ(),
            slot.getNpcRotY()
        );
        shop.setRentalSlotId(slot.getId());
        shop.setRentalExpiresAt(rentedUntil);
        if (cfg.rentalStations.rentalShopsListFree) {
            shop.setListedUntil(rentedUntil);
        }
        shopManager.createShop(shop);

        // Spawn the winner's NPC. Use the online player map since the
        // finalize runs from the scheduler thread with no Player arg.
        Player winner = plugin.getOnlinePlayer(winnerUuid);
        spawnShopNpcFromPlayer(shop, winner);

        slot.setRentedBy(winnerUuid);
        slot.setRentedByName(winnerName);
        slot.setRentedShopId(shop.getId());
        slot.setRentedUntil(rentedUntil);
        slot.setAuctionEndsAt(0L);
        slot.setCurrentHighBidder(null);
        slot.setCurrentHighBidderName(null);
        slot.setCurrentHighBid(0);
        slot.setEndingSoonBroadcast(false);
        database.saveRentalSlot(slot);
        database.deleteBidsForSlot(slot.getId());
        slot.clearDirty();

        ShopEventBus.getInstance().fire(new RentalStartedEvent(
            slot.getId(), shop.getId(), winnerUuid, winnerName,
            displayName, rentedUntil, winningBid
        ));
        ShopEventBus.getInstance().fire(new RentalAuctionWonEvent(
            slot.getId(), shop.getId(), displayName,
            winnerUuid, winnerName, winningBid, rentedUntil
        ));

        sendChatMessage(winnerUuid,
            plugin.getI18n().get("shop.rental.auction_won",
                displayName, formatRemainingTime(rentedUntil - System.currentTimeMillis())),
            "#ffd700");

        LOGGER.info("Auction finalized: '" + displayName + "' -> " + winnerName
            + " for " + winningBid + "g (rental until " + rentedUntil + ")");
    }

    /**
     * Handle an auction that closed with zero bids. Falls back to the
     * {@code onEmptyAuction} config ("RESTART" / "VACANT" / "DELETE").
     * Caller MUST hold the slot lock.
     */
    private void handleEmptyAuction(RentalSlotData slot) {
        String action = config.getData().rentalStations.onEmptyAuction;
        if (action == null) action = "RESTART";
        switch (action.toUpperCase()) {
            case "DELETE" -> {
                database.deleteRentalSlot(slot.getId());
                database.deleteBidsForSlot(slot.getId());
                slots.remove(slot.getId());
                slotLocks.remove(slot.getId());
                LOGGER.info("Auction '" + slot.getDisplayName()
                    + "' closed with no bids — slot deleted per config");
            }
            case "VACANT" -> {
                slot.setAuctionEndsAt(0L);
                slot.setEndingSoonBroadcast(false);
                database.saveRentalSlot(slot);
                LOGGER.info("Auction '" + slot.getDisplayName()
                    + "' closed with no bids — slot idle per config");
            }
            default -> restartAuction(slot);
        }
    }

    /**
     * Restart a new auction round with the same duration. Caller MUST
     * hold the slot lock.
     */
    private void restartAuction(RentalSlotData slot) {
        long newEnd = System.currentTimeMillis()
            + (long) slot.getAuctionDurationMinutes() * 60_000L;
        slot.setAuctionEndsAt(newEnd);
        slot.setEndingSoonBroadcast(false);
        database.saveRentalSlot(slot);
        LOGGER.info("Auction '" + slot.getDisplayName()
            + "' restarted, new end: " + newEnd);
    }

    /**
     * Broadcast an "ending soon" chat message to every online player and
     * flip the slot's {@code endingSoonBroadcast} flag so it fires once.
     */
    private void broadcastEndingSoon(RentalSlotData slot) {
        long remaining = slot.getAuctionEndsAt() - System.currentTimeMillis();
        String msg = plugin.getI18n().get("shop.rental.auction_ending",
            slot.getDisplayName(), formatRemainingTime(remaining),
            slot.getCurrentHighBid());
        for (Player p : plugin.getOnlinePlayers().values()) {
            try {
                p.sendMessage(Message.raw(msg).color("#ffd700"));
            } catch (Exception ignored) {
            }
        }
        slot.setEndingSoonBroadcast(true);
        database.saveRentalSlot(slot);
    }

    /**
     * Format a millisecond duration as {@code "2h 34m"} or {@code "45s"}
     * for chat messages.
     */
    public static String formatRemainingTime(long ms) {
        if (ms <= 0) return "0s";
        long totalSec = ms / 1000L;
        long days = totalSec / 86400L;
        long hours = (totalSec % 86400L) / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    // ==================== LIVE COUNTDOWN TICK ====================

    /**
     * Register an open {@link RentalBidPage} so the live-countdown tick
     * can poke its {@code refreshFromTick()} method every 2s.
     */
    public void registerOpenBidPage(UUID viewerUuid, RentalBidPage page) {
        if (viewerUuid != null && page != null) {
            openBidPages.put(viewerUuid, page);
        }
    }

    /** Remove an open bid page (called from its {@code onDismiss}). */
    public void unregisterOpenBidPage(UUID viewerUuid) {
        if (viewerUuid != null) {
            openBidPages.remove(viewerUuid);
        }
    }

    /**
     * Runs refresh on every registered open bid page. Called every 2s
     * from the plugin scheduler.
     */
    public void tickOpenPages() {
        if (openBidPages.isEmpty()) return;
        for (Map.Entry<UUID, RentalBidPage> entry : openBidPages.entrySet()) {
            try {
                entry.getValue().refreshFromTick();
            } catch (Exception e) {
                LOGGER.fine("Open bid page refresh failed for "
                    + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    // ==================== NPC SPAWN HELPERS ====================

    /**
     * Spawn (or queue) a shop NPC for the given {@link ShopData} by
     * resolving the {@link com.hypixel.hytale.server.core.universe.world.World}
     * from the given player entity. If the player is null (auction win
     * while winner is offline), the NPC is registered for deferred spawn
     * on next world load.
     */
    private void spawnShopNpcFromPlayer(ShopData shop, Player player) {
        try {
            plugin.getNpcManager().registerShopInWorld(shop);
            if (player != null) {
                var world = player.getWorld();
                if (world != null) {
                    var pos = new com.hypixel.hytale.math.vector.Vector3d(
                        shop.getPosX(), shop.getPosY(), shop.getPosZ());
                    float rotY = shop.getNpcRotY();
                    world.execute(() ->
                        plugin.getNpcManager().spawnNpcAtPosition(shop, world, pos, rotY));
                    return;
                }
            }
            // No player / no world — NPC will spawn on next world init.
            LOGGER.fine("Deferred NPC spawn for rental shop " + shop.getId()
                + " (no world reference available)");
        } catch (Exception e) {
            LOGGER.warning("spawnShopNpcFromPlayer failed for " + shop.getId()
                + ": " + e.getMessage());
        }
    }

    // ==================== VACANT SHELL MANAGEMENT ====================

    /**
     * Build a name for the vacant shell's nameplate based on the slot's
     * mode and price so players immediately see what the NPC offers.
     */
    private String buildVacantName(RentalSlotData slot) {
        if (slot.getMode() == RentalSlotData.Mode.AUCTION) {
            int bid = slot.getCurrentHighBid() > 0
                ? slot.getCurrentHighBid()
                : slot.getMinBid();
            return slot.getDisplayName() + " [AUCTION " + bid + "g]";
        }
        return slot.getDisplayName() + " [" + slot.getPricePerDay() + "g/day]";
    }

    /**
     * Creates a "vacant shell" {@link ShopData} row (type ADMIN, no
     * owner, empty items, {@code rentalSlotId} set) and persists it via
     * {@link ShopManager#createShop}. The existing NPC spawn pipeline
     * then treats it like any other admin shop and spawns an interactable
     * NPC at the slot position on world load. The interaction handler
     * detects the {@code rentalSlotId} on the ShopData and routes to
     * the rent/bid page instead of the browse page.
     */
    private void createVacantShellShop(RentalSlotData slot) {
        ShopData shell = new ShopData(
            buildVacantName(slot),
            "",
            ShopType.ADMIN,
            null,
            null,
            slot.getWorldName(),
            slot.getPosX(), slot.getPosY(), slot.getPosZ(),
            slot.getNpcRotY()
        );
        shell.setRentalSlotId(slot.getId());
        shell.setListedUntil(0L); // don't show vacant shells in the shop directory
        shell.setOpen(false);     // not a real shop — no buy/sell
        shopManager.createShop(shell);
        LOGGER.fine("Created vacant shell shop " + shell.getId()
            + " for rental slot " + slot.getId());

        // Spawn the NPC live so the slot is visible immediately. Without this
        // the shell row sits in the DB but no entity exists in the world
        // until the next chunk/world reload - which means a force-expired
        // rental shows no NPC for the rest of the session. The
        // spawnNpcAtPosition path is idempotent (despawns any tracked NPC
        // for this shop id first), so the redundant initial-create spawn
        // call in /kssa createrental still works.
        try {
            plugin.getNpcManager().registerShopInWorld(shell);
            com.hypixel.hytale.server.core.universe.world.World world =
                resolveDefaultWorld();
            if (world != null) {
                final ShopData spawnTarget = shell;
                final com.hypixel.hytale.math.vector.Vector3d pos =
                    new com.hypixel.hytale.math.vector.Vector3d(
                        slot.getPosX(), slot.getPosY(), slot.getPosZ());
                final float rotY = slot.getNpcRotY();
                world.execute(() ->
                    plugin.getNpcManager().spawnNpcAtPosition(
                        spawnTarget, world, pos, rotY));
            } else {
                LOGGER.fine("createVacantShellShop: no world resolved, "
                    + "deferring NPC spawn to next world init");
            }
        } catch (Exception e) {
            LOGGER.warning("createVacantShellShop: NPC respawn failed for "
                + slot.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Resolves the default world via the Hytale Universe singleton. Used
     * by {@link #createVacantShellShop} so the live-respawn path after a
     * rental expires can dispatch onto the world thread without needing a
     * Player reference.
     */
    private com.hypixel.hytale.server.core.universe.world.World resolveDefaultWorld() {
        try {
            com.hypixel.hytale.server.core.universe.Universe universe =
                com.hypixel.hytale.server.core.universe.Universe.get();
            return universe == null ? null : universe.getDefaultWorld();
        } catch (Exception e) {
            LOGGER.fine("resolveDefaultWorld error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Finds and deletes the vacant shell shop for a slot. Called before
     * the renter's real shop is created (so the NPC position is freed)
     * and when the slot itself is deleted. Safe to call when no shell
     * exists (e.g. the slot is currently rented and the shell was already
     * replaced by the renter's shop).
     */
    private void deleteVacantShellShop(RentalSlotData slot) {
        ShopData shell = shopManager.getShopByRentalSlotId(slot.getId());
        if (shell == null) return;
        // Only delete actual shells (ADMIN type). If the matching shop
        // is the renter's PLAYER shop, expireSlot handles that path.
        if (!shell.isAdminShop()) return;
        try { plugin.getNpcManager().despawnNpc(shell.getId()); }
        catch (Exception e) {
            LOGGER.warning("deleteVacantShellShop: despawn failed for "
                + shell.getId() + ": " + e.getMessage());
        }
        try { shopManager.deleteShop(shell.getId()); }
        catch (Exception e) {
            LOGGER.warning("deleteVacantShellShop: deleteShop failed for "
                + shell.getId() + ": " + e.getMessage());
        }
        LOGGER.fine("Deleted vacant shell shop for rental slot " + slot.getId());
    }

    // ==================== INTERNAL ====================

    /**
     * Returns (and interns) the per-slot lock. Every mutation path
     * synchronizes on this object to serialize racing renters.
     */
    private Object lockFor(UUID slotId) {
        return slotLocks.computeIfAbsent(slotId, k -> new Object());
    }

    /** Public accessor for UI pages that need to open a confirm dialog. */
    public ShopPlugin getPlugin() {
        return plugin;
    }
}
