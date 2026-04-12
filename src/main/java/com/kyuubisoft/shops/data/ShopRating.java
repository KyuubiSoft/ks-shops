package com.kyuubisoft.shops.data;

import java.util.UUID;

public class ShopRating {

    private UUID raterUuid;
    private String raterName;
    private UUID shopId;
    private int stars;
    private String comment;
    private long timestamp;

    public ShopRating(UUID raterUuid, String raterName, UUID shopId,
                      int stars, String comment, long timestamp) {
        this.raterUuid = raterUuid;
        this.raterName = raterName;
        this.shopId = shopId;
        this.stars = Math.max(1, Math.min(5, stars));
        this.comment = comment;
        this.timestamp = timestamp;
    }

    public UUID getRaterUuid() { return raterUuid; }
    public String getRaterName() { return raterName; }
    public UUID getShopId() { return shopId; }
    public int getStars() { return stars; }
    public String getComment() { return comment; }
    public long getTimestamp() { return timestamp; }
}
