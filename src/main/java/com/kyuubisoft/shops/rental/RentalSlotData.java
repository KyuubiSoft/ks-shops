package com.kyuubisoft.shops.rental;

import java.util.UUID;

/**
 * A persistent leasable shop slot placed by an admin. The slot itself
 * never dies on rental expiry — only the runtime fields ({@code rentedBy},
 * {@code rentedShopId}, {@code rentedUntil}, auction state) flip back to
 * idle. The slot config + position persist forever.
 */
public class RentalSlotData {

    public enum Mode { FIXED, AUCTION }

    // --- Persistent slot config ---
    private UUID id;
    private String worldName;
    private double posX;
    private double posY;
    private double posZ;
    private float npcRotY;
    private String displayName;
    /** Optional group id so multiple slots share a station hub NPC. */
    private String stationId;
    private Mode mode = Mode.FIXED;
    private int maxDays = 7;
    private int pricePerDay = 100;     // FIXED mode only
    private int minBid = 500;          // AUCTION mode only
    private int bidIncrement = 10;     // AUCTION mode only
    private int auctionDurationMinutes = 60; // AUCTION mode only
    private long createdAt;

    // --- Runtime state (cleared on expiry) ---
    /** ID of the live ShopData backing the current rental, or null when vacant. */
    private UUID rentedShopId;
    /** UUID of the current renter, or null when vacant. */
    private UUID rentedBy;
    /** Cached renter username for nameplate rendering without DB lookup. */
    private String rentedByName;
    /** Expiry timestamp in ms since epoch. 0 = vacant / no active rental. */
    private long rentedUntil;
    /** Auction close timestamp. 0 = no active auction. */
    private long auctionEndsAt;
    private UUID currentHighBidder;
    private String currentHighBidderName;
    private int currentHighBid;
    /** True once the "ending soon" broadcast fires so we don't repeat it. */
    private boolean endingSoonBroadcast;

    private transient volatile boolean dirty;

    public RentalSlotData() {
        this.id = UUID.randomUUID();
        this.createdAt = System.currentTimeMillis();
    }

    public static RentalSlotData fromDatabase(
            UUID id, String worldName, double posX, double posY, double posZ, float npcRotY,
            String displayName, String stationId, Mode mode, int maxDays, int pricePerDay,
            int minBid, int bidIncrement, int auctionDurationMinutes, long createdAt,
            UUID rentedShopId, UUID rentedBy, String rentedByName, long rentedUntil,
            long auctionEndsAt, UUID currentHighBidder, String currentHighBidderName,
            int currentHighBid, boolean endingSoonBroadcast) {
        RentalSlotData slot = new RentalSlotData();
        slot.id = id;
        slot.worldName = worldName;
        slot.posX = posX;
        slot.posY = posY;
        slot.posZ = posZ;
        slot.npcRotY = npcRotY;
        slot.displayName = displayName;
        slot.stationId = stationId;
        slot.mode = mode != null ? mode : Mode.FIXED;
        slot.maxDays = maxDays;
        slot.pricePerDay = pricePerDay;
        slot.minBid = minBid;
        slot.bidIncrement = bidIncrement;
        slot.auctionDurationMinutes = auctionDurationMinutes;
        slot.createdAt = createdAt;
        slot.rentedShopId = rentedShopId;
        slot.rentedBy = rentedBy;
        slot.rentedByName = rentedByName;
        slot.rentedUntil = rentedUntil;
        slot.auctionEndsAt = auctionEndsAt;
        slot.currentHighBidder = currentHighBidder;
        slot.currentHighBidderName = currentHighBidderName;
        slot.currentHighBid = currentHighBid;
        slot.endingSoonBroadcast = endingSoonBroadcast;
        slot.dirty = false;
        return slot;
    }

    // --- Getters ---
    public UUID getId() { return id; }
    public String getWorldName() { return worldName; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public float getNpcRotY() { return npcRotY; }
    public String getDisplayName() { return displayName; }
    public String getStationId() { return stationId; }
    public Mode getMode() { return mode; }
    public int getMaxDays() { return maxDays; }
    public int getPricePerDay() { return pricePerDay; }
    public int getMinBid() { return minBid; }
    public int getBidIncrement() { return bidIncrement; }
    public int getAuctionDurationMinutes() { return auctionDurationMinutes; }
    public long getCreatedAt() { return createdAt; }
    public UUID getRentedShopId() { return rentedShopId; }
    public UUID getRentedBy() { return rentedBy; }
    public String getRentedByName() { return rentedByName; }
    public long getRentedUntil() { return rentedUntil; }
    public long getAuctionEndsAt() { return auctionEndsAt; }
    public UUID getCurrentHighBidder() { return currentHighBidder; }
    public String getCurrentHighBidderName() { return currentHighBidderName; }
    public int getCurrentHighBid() { return currentHighBid; }
    public boolean isEndingSoonBroadcast() { return endingSoonBroadcast; }

    // --- Setters (all markDirty) ---
    public void setId(UUID id) { this.id = id; markDirty(); }
    public void setWorldName(String worldName) { this.worldName = worldName; markDirty(); }
    public void setPosX(double posX) { this.posX = posX; markDirty(); }
    public void setPosY(double posY) { this.posY = posY; markDirty(); }
    public void setPosZ(double posZ) { this.posZ = posZ; markDirty(); }
    public void setNpcRotY(float npcRotY) { this.npcRotY = npcRotY; markDirty(); }
    public void setDisplayName(String displayName) { this.displayName = displayName; markDirty(); }
    public void setStationId(String stationId) { this.stationId = stationId; markDirty(); }
    public void setMode(Mode mode) { this.mode = mode; markDirty(); }
    public void setMaxDays(int maxDays) { this.maxDays = maxDays; markDirty(); }
    public void setPricePerDay(int pricePerDay) { this.pricePerDay = pricePerDay; markDirty(); }
    public void setMinBid(int minBid) { this.minBid = minBid; markDirty(); }
    public void setBidIncrement(int bidIncrement) { this.bidIncrement = bidIncrement; markDirty(); }
    public void setAuctionDurationMinutes(int v) { this.auctionDurationMinutes = v; markDirty(); }
    public void setRentedShopId(UUID v) { this.rentedShopId = v; markDirty(); }
    public void setRentedBy(UUID v) { this.rentedBy = v; markDirty(); }
    public void setRentedByName(String v) { this.rentedByName = v; markDirty(); }
    public void setRentedUntil(long v) { this.rentedUntil = v; markDirty(); }
    public void setAuctionEndsAt(long v) { this.auctionEndsAt = v; markDirty(); }
    public void setCurrentHighBidder(UUID v) { this.currentHighBidder = v; markDirty(); }
    public void setCurrentHighBidderName(String v) { this.currentHighBidderName = v; markDirty(); }
    public void setCurrentHighBid(int v) { this.currentHighBid = v; markDirty(); }
    public void setEndingSoonBroadcast(boolean v) { this.endingSoonBroadcast = v; markDirty(); }

    public void markDirty() { this.dirty = true; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { this.dirty = false; }

    /** True when the slot has no active renter AND no active auction. */
    public boolean isVacant() {
        return rentedBy == null && (mode == Mode.FIXED || auctionEndsAt == 0L);
    }

    /** True when an auction is currently open (mode AUCTION + auctionEndsAt > 0). */
    public boolean isAuctionOpen() {
        return mode == Mode.AUCTION && auctionEndsAt > 0L
            && System.currentTimeMillis() < auctionEndsAt;
    }

    /** True when the active rental has expired and the expiry tick has not yet cleaned it up. */
    public boolean isRentalExpired() {
        return rentedBy != null && rentedUntil > 0 && System.currentTimeMillis() >= rentedUntil;
    }

    /** Reset all runtime fields to the vacant state. Caller must persist. */
    public void clearRuntimeState() {
        this.rentedShopId = null;
        this.rentedBy = null;
        this.rentedByName = null;
        this.rentedUntil = 0L;
        this.auctionEndsAt = 0L;
        this.currentHighBidder = null;
        this.currentHighBidderName = null;
        this.currentHighBid = 0;
        this.endingSoonBroadcast = false;
        markDirty();
    }
}
