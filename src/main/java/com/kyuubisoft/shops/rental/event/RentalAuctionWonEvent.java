package com.kyuubisoft.shops.rental.event;

import java.util.UUID;

/**
 * Fired when an auction closes with a winning bid that was successfully
 * charged + a rental was started. If the winner's withdrawal failed
 * (insufficient funds at close time) the event does NOT fire and the
 * auction state is cleared / restarted instead.
 */
public class RentalAuctionWonEvent {

    private final UUID slotId;
    private final UUID shopId;
    private final String slotDisplayName;
    private final UUID winnerUuid;
    private final String winnerName;
    private final int winningBid;
    private final long rentedUntil;

    public RentalAuctionWonEvent(UUID slotId, UUID shopId, String slotDisplayName,
                                 UUID winnerUuid, String winnerName,
                                 int winningBid, long rentedUntil) {
        this.slotId = slotId;
        this.shopId = shopId;
        this.slotDisplayName = slotDisplayName;
        this.winnerUuid = winnerUuid;
        this.winnerName = winnerName;
        this.winningBid = winningBid;
        this.rentedUntil = rentedUntil;
    }

    public UUID getSlotId() { return slotId; }
    public UUID getShopId() { return shopId; }
    public String getSlotDisplayName() { return slotDisplayName; }
    public UUID getWinnerUuid() { return winnerUuid; }
    public String getWinnerName() { return winnerName; }
    public int getWinningBid() { return winningBid; }
    public long getRentedUntil() { return rentedUntil; }
}
