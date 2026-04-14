package com.kyuubisoft.shops.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.mailbox.MailboxEntry;
import com.kyuubisoft.shops.mailbox.MailboxService;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Mailbox page for KS-Shops.
 *
 * Displays the player's unclaimed shop mails (items earned from sales and
 * money from purchases). Each mail can be claimed individually or in bulk
 * via the Claim All button.
 *
 * Opened by pressing F on a placed Mailbox_Block.
 *
 * Usage:
 * <pre>
 *   MailboxPage page = new MailboxPage(playerRef, player, plugin);
 *   player.getPageManager().openCustomPage(ref, store, page);
 * </pre>
 */
public class MailboxPage extends InteractiveCustomUIPage<MailboxPage.MailboxData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final int ROWS_PER_PAGE = 6;

    /** Item icon used to represent money mails. */
    private static final String MONEY_ICON = "Ingredient_Bar_Gold";

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;

    private int currentPage = 0;
    private List<MailboxEntry> mails;

    // Cached summary (over ALL mails, not just the current page)
    private int itemMailCount = 0;
    private int moneyMailCount = 0;
    private double totalPendingMoney = 0.0;

    public MailboxPage(PlayerRef playerRef, Player player, ShopPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, MailboxData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.mails = new ArrayList<>();
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/Mailbox.ui");

        loadMails();

        bindAllEvents(events);
        buildUI(ui);
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull MailboxData data) {
        super.handleDataEvent(ref, store, data);

        if (data.claim != null) {
            handleClaim(data.claim);
            return;
        }

        if (data.button != null) {
            switch (data.button) {
                case "claim_all" -> {
                    handleClaimAll();
                    return;
                }
                case "prev" -> {
                    handlePagination(-1);
                    return;
                }
                case "next" -> {
                    handlePagination(1);
                    return;
                }
                case "close" -> {
                    handleClose();
                    return;
                }
            }
        }

        // Catch-all: prevent permanent "Loading..." state
        this.sendUpdate(new UICommandBuilder(), false);
    }

    // ==================== CLAIM (single) ====================

    private void handleClaim(String indexStr) {
        ShopI18n i18n = plugin.getI18n();
        try {
            int rowIndex = Integer.parseInt(indexStr);
            int actualIndex = currentPage * ROWS_PER_PAGE + rowIndex;

            if (actualIndex < 0 || actualIndex >= mails.size()) {
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }

            MailboxEntry mail = mails.get(actualIndex);
            if (mail == null) {
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }

            // Re-fetch the latest copy from the service in case another thread changed it
            MailboxEntry fresh = plugin.getMailboxService().getMail(mail.getId());
            if (fresh == null || fresh.isClaimed()) {
                // Already claimed or deleted — refresh to drop it from the list
                loadMails();
                refreshUI();
                return;
            }

            if (dispenseAndClaim(fresh)) {
                sendClaimChatMessage(i18n, fresh);
            } else {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.mailbox.claim_failed")).color("#FF5555"));
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("[Mailbox] Invalid claim index: " + indexStr);
        } catch (Exception e) {
            LOGGER.warning("[Mailbox] Claim failed: " + e.getMessage());
        }

        loadMails();
        refreshUI();
    }

    // ==================== CLAIM ALL ====================

    private void handleClaimAll() {
        ShopI18n i18n = plugin.getI18n();
        int claimedCount = 0;
        int claimedItems = 0;
        double claimedMoney = 0.0;

        try {
            // Snapshot the list so the iteration is stable even if dispensing throws
            List<MailboxEntry> snapshot = new ArrayList<>(mails);
            for (MailboxEntry mail : snapshot) {
                if (mail == null || mail.isClaimed()) continue;

                MailboxEntry fresh = plugin.getMailboxService().getMail(mail.getId());
                if (fresh == null || fresh.isClaimed()) continue;

                if (dispenseAndClaim(fresh)) {
                    claimedCount++;
                    if (fresh.getType() == MailboxEntry.Type.ITEM) {
                        claimedItems += fresh.getQuantity();
                    } else if (fresh.getType() == MailboxEntry.Type.MONEY) {
                        claimedMoney += fresh.getAmount();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warning("[Mailbox] ClaimAll failed: " + e.getMessage());
        }

        if (claimedCount > 0) {
            String moneyFormatted = plugin.getEconomyBridge().format(claimedMoney);
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.mailbox.claimed_all",
                    claimedCount, claimedItems, moneyFormatted)).color("#55FF55"));
        } else if (!mails.isEmpty()) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.mailbox.claim_failed")).color("#FF5555"));
        }

        loadMails();
        currentPage = 0;
        refreshUI();
    }

    // ==================== DISPENSE ====================

    /**
     * Gives the mail contents to the player and marks the mail claimed on success.
     * Returns true if the dispense + DB update both succeeded.
     */
    private boolean dispenseAndClaim(MailboxEntry mail) {
        MailboxService mailboxService = plugin.getMailboxService();
        try {
            if (mail.getType() == MailboxEntry.Type.ITEM) {
                // giveItem is void + catches its own exceptions; any remainder is
                // dropped at the player's feet by the Priority 1 fix. We consider
                // the call successful unless it throws an unchecked exception.
                plugin.getShopService().giveItem(playerRef, mail.getItemId(), mail.getQuantity());
                return mailboxService.markClaimed(mail.getId());
            }

            if (mail.getType() == MailboxEntry.Type.MONEY) {
                boolean deposited = plugin.getEconomyBridge().deposit(
                    playerRef.getUuid(), mail.getAmount());
                if (!deposited) return false;
                return mailboxService.markClaimed(mail.getId());
            }
        } catch (Exception e) {
            LOGGER.warning("[Mailbox] Dispense failed for mail " + mail.getId() + ": " + e.getMessage());
        }
        return false;
    }

    private void sendClaimChatMessage(ShopI18n i18n, MailboxEntry mail) {
        if (mail.getType() == MailboxEntry.Type.ITEM) {
            String itemLabel = formatItemName(mail.getItemId());
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.mailbox.claimed_item",
                    mail.getQuantity(), itemLabel)).color("#55FF55"));
        } else if (mail.getType() == MailboxEntry.Type.MONEY) {
            String moneyFormatted = plugin.getEconomyBridge().format(mail.getAmount());
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.mailbox.claimed_money", moneyFormatted)).color("#55FF55"));
        }
    }

    // ==================== CLOSE ====================

    private void handleClose() {
        try {
            this.close();
        } catch (Exception e) {
            LOGGER.warning("[Mailbox] Failed to close page: " + e.getMessage());
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    // ==================== PAGINATION ====================

    private void handlePagination(int direction) {
        int totalPages = getTotalPages();
        int newPage = currentPage + direction;
        if (newPage >= 0 && newPage < totalPages) {
            currentPage = newPage;
        }
        refreshUI();
    }

    // ==================== DATA LOADING ====================

    private void loadMails() {
        try {
            UUID playerUuid = playerRef.getUuid();
            mails = plugin.getMailboxService().getUnclaimedForPlayer(playerUuid);
            if (mails == null) mails = new ArrayList<>();
        } catch (Exception e) {
            LOGGER.warning("[Mailbox] Failed to load mails: " + e.getMessage());
            mails = new ArrayList<>();
        }

        // Recompute summary
        itemMailCount = 0;
        moneyMailCount = 0;
        totalPendingMoney = 0.0;
        for (MailboxEntry m : mails) {
            if (m == null) continue;
            if (m.getType() == MailboxEntry.Type.ITEM) {
                itemMailCount++;
            } else if (m.getType() == MailboxEntry.Type.MONEY) {
                moneyMailCount++;
                totalPendingMoney += m.getAmount();
            }
        }

        // Clamp current page to the new range
        int totalPages = getTotalPages();
        if (currentPage >= totalPages) currentPage = Math.max(0, totalPages - 1);
    }

    // ==================== REFRESH ====================

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindAllEvents(events);
        buildUI(ui);
        this.sendUpdate(ui, events, false);
    }

    // ==================== EVENT BINDING ====================

    private void bindAllEvents(UIEventBuilder events) {
        // Claim-All button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimAllBtn",
            EventData.of("Button", "claim_all"), false);

        // Per-row claim buttons
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                "#Entry" + i + "Claim",
                EventData.of("Claim", String.valueOf(i)), false);
        }

        // Pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevBtn",
            EventData.of("Button", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextBtn",
            EventData.of("Button", "next"), false);

        // Close
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn",
            EventData.of("Button", "close"), false);
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();

        // Summary section
        buildSummary(ui, i18n);

        // Empty state vs list
        boolean isEmpty = mails.isEmpty();
        ui.set("#EmptyLabel.Visible", isEmpty);
        ui.set("#MailList.Visible", !isEmpty);
        ui.set("#MailListHeader.Visible", !isEmpty);
        if (isEmpty) {
            ui.set("#EmptyLabel.Text", i18n.get(playerRef, "shop.mailbox.empty"));
        }

        // Mail rows
        buildMailRows(ui, i18n);

        // Pagination
        buildPagination(ui);

        // Claim-All visibility (hidden when nothing to claim)
        ui.set("#ClaimAllBtn.Visible", !isEmpty);
    }

    private void buildSummary(UICommandBuilder ui, ShopI18n i18n) {
        // Header is constant; RenderUppercase in the .ui handles casing
        ui.set("#SummaryHeader.Text", "UNCLAIMED MAILS");

        String summaryLine = i18n.get(playerRef, "shop.mailbox.summary",
            itemMailCount, moneyMailCount);
        ui.set("#SummaryCounts.Text", summaryLine);

        String totalFormatted = plugin.getEconomyBridge().format(totalPendingMoney);
        ui.set("#SummaryTotal.Text",
            i18n.get(playerRef, "shop.mailbox.total_pending", totalFormatted));
    }

    private void buildMailRows(UICommandBuilder ui, ShopI18n i18n) {
        int totalMails = mails.size();
        int startIndex = currentPage * ROWS_PER_PAGE;

        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            String entryId = "#Entry" + i;
            int actualIndex = startIndex + i;

            if (actualIndex < totalMails) {
                MailboxEntry mail = mails.get(actualIndex);
                ui.set(entryId + ".Visible", true);

                // Icon
                String iconId = (mail.getType() == MailboxEntry.Type.ITEM && mail.getItemId() != null)
                    ? mail.getItemId()
                    : MONEY_ICON;
                ui.set(entryId + " " + entryId + "Icon.ItemId", iconId);

                // Title line (item + amount, or money amount)
                ui.set(entryId + " " + entryId + "Detail.Text", buildTitleText(i18n, mail));

                // From line (shop name + optional buyer)
                ui.set(entryId + " " + entryId + "From.Text", buildFromText(i18n, mail));

                // Time
                ui.set(entryId + " " + entryId + "Time.Text",
                    formatRelativeTime(mail.getCreatedAt()));
            } else {
                ui.set(entryId + ".Visible", false);
            }
        }
    }

    /**
     * Builds the top line of a mail row. For ITEM mails this is the
     * quantity + item name ("5x Iron Sword"), for MONEY mails it is
     * the formatted amount ("250 Gold").
     */
    private String buildTitleText(ShopI18n i18n, MailboxEntry mail) {
        if (mail.getType() == MailboxEntry.Type.ITEM) {
            String itemLabel = formatItemName(mail.getItemId());
            return i18n.get(playerRef, "shop.mailbox.row.item_title",
                mail.getQuantity(), itemLabel);
        }
        String amountFormatted = plugin.getEconomyBridge().format(mail.getAmount());
        return i18n.get(playerRef, "shop.mailbox.row.money_title", amountFormatted);
    }

    /**
     * Builds the bottom line of a mail row. For ITEM mails this is just
     * "From: ShopName". For MONEY mails it includes the buyer name
     * ("From: ShopName (BuyerName)") so the owner can see who paid.
     */
    private String buildFromText(ShopI18n i18n, MailboxEntry mail) {
        String shopName = mail.getFromShopName() != null ? mail.getFromShopName() : "Shop";
        if (mail.getType() == MailboxEntry.Type.ITEM) {
            return i18n.get(playerRef, "shop.mailbox.row.from_item", shopName);
        }
        String fromPlayer = mail.getFromPlayerName() != null ? mail.getFromPlayerName() : "";
        if (fromPlayer.isEmpty()) {
            return i18n.get(playerRef, "shop.mailbox.row.from_item", shopName);
        }
        return i18n.get(playerRef, "shop.mailbox.row.from_money", shopName, fromPlayer);
    }

    private void buildPagination(UICommandBuilder ui) {
        int totalPages = getTotalPages();

        // Clamp current page defensively
        if (currentPage >= totalPages) currentPage = Math.max(0, totalPages - 1);

        ui.set("#PageInfo.Text", (currentPage + 1) + "/" + Math.max(1, totalPages));
        ui.set("#PrevBtn.Visible", currentPage > 0);
        ui.set("#NextBtn.Visible", currentPage < totalPages - 1);
    }

    // ==================== HELPERS ====================

    private int getTotalPages() {
        int total = (mails != null) ? mails.size() : 0;
        return Math.max(1, (int) Math.ceil(total / (double) ROWS_PER_PAGE));
    }

    /**
     * Converts an item ID like "Ingredient_Bar_Gold" to "Ingredient Bar Gold".
     */
    private String formatItemName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "";
        return itemId.replace('_', ' ');
    }

    /**
     * Formats a timestamp into a relative time string.
     * ASCII-only so the .ui labels stay safe.
     */
    private String formatRelativeTime(long timestamp) {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        if (diff < 0) return "just now";

        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";

        long hours = TimeUnit.MILLISECONDS.toHours(diff);
        if (hours < 24) return hours + "h ago";

        long days = TimeUnit.MILLISECONDS.toDays(diff);
        if (days < 30) return days + "d ago";

        long months = days / 30;
        if (months < 12) return months + "mo ago";

        return (days / 365) + "y ago";
    }

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[Mailbox] onDismiss error: " + e.getMessage());
        }
        LOGGER.fine("[Mailbox] Page dismissed for " + playerRef.getUsername());
    }

    // ==================== EVENT DATA CODEC ====================

    public static class MailboxData {
        public static final BuilderCodec<MailboxData> CODEC = BuilderCodec
            .<MailboxData>builder(MailboxData.class, MailboxData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .addField(new KeyedCodec<>("Claim", Codec.STRING),
                (data, value) -> data.claim = value,
                data -> data.claim)
            .build();

        private String button;
        private String claim;

        public MailboxData() {}
    }
}
