package com.kyuubisoft.shops.rental.event;

import java.util.UUID;

/**
 * Fired when a player successfully starts renting a rental slot.
 * Covers both FIXED price rentals and auction wins.
 */
public class RentalStartedEvent {

    private final UUID slotId;
    private final UUID shopId;
    private final UUID renterUuid;
    private final String renterName;
    private final String slotDisplayName;
    private final long rentedUntil;
    private final int totalCost;

    public RentalStartedEvent(UUID slotId, UUID shopId, UUID renterUuid, String renterName,
                              String slotDisplayName, long rentedUntil, int totalCost) {
        this.slotId = slotId;
        this.shopId = shopId;
        this.renterUuid = renterUuid;
        this.renterName = renterName;
        this.slotDisplayName = slotDisplayName;
        this.rentedUntil = rentedUntil;
        this.totalCost = totalCost;
    }

    public UUID getSlotId() { return slotId; }
    public UUID getShopId() { return shopId; }
    public UUID getRenterUuid() { return renterUuid; }
    public String getRenterName() { return renterName; }
    public String getSlotDisplayName() { return slotDisplayName; }
    public long getRentedUntil() { return rentedUntil; }
    public int getTotalCost() { return totalCost; }
}
