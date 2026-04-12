package com.kyuubisoft.shops.event;

import com.kyuubisoft.shops.data.ShopType;

import java.util.UUID;

/**
 * Fired when a shop is deleted.
 */
public class ShopDeleteEvent {

    private final UUID shopId;
    private final ShopType type;
    private final UUID ownerUuid;
    private final String reason;

    public ShopDeleteEvent(UUID shopId, ShopType type, UUID ownerUuid, String reason) {
        this.shopId = shopId;
        this.type = type;
        this.ownerUuid = ownerUuid;
        this.reason = reason;
    }

    public UUID getShopId() { return shopId; }
    public ShopType getType() { return type; }
    /** Nullable for admin shops. */
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getReason() { return reason; }
}
