package com.kyuubisoft.shops.data;

public class ShopItem {

    private String itemId;
    private int buyPrice;
    private int sellPrice;
    private int stock;
    private int maxStock;
    private boolean buyEnabled;
    private boolean sellEnabled;
    private int slot;
    private String category;
    private int dailyBuyLimit;
    private int dailySellLimit;
    private String itemMetadata;

    public ShopItem() {
        this.stock = -1;
        this.maxStock = -1;
        this.buyEnabled = true;
        this.sellEnabled = true;
    }

    public ShopItem(String itemId, int buyPrice, int sellPrice, int stock, int maxStock,
                    boolean buyEnabled, boolean sellEnabled, int slot, String category,
                    int dailyBuyLimit, int dailySellLimit, String itemMetadata) {
        this.itemId = itemId;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.stock = stock;
        this.maxStock = maxStock;
        this.buyEnabled = buyEnabled;
        this.sellEnabled = sellEnabled;
        this.slot = slot;
        this.category = category;
        this.dailyBuyLimit = dailyBuyLimit;
        this.dailySellLimit = dailySellLimit;
        this.itemMetadata = itemMetadata;
    }

    public String getItemId() { return itemId; }
    public int getBuyPrice() { return buyPrice; }
    public int getSellPrice() { return sellPrice; }
    public int getStock() { return stock; }
    public int getMaxStock() { return maxStock; }
    public boolean isBuyEnabled() { return buyEnabled; }
    public boolean isSellEnabled() { return sellEnabled; }
    public int getSlot() { return slot; }
    public String getCategory() { return category; }
    public int getDailyBuyLimit() { return dailyBuyLimit; }
    public int getDailySellLimit() { return dailySellLimit; }
    public String getItemMetadata() { return itemMetadata; }

    public void setItemId(String itemId) { this.itemId = itemId; }
    public void setBuyPrice(int buyPrice) { this.buyPrice = buyPrice; }
    public void setSellPrice(int sellPrice) { this.sellPrice = sellPrice; }
    public void setStock(int stock) { this.stock = stock; }
    public void setMaxStock(int maxStock) { this.maxStock = maxStock; }
    public void setBuyEnabled(boolean buyEnabled) { this.buyEnabled = buyEnabled; }
    public void setSellEnabled(boolean sellEnabled) { this.sellEnabled = sellEnabled; }
    public void setSlot(int slot) { this.slot = slot; }
    public void setCategory(String category) { this.category = category; }
    public void setDailyBuyLimit(int dailyBuyLimit) { this.dailyBuyLimit = dailyBuyLimit; }
    public void setDailySellLimit(int dailySellLimit) { this.dailySellLimit = dailySellLimit; }
    public void setItemMetadata(String itemMetadata) { this.itemMetadata = itemMetadata; }

    public boolean hasStock() {
        return stock != 0;
    }

    public boolean isUnlimitedStock() {
        return stock == -1;
    }

    public boolean decrementStock(int amount) {
        if (isUnlimitedStock()) {
            return true;
        }
        if (stock < amount) {
            return false;
        }
        stock -= amount;
        return true;
    }
}
