package com.kyuubisoft.shops.rental.event;

import java.util.UUID;

/**
 * Fired when a rental expires (natural end-of-term, force-expire, or
 * release-early). Items + remaining shop balance have already been
 * mailed back to the renter at the time this event fires.
 */
public class RentalExpiredEvent {

    public enum Reason { EXPIRED, FORCE_EXPIRED, RELEASED_EARLY }

    private final UUID slotId;
    private final UUID shopId;
    private final UUID renterUuid;
    private final String renterName;
    private final String slotDisplayName;
    private final int mailedItems;
    private final double mailedBalance;
    private final Reason reason;

    public RentalExpiredEvent(UUID slotId, UUID shopId, UUID renterUuid, String renterName,
                              String slotDisplayName, int mailedItems, double mailedBalance,
                              Reason reason) {
        this.slotId = slotId;
        this.shopId = shopId;
        this.renterUuid = renterUuid;
        this.renterName = renterName;
        this.slotDisplayName = slotDisplayName;
        this.mailedItems = mailedItems;
        this.mailedBalance = mailedBalance;
        this.reason = reason;
    }

    public UUID getSlotId() { return slotId; }
    public UUID getShopId() { return shopId; }
    public UUID getRenterUuid() { return renterUuid; }
    public String getRenterName() { return renterName; }
    public String getSlotDisplayName() { return slotDisplayName; }
    public int getMailedItems() { return mailedItems; }
    public double getMailedBalance() { return mailedBalance; }
    public Reason getReason() { return reason; }
}
