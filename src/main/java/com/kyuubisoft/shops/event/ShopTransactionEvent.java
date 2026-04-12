package com.kyuubisoft.shops.event;

import java.util.UUID;

/**
 * Fired when a buy or sell transaction completes in a shop.
 */
public class ShopTransactionEvent {

    private final UUID shopId;
    private final UUID buyerUuid;
    private final UUID sellerUuid;
    private final String itemId;
    private final int quantity;
    private final int totalPrice;
    private final int taxAmount;
    private final String transactionType;

    public ShopTransactionEvent(UUID shopId, UUID buyerUuid, UUID sellerUuid,
                                String itemId, int quantity, int totalPrice,
                                int taxAmount, String transactionType) {
        this.shopId = shopId;
        this.buyerUuid = buyerUuid;
        this.sellerUuid = sellerUuid;
        this.itemId = itemId;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.taxAmount = taxAmount;
        this.transactionType = transactionType;
    }

    public UUID getShopId() { return shopId; }
    public UUID getBuyerUuid() { return buyerUuid; }
    /** Nullable for admin shop transactions. */
    public UUID getSellerUuid() { return sellerUuid; }
    public String getItemId() { return itemId; }
    public int getQuantity() { return quantity; }
    public int getTotalPrice() { return totalPrice; }
    public int getTaxAmount() { return taxAmount; }
    /** "BUY" or "SELL" */
    public String getTransactionType() { return transactionType; }
}
