package com.kyuubisoft.shops.data;

import java.util.List;

public class AdminShopDefinition {

    private String id;
    private String name;
    private String description;
    private String category;
    private String npcSkinUsername;
    private String currencyId;
    private String worldName;
    private double posX;
    private double posY;
    private double posZ;
    private float npcRotY;
    private List<AdminShopItemConfig> items;

    public AdminShopDefinition() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getNpcSkinUsername() { return npcSkinUsername; }
    public String getCurrencyId() { return currencyId; }
    public String getWorldName() { return worldName; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public float getNpcRotY() { return npcRotY; }
    public List<AdminShopItemConfig> getItems() { return items; }

    public static class AdminShopItemConfig {

        private String itemId;
        private int buyPrice;
        private int sellPrice;
        private int stock;
        private int maxStock;
        private boolean buyEnabled;
        private boolean sellEnabled;
        private int dailyBuyLimit;
        private int dailySellLimit;
        private String category;

        public AdminShopItemConfig() {}

        public String getItemId() { return itemId; }
        public int getBuyPrice() { return buyPrice; }
        public int getSellPrice() { return sellPrice; }
        public int getStock() { return stock; }
        public int getMaxStock() { return maxStock; }
        public boolean isBuyEnabled() { return buyEnabled; }
        public boolean isSellEnabled() { return sellEnabled; }
        public int getDailyBuyLimit() { return dailyBuyLimit; }
        public int getDailySellLimit() { return dailySellLimit; }
        public String getCategory() { return category; }
    }
}
