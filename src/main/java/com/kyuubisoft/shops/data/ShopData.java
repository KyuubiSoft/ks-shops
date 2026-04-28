package com.kyuubisoft.shops.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class ShopData {

    private UUID id;
    private String name;
    private String description;
    private ShopType type;
    private UUID ownerUuid;
    private String ownerName;
    private String worldName;
    private double posX;
    private double posY;
    private double posZ;
    private float npcRotY;
    private String npcEntityId;
    private String npcSkinUsername;
    private String iconItemId;
    private List<ShopItem> items;
    private String category;
    private List<String> tags;
    private double averageRating;
    private int totalRatings;
    private double shopBalance;   // Shop account balance (owner deposits, customers pay into, sells deduct from)
    private double totalRevenue;
    private double totalTaxPaid;
    private long rentPaidUntil;
    private double rentCostPerCycle;
    private int rentCycleDays;
    private boolean featured;
    private long featuredUntil;
    private boolean open;
    private boolean showNameTag = true;
    private boolean packed = false;
    /** Timestamp (ms) up to which this shop is listed in the public directory.
     *  0 = never listed, {@link Long#MAX_VALUE} = permanent (via permission
     *  or admin override), anything else = expires at that time. */
    private long listedUntil = 0L;
    /** When non-null, this shop is backing a rental slot. Links back to
     *  {@code RentalSlotData.id} for the vacant-slot NPC flow + expiry. */
    private UUID rentalSlotId;
    /** Mirror of {@code RentalSlotData.rentedUntil} for quick filtering
     *  without a join. 0 = not a rental / not rented. */
    private long rentalExpiresAt = 0L;
    private long createdAt;
    private long lastActivity;
    private transient volatile boolean dirty;

    public ShopData() {
        this.id = UUID.randomUUID();
        this.items = new CopyOnWriteArrayList<>();
        this.tags = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.lastActivity = this.createdAt;
        this.open = true;
    }

    public ShopData(String name, String description, ShopType type,
                    UUID ownerUuid, String ownerName,
                    String worldName, double posX, double posY, double posZ, float npcRotY) {
        this();
        this.name = name;
        this.description = description;
        this.type = type;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.worldName = worldName;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.npcRotY = npcRotY;
    }

    public static ShopData fromDatabase(UUID id, String name, String description, ShopType type,
                                        UUID ownerUuid, String ownerName,
                                        String worldName, double posX, double posY, double posZ,
                                        float npcRotY, String npcEntityId, String npcSkinUsername,
                                        String iconItemId,
                                        List<ShopItem> items, String category, List<String> tags,
                                        double averageRating, int totalRatings,
                                        double totalRevenue, double totalTaxPaid,
                                        long rentPaidUntil, double rentCostPerCycle, int rentCycleDays,
                                        boolean featured, long featuredUntil,
                                        boolean open, long createdAt, long lastActivity) {
        ShopData shop = new ShopData();
        shop.id = id;
        shop.name = name;
        shop.description = description;
        shop.type = type;
        shop.ownerUuid = ownerUuid;
        shop.ownerName = ownerName;
        shop.worldName = worldName;
        shop.posX = posX;
        shop.posY = posY;
        shop.posZ = posZ;
        shop.npcRotY = npcRotY;
        shop.npcEntityId = npcEntityId;
        shop.npcSkinUsername = npcSkinUsername;
        shop.iconItemId = iconItemId;
        shop.items = items != null ? new CopyOnWriteArrayList<>(items) : new CopyOnWriteArrayList<>();
        shop.category = category;
        shop.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        shop.averageRating = averageRating;
        shop.totalRatings = totalRatings;
        shop.totalRevenue = totalRevenue;
        shop.totalTaxPaid = totalTaxPaid;
        shop.rentPaidUntil = rentPaidUntil;
        shop.rentCostPerCycle = rentCostPerCycle;
        shop.rentCycleDays = rentCycleDays;
        shop.featured = featured;
        shop.featuredUntil = featuredUntil;
        shop.open = open;
        shop.createdAt = createdAt;
        shop.lastActivity = lastActivity;
        return shop;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ShopType getType() { return type; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public String getWorldName() { return worldName; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public float getNpcRotY() { return npcRotY; }
    public String getNpcEntityId() { return npcEntityId; }
    public String getNpcSkinUsername() { return npcSkinUsername; }
    public String getIconItemId() { return iconItemId; }
    public List<ShopItem> getItems() { return items; }
    public String getCategory() { return category; }
    public List<String> getTags() { return tags; }
    public double getAverageRating() { return averageRating; }
    public int getTotalRatings() { return totalRatings; }
    public double getShopBalance() { return shopBalance; }
    public double getTotalRevenue() { return totalRevenue; }
    public double getTotalTaxPaid() { return totalTaxPaid; }
    public long getRentPaidUntil() { return rentPaidUntil; }
    public double getRentCostPerCycle() { return rentCostPerCycle; }
    public int getRentCycleDays() { return rentCycleDays; }
    public boolean isFeatured() { return featured; }
    public long getFeaturedUntil() { return featuredUntil; }
    public boolean isOpen() { return open; }
    public boolean isShowNameTag() { return showNameTag; }
    public boolean isPacked() { return packed; }
    public long getListedUntil() { return listedUntil; }

    /**
     * True when this shop is currently listed in the public directory.
     * Admin shops are always considered listed regardless of the field
     * (the directory filter special-cases them in DirectoryService).
     */
    public boolean isListedInDirectory() {
        if (listedUntil == Long.MAX_VALUE) return true;
        return listedUntil > System.currentTimeMillis();
    }
    public UUID getRentalSlotId() { return rentalSlotId; }
    public long getRentalExpiresAt() { return rentalExpiresAt; }
    /** True when this shop row is backing a rental slot (regardless of rented state). */
    public boolean isRentalBacked() { return rentalSlotId != null; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActivity() { return lastActivity; }

    public void setName(String name) { this.name = name; markDirty(); }
    public void setDescription(String description) { this.description = description; markDirty(); }
    public void setType(ShopType type) { this.type = type; markDirty(); }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; markDirty(); }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; markDirty(); }
    public void setWorldName(String worldName) { this.worldName = worldName; markDirty(); }
    public void setPosX(double posX) { this.posX = posX; markDirty(); }
    public void setPosY(double posY) { this.posY = posY; markDirty(); }
    public void setPosZ(double posZ) { this.posZ = posZ; markDirty(); }
    public void setNpcRotY(float npcRotY) { this.npcRotY = npcRotY; markDirty(); }
    public void setNpcEntityId(String npcEntityId) { this.npcEntityId = npcEntityId; markDirty(); }
    public void setNpcSkinUsername(String npcSkinUsername) { this.npcSkinUsername = npcSkinUsername; markDirty(); }
    public void setIconItemId(String iconItemId) { this.iconItemId = iconItemId; markDirty(); }
    public void setCategory(String category) { this.category = category; markDirty(); }
    public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); markDirty(); }
    public void setAverageRating(double averageRating) { this.averageRating = averageRating; markDirty(); }
    public void setTotalRatings(int totalRatings) { this.totalRatings = totalRatings; markDirty(); }
    public void setShopBalance(double shopBalance) { this.shopBalance = shopBalance; markDirty(); }
    public void addToBalance(double amount) {
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        this.shopBalance += amount;
        markDirty();
    }
    public boolean deductFromBalance(double amount) {
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return false;
        if (shopBalance >= amount) { shopBalance -= amount; markDirty(); return true; }
        return false;
    }
    public void setTotalRevenue(double totalRevenue) { this.totalRevenue = totalRevenue; markDirty(); }
    public void setTotalTaxPaid(double totalTaxPaid) { this.totalTaxPaid = totalTaxPaid; markDirty(); }
    public void setRentPaidUntil(long rentPaidUntil) { this.rentPaidUntil = rentPaidUntil; markDirty(); }
    public void setRentCostPerCycle(double rentCostPerCycle) { this.rentCostPerCycle = rentCostPerCycle; markDirty(); }
    public void setRentCycleDays(int rentCycleDays) { this.rentCycleDays = rentCycleDays; markDirty(); }
    public void setFeatured(boolean featured) { this.featured = featured; markDirty(); }
    public void setFeaturedUntil(long featuredUntil) { this.featuredUntil = featuredUntil; markDirty(); }
    public void setOpen(boolean open) { this.open = open; markDirty(); }
    public void setShowNameTag(boolean showNameTag) { this.showNameTag = showNameTag; markDirty(); }
    public void setListedUntil(long listedUntil) { this.listedUntil = listedUntil; markDirty(); }
    public void setRentalSlotId(UUID rentalSlotId) { this.rentalSlotId = rentalSlotId; markDirty(); }
    public void setRentalExpiresAt(long rentalExpiresAt) { this.rentalExpiresAt = rentalExpiresAt; markDirty(); }
    public void setPacked(boolean packed) { this.packed = packed; markDirty(); }
    public void setLastActivity(long lastActivity) { this.lastActivity = lastActivity; markDirty(); }

    public void markDirty() { this.dirty = true; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { this.dirty = false; }

    /**
     * Returns the ItemId to use as the visual icon for this shop in directory/browse views.
     * Prefers the explicit {@link #iconItemId} chosen by the owner via the icon picker.
     * Falls back to the first shop item's itemId, or {@code null} if the shop is empty.
     */
    public String getDisplayIconItemId() {
        if (iconItemId != null && !iconItemId.isBlank()) {
            return iconItemId;
        }
        return items.isEmpty() ? null : items.get(0).getItemId();
    }

    public ShopItem getItem(String itemId) {
        for (ShopItem item : items) {
            if (item.getItemId().equals(itemId)) {
                return item;
            }
        }
        return null;
    }

    public void addItem(ShopItem item) {
        items.add(item);
        markDirty();
    }

    public boolean removeItem(String itemId) {
        boolean removed = items.removeIf(item -> item.getItemId().equals(itemId));
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public boolean isPlayerShop() {
        return type == ShopType.PLAYER;
    }

    public boolean isAdminShop() {
        return type == ShopType.ADMIN;
    }

    public void recalculateRating(List<ShopRating> ratings) {
        if (ratings == null || ratings.isEmpty()) {
            this.averageRating = 0;
            this.totalRatings = 0;
            markDirty();
            return;
        }
        double sum = 0;
        for (ShopRating rating : ratings) {
            sum += rating.getStars();
        }
        this.totalRatings = ratings.size();
        this.averageRating = sum / totalRatings;
        markDirty();
    }
}
