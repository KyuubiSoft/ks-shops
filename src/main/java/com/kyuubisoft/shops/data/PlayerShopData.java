package com.kyuubisoft.shops.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerShopData {

    private String uuid;
    private String username;
    private List<UUID> ownedShopIds;
    private double totalEarnings;
    private double totalSpent;
    private int totalSales;
    private int totalPurchases;
    private double pendingEarnings;
    private Map<UUID, Integer> ratingsGiven;
    private String sortPreference;
    private String preferredCategory;
    private transient volatile boolean dirty;

    public PlayerShopData() {
        this.ownedShopIds = new ArrayList<>();
        this.ratingsGiven = new HashMap<>();
    }

    public PlayerShopData(String uuid, String username) {
        this();
        this.uuid = uuid;
        this.username = username;
    }

    public static PlayerShopData fromDatabase(String uuid, String username,
                                              List<UUID> ownedShopIds,
                                              double totalEarnings, double totalSpent,
                                              int totalSales, int totalPurchases,
                                              double pendingEarnings,
                                              Map<UUID, Integer> ratingsGiven,
                                              String sortPreference, String preferredCategory) {
        PlayerShopData data = new PlayerShopData();
        data.uuid = uuid;
        data.username = username;
        data.ownedShopIds = ownedShopIds != null ? new ArrayList<>(ownedShopIds) : new ArrayList<>();
        data.totalEarnings = totalEarnings;
        data.totalSpent = totalSpent;
        data.totalSales = totalSales;
        data.totalPurchases = totalPurchases;
        data.pendingEarnings = pendingEarnings;
        data.ratingsGiven = ratingsGiven != null ? new HashMap<>(ratingsGiven) : new HashMap<>();
        data.sortPreference = sortPreference;
        data.preferredCategory = preferredCategory;
        return data;
    }

    public String getUuid() { return uuid; }
    public String getUsername() { return username; }
    public List<UUID> getOwnedShopIds() { return ownedShopIds; }
    public double getTotalEarnings() { return totalEarnings; }
    public double getTotalSpent() { return totalSpent; }
    public int getTotalSales() { return totalSales; }
    public int getTotalPurchases() { return totalPurchases; }
    public double getPendingEarnings() { return pendingEarnings; }
    public Map<UUID, Integer> getRatingsGiven() { return ratingsGiven; }
    public String getSortPreference() { return sortPreference; }
    public String getPreferredCategory() { return preferredCategory; }

    public void setUsername(String username) { this.username = username; markDirty(); }
    public void setTotalEarnings(double totalEarnings) { this.totalEarnings = totalEarnings; markDirty(); }
    public void setTotalSpent(double totalSpent) { this.totalSpent = totalSpent; markDirty(); }
    public void setTotalSales(int totalSales) { this.totalSales = totalSales; markDirty(); }
    public void setTotalPurchases(int totalPurchases) { this.totalPurchases = totalPurchases; markDirty(); }
    public void setPendingEarnings(double pendingEarnings) { this.pendingEarnings = pendingEarnings; markDirty(); }
    public void setSortPreference(String sortPreference) { this.sortPreference = sortPreference; markDirty(); }
    public void setPreferredCategory(String preferredCategory) { this.preferredCategory = preferredCategory; markDirty(); }

    public void markDirty() { this.dirty = true; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { this.dirty = false; }

    public void addOwnedShop(UUID shopId) {
        if (!ownedShopIds.contains(shopId)) {
            ownedShopIds.add(shopId);
            markDirty();
        }
    }

    public void removeOwnedShop(UUID shopId) {
        if (ownedShopIds.remove(shopId)) {
            markDirty();
        }
    }

    public int getShopCount() {
        return ownedShopIds.size();
    }
}
