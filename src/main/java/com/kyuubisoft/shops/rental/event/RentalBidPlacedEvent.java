package com.kyuubisoft.shops.rental.event;

import java.util.UUID;

/**
 * Fired when a player successfully places a bid on an auction rental slot.
 * Bids are NOT withdrawn at bid time — the winner is charged at auction
 * finalize — so this event is purely informational.
 */
public class RentalBidPlacedEvent {

    private final UUID slotId;
    private final String slotDisplayName;
    private final UUID bidderUuid;
    private final String bidderName;
    private final int bidAmount;
    private final UUID previousHighBidder;
    private final String previousHighBidderName;
    private final int previousHighBid;
    private final long auctionEndsAt;

    public RentalBidPlacedEvent(UUID slotId, String slotDisplayName,
                                UUID bidderUuid, String bidderName, int bidAmount,
                                UUID previousHighBidder, String previousHighBidderName,
                                int previousHighBid, long auctionEndsAt) {
        this.slotId = slotId;
        this.slotDisplayName = slotDisplayName;
        this.bidderUuid = bidderUuid;
        this.bidderName = bidderName;
        this.bidAmount = bidAmount;
        this.previousHighBidder = previousHighBidder;
        this.previousHighBidderName = previousHighBidderName;
        this.previousHighBid = previousHighBid;
        this.auctionEndsAt = auctionEndsAt;
    }

    public UUID getSlotId() { return slotId; }
    public String getSlotDisplayName() { return slotDisplayName; }
    public UUID getBidderUuid() { return bidderUuid; }
    public String getBidderName() { return bidderName; }
    public int getBidAmount() { return bidAmount; }
    /** Nullable - the first bidder has no predecessor. */
    public UUID getPreviousHighBidder() { return previousHighBidder; }
    public String getPreviousHighBidderName() { return previousHighBidderName; }
    public int getPreviousHighBid() { return previousHighBid; }
    public long getAuctionEndsAt() { return auctionEndsAt; }
}
