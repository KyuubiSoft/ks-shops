package com.kyuubisoft.shops.event;

import com.kyuubisoft.shops.data.ShopType;

import java.util.UUID;

/**
 * Fired when a new shop is created (admin or player).
 */
public class ShopCreateEvent {

    private final UUID shopId;
    private final ShopType type;
    private final UUID ownerUuid;
    private final String shopName;

    public ShopCreateEvent(UUID shopId, ShopType type, UUID ownerUuid, String shopName) {
        this.shopId = shopId;
        this.type = type;
        this.ownerUuid = ownerUuid;
        this.shopName = shopName;
    }

    public UUID getShopId() { return shopId; }
    public ShopType getType() { return type; }
    /** Nullable for admin shops. */
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getShopName() { return shopName; }
}
