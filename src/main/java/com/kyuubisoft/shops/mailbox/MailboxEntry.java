package com.kyuubisoft.shops.mailbox;

import java.util.UUID;

public final class MailboxEntry {

    public enum Type { ITEM, MONEY }

    private long id;  // DB PK, 0 until inserted
    private final UUID ownerUuid;
    private final Type type;
    private final String itemId;   // null for MONEY
    private final int quantity;    // 0 for MONEY
    private final double amount;   // 0 for ITEM
    private final UUID fromShopId;        // nullable (admin transactions have none)
    private final String fromShopName;    // snapshot at time of mail creation
    private final String fromPlayerName;  // buyer name for MONEY mails; shop display name for ITEM mails
    private final long createdAt;
    private boolean claimed;

    private MailboxEntry(long id, UUID ownerUuid, Type type, String itemId, int quantity,
                         double amount, UUID fromShopId, String fromShopName,
                         String fromPlayerName, long createdAt, boolean claimed) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.type = type;
        this.itemId = itemId;
        this.quantity = quantity;
        this.amount = amount;
        this.fromShopId = fromShopId;
        this.fromShopName = fromShopName;
        this.fromPlayerName = fromPlayerName;
        this.createdAt = createdAt;
        this.claimed = claimed;
    }

    public static MailboxEntry itemMail(UUID ownerUuid, UUID shopId, String shopName,
                                        String buyerName, String itemId, int quantity) {
        return new MailboxEntry(0L, ownerUuid, Type.ITEM, itemId, quantity,
            0.0, shopId, shopName, buyerName, System.currentTimeMillis(), false);
    }

    public static MailboxEntry moneyMail(UUID ownerUuid, UUID shopId, String shopName,
                                         String buyerName, double amount) {
        return new MailboxEntry(0L, ownerUuid, Type.MONEY, null, 0,
            amount, shopId, shopName, buyerName, System.currentTimeMillis(), false);
    }

    public static MailboxEntry fromDatabase(long id, UUID ownerUuid, Type type, String itemId,
                                            int quantity, double amount, UUID fromShopId,
                                            String fromShopName, String fromPlayerName,
                                            long createdAt, boolean claimed) {
        return new MailboxEntry(id, ownerUuid, type, itemId, quantity, amount,
            fromShopId, fromShopName, fromPlayerName, createdAt, claimed);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public Type getType() { return type; }
    public String getItemId() { return itemId; }
    public int getQuantity() { return quantity; }
    public double getAmount() { return amount; }
    public UUID getFromShopId() { return fromShopId; }
    public String getFromShopName() { return fromShopName; }
    public String getFromPlayerName() { return fromPlayerName; }
    public long getCreatedAt() { return createdAt; }
    public boolean isClaimed() { return claimed; }
    public void setClaimed(boolean claimed) { this.claimed = claimed; }
}
