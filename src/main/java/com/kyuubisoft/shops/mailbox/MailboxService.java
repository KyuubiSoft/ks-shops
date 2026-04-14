package com.kyuubisoft.shops.mailbox;

import com.kyuubisoft.shops.data.ShopDatabase;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class MailboxService {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private final ShopDatabase database;

    public MailboxService(ShopDatabase database) {
        this.database = database;
    }

    public void createItemMail(UUID ownerUuid, UUID shopId, String shopName,
                               String buyerName, String itemId, int quantity) {
        createItemMail(ownerUuid, shopId, shopName, buyerName, itemId, quantity, null);
    }

    /**
     * Creates an ITEM mail that carries an optional BSON metadata blob so the
     * claim path can reconstruct enchantments / custom stats / pet ids /
     * weapon mastery levels on the receiving side. Pass {@code null} for
     * {@code itemMetadata} to send a vanilla item with no metadata (legacy
     * behaviour).
     */
    public void createItemMail(UUID ownerUuid, UUID shopId, String shopName,
                               String buyerName, String itemId, int quantity,
                               String itemMetadata) {
        if (ownerUuid == null || itemId == null || quantity <= 0) return;
        MailboxEntry entry = MailboxEntry.itemMail(ownerUuid, shopId, shopName,
            buyerName, itemId, quantity, itemMetadata);
        database.insertMailboxEntry(entry);
    }

    public void createMoneyMail(UUID ownerUuid, UUID shopId, String shopName,
                                String buyerName, double amount) {
        if (ownerUuid == null || amount <= 0) return;
        MailboxEntry entry = MailboxEntry.moneyMail(ownerUuid, shopId, shopName, buyerName, amount);
        database.insertMailboxEntry(entry);
    }

    public List<MailboxEntry> getUnclaimedForPlayer(UUID ownerUuid) {
        return database.loadMailboxForPlayer(ownerUuid, false);
    }

    public int countUnclaimedForPlayer(UUID ownerUuid) {
        return database.countUnclaimedMailsForPlayer(ownerUuid);
    }

    public MailboxEntry getMail(long mailId) {
        return database.loadMail(mailId);
    }

    /**
     * Marks a mail as claimed. Does NOT give items or money — the caller is
     * responsible for actually dispensing the reward (e.g. via ShopService.giveItem
     * or economyBridge.deposit). This method is the DB-side transition only.
     */
    public boolean markClaimed(long mailId) {
        return database.markMailClaimed(mailId);
    }
}
