package com.kyuubisoft.shops.commands;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.system.Argument;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.bridge.CoreBridge;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.ShopManager;
import com.kyuubisoft.shops.service.ShopService;
import com.kyuubisoft.shops.service.ShopSessionManager;
import com.kyuubisoft.shops.ui.ShopBrowsePage;
import com.kyuubisoft.shops.ui.ShopDirectoryPage;
import com.kyuubisoft.shops.ui.ShopNotificationsPage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Player shop commands: /ksshop
 *
 * Subcommands:
 *   /ksshop browse          - Open shop directory
 *   /ksshop visit <shopId>  - Visit a shop
 *   /ksshop create <name>   - Create player shop
 *   /ksshop edit [shopId]   - Open shop editor
 *   /ksshop delete <shopId> - Delete own shop
 *   /ksshop open/close      - Toggle shop status
 *   /ksshop rename <name>   - Rename shop
 *   /ksshop myshops         - List own shops
 *   /ksshop rate <id> <1-5> - Rate a shop
 *   /ksshop history         - Transaction history
 *   /ksshop notifications   - View offline sales
 *   /ksshop collect         - Collect earnings
 *   /ksshop search <query>  - Search items/shops
 *   /ksshop help            - Show help
 */
public class ShopCommand extends AbstractCommandCollection {

    @Override
    protected boolean canGeneratePermission() { return false; }

    private final ShopPlugin plugin;

    public ShopCommand(ShopPlugin plugin) {
        super("ksshop", "KyuubiSoft Shop System");
        addAliases("shop", "market");
        this.plugin = plugin;

        addSubCommand(new HelpCmd());
        addSubCommand(new BrowseCmd());
        addSubCommand(new SearchCmd());
        addSubCommand(new VisitCmd());
        addSubCommand(new CreateCmd());
        addSubCommand(new EditCmd());
        addSubCommand(new DeleteCmd());
        addSubCommand(new OpenCmd());
        addSubCommand(new CloseCmd());
        addSubCommand(new RenameCmd());
        addSubCommand(new MyShopsCmd());
        addSubCommand(new RateCmd());
        addSubCommand(new HistoryCmd());
        addSubCommand(new NotificationsCmd());
        addSubCommand(new CollectCmd());
    }

    // ==================== PLAYER COMMANDS ====================

    private class HelpCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        HelpCmd() { super("help", "Show shop help"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            ShopI18n i18n = plugin.getI18n();
            player.sendMessage(Message.raw(i18n.get("shop.help.header")).color("#FFD700"));
            player.sendMessage(Message.raw(i18n.get("shop.help.create")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.edit")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.browse")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.search")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.visit")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.rate")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.myshops")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.collect")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get("shop.help.history")).color("#96a9be"));
        }
    }

    private class BrowseCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        BrowseCmd() { super("browse", "Browse all shops"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.browse", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }
            ShopDirectoryPage page = new ShopDirectoryPage(playerRef, player, plugin);
            player.getPageManager().openCustomPage(ref, store, page);
        }
    }

    private class SearchCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        SearchCmd() {
            super("search", "Search for items/shops");
            withRequiredArg("query", "Search query", ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.browse", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }
            String query = ctx.getInputString().split("\\s+", 3).length > 2 ? ctx.getInputString().split("\\s+", 3)[2] : "";
            // TODO: Search via DirectoryService
            player.sendMessage(Message.raw("Searching for: " + query).color("#FFD700"));
        }
    }

    private class VisitCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        VisitCmd() {
            super("visit", "Visit a specific shop");
            withRequiredArg("shopId", "Shop ID or name", ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.browse", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            ShopI18n i18n = plugin.getI18n();
            ShopManager shopManager = plugin.getShopManager();

            // Parse shop identifier (UUID or name)
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String shopIdentifier = parts.length > 2 ? parts[2] : "";
            if (shopIdentifier.isBlank()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.visit.id_required")).color("#FF5555"));
                return;
            }

            // Try UUID first
            ShopData shop = null;
            try {
                UUID shopId = UUID.fromString(shopIdentifier);
                shop = shopManager.getShop(shopId);
            } catch (IllegalArgumentException ignored) {
                // Not a UUID — search by name
            }

            // Search by name (case-insensitive)
            if (shop == null) {
                for (ShopData candidate : shopManager.getAllShops()) {
                    if (candidate.getName() != null
                            && candidate.getName().equalsIgnoreCase(shopIdentifier)) {
                        shop = candidate;
                        break;
                    }
                }
            }

            if (shop == null) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.visit.not_found", shopIdentifier)).color("#FF5555"));
                return;
            }

            if (!shop.isOpen()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.closed")).color("#FF5555"));
                return;
            }

            // Open the browse page for this shop
            plugin.getSessionManager().addActiveBuyer(playerRef.getUuid());

            final ShopData targetShop = shop;
            CoreBridge.runWithI18n(playerRef, () -> {
                try {
                    ShopBrowsePage page = new ShopBrowsePage(playerRef, player, plugin, targetShop);
                    player.getPageManager().openCustomPage(ref, store, page);
                } catch (Exception e) {
                    player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.open_failed")).color("#FF5555"));
                }
            });
        }
    }

    private class CreateCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        CreateCmd() {
            super("create", "Create a new player shop");
            withRequiredArg("name", "Shop name", ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.create", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            // Showcase write guard
            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            ShopI18n i18n = plugin.getI18n();
            ShopService shopService = plugin.getShopService();

            // Parse shop name from greedy string argument
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String shopName = parts.length > 2 ? parts[2] : "";
            if (shopName.isBlank()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.create.name_required")).color("#FF5555"));
                return;
            }

            // Get player position for shop placement
            TransformComponent tc = player.getTransformComponent();
            if (tc == null) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.create.failed")).color("#FF5555"));
                return;
            }
            Vector3d pos = tc.getPosition();
            double x = pos.x;
            double y = pos.y;
            double z = pos.z;
            String worldName = world.getName();

            // Create the shop via ShopService (handles validation, cost, limits)
            ShopData newShop = shopService.createPlayerShop(
                playerRef, shopName, "", "", worldName, x, y, z
            );

            if (newShop == null) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.create.failed")).color("#FF5555"));
                return;
            }

            // Register in NPC world index and spawn NPC
            plugin.getNpcManager().registerShopInWorld(newShop);
            plugin.getNpcManager().spawnNpc(newShop);

            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.create.success", newShop.getName())).color("#55FF55"));
        }
    }

    private class EditCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        EditCmd() { super("edit", "Open shop editor"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.edit", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            ShopI18n i18n = plugin.getI18n();
            ShopManager shopManager = plugin.getShopManager();
            UUID playerUuid = playerRef.getUuid();

            // Find nearest owned shop
            TransformComponent tc = player.getTransformComponent();
            double px = 0, py = 0, pz = 0;
            if (tc != null) {
                Vector3d pos = tc.getPosition();
                px = pos.x;
                py = pos.y;
                pz = pos.z;
            }

            ShopData nearest = null;
            double nearestDistSq = Double.MAX_VALUE;

            for (ShopData shop : shopManager.getShopsByOwner(playerUuid)) {
                if (!world.getName().equals(shop.getWorldName())) continue;
                double dx = shop.getPosX() - px;
                double dy = shop.getPosY() - py;
                double dz = shop.getPosZ() - pz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearest = shop;
                }
            }

            if (nearest == null) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.edit.no_shop")).color("#FF5555"));
                return;
            }

            // Acquire editor lock
            ShopSessionManager sessionManager = plugin.getSessionManager();
            if (!sessionManager.lockEditor(nearest.getId(), playerUuid)) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.editor_locked")).color("#FF5555"));
                return;
            }

            // Open the shop interaction as owner (opens editor page)
            final ShopData targetShop = nearest;
            CoreBridge.runWithI18n(playerRef, () -> {
                try {
                    // TODO: Replace with ShopEditPage when implemented
                    ShopBrowsePage page = new ShopBrowsePage(playerRef, player, plugin, targetShop);
                    player.getPageManager().openCustomPage(ref, store, page);
                    player.sendMessage(Message.raw(
                        i18n.get(playerRef, "shop.edit.opened", targetShop.getName())).color("#55FF55"));
                } catch (Exception e) {
                    sessionManager.unlockEditor(targetShop.getId(), playerUuid);
                    player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.open_failed")).color("#FF5555"));
                }
            });
        }
    }

    private class DeleteCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        DeleteCmd() {
            super("delete", "Delete your shop");
            withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.delete", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }
            // TODO: Delete shop with confirmation
            player.sendMessage(Message.raw("Shop deletion coming soon...").color("#FFD700"));
        }
    }

    private class OpenCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        OpenCmd() { super("open", "Open your shop for business"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.edit", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            ShopI18n i18n = plugin.getI18n();
            UUID playerUuid = playerRef.getUuid();

            // Find the player's shop(s) and open the nearest one
            List<ShopData> ownedShops = plugin.getShopManager().getShopsByOwner(playerUuid);
            if (ownedShops.isEmpty()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.edit.no_shop")).color("#FF5555"));
                return;
            }

            // If only one shop, toggle it. Otherwise, find nearest.
            ShopData targetShop;
            if (ownedShops.size() == 1) {
                targetShop = ownedShops.get(0);
            } else {
                targetShop = findNearestOwnedShop(ownedShops, player, world);
                if (targetShop == null) {
                    targetShop = ownedShops.get(0); // Fallback to first
                }
            }

            if (targetShop.isOpen()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.status.already_open", targetShop.getName())).color("#FFD700"));
                return;
            }

            targetShop.setOpen(true);
            plugin.getNpcManager().spawnNpc(targetShop);
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.status.opened", targetShop.getName())).color("#55FF55"));
        }
    }

    private class CloseCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        CloseCmd() { super("close", "Close your shop temporarily"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.edit", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            ShopI18n i18n = plugin.getI18n();
            UUID playerUuid = playerRef.getUuid();

            List<ShopData> ownedShops = plugin.getShopManager().getShopsByOwner(playerUuid);
            if (ownedShops.isEmpty()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.edit.no_shop")).color("#FF5555"));
                return;
            }

            ShopData targetShop;
            if (ownedShops.size() == 1) {
                targetShop = ownedShops.get(0);
            } else {
                targetShop = findNearestOwnedShop(ownedShops, player, world);
                if (targetShop == null) {
                    targetShop = ownedShops.get(0);
                }
            }

            if (!targetShop.isOpen()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.status.already_closed", targetShop.getName())).color("#FFD700"));
                return;
            }

            targetShop.setOpen(false);
            plugin.getNpcManager().despawnNpc(targetShop.getId());
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.status.closed", targetShop.getName())).color("#FFD700"));
        }
    }

    private class RenameCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        RenameCmd() {
            super("rename", "Rename your shop");
            withRequiredArg("name", "New shop name", ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.edit", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            ShopI18n i18n = plugin.getI18n();
            UUID playerUuid = playerRef.getUuid();

            // Parse new name
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String newName = parts.length > 2 ? parts[2].trim() : "";
            if (newName.isBlank()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.rename.name_required")).color("#FF5555"));
                return;
            }

            // Validate name length
            var cfg = plugin.getShopConfig().getData().playerShops;
            if (newName.length() < cfg.nameMinLength || newName.length() > cfg.nameMaxLength) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.rename.invalid_length",
                        cfg.nameMinLength, cfg.nameMaxLength)).color("#FF5555"));
                return;
            }

            // Check uniqueness
            for (ShopData existing : plugin.getShopManager().getAllShops()) {
                if (existing.getName().equalsIgnoreCase(newName)) {
                    player.sendMessage(Message.raw(
                        i18n.get(playerRef, "shop.rename.name_taken", newName)).color("#FF5555"));
                    return;
                }
            }

            // Find nearest owned shop
            List<ShopData> ownedShops = plugin.getShopManager().getShopsByOwner(playerUuid);
            if (ownedShops.isEmpty()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.edit.no_shop")).color("#FF5555"));
                return;
            }

            ShopData targetShop;
            if (ownedShops.size() == 1) {
                targetShop = ownedShops.get(0);
            } else {
                targetShop = findNearestOwnedShop(ownedShops, player, world);
                if (targetShop == null) {
                    targetShop = ownedShops.get(0);
                }
            }

            String oldName = targetShop.getName();
            targetShop.setName(newName);

            // Respawn NPC to update nameplate
            plugin.getNpcManager().respawnNpc(targetShop);

            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.rename.success", oldName, newName)).color("#55FF55"));
        }
    }

    private class MyShopsCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        MyShopsCmd() { super("myshops", "List your shops"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);

            ShopI18n i18n = plugin.getI18n();
            UUID playerUuid = playerRef.getUuid();

            List<ShopData> ownedShops = plugin.getShopManager().getShopsByOwner(playerUuid);
            if (ownedShops.isEmpty()) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.myshops.none")).color("#FFD700"));
                return;
            }

            int maxShops = plugin.getShopConfig().getData().playerShops.maxShopsPerPlayer;
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.myshops.header", ownedShops.size(), maxShops)).color("#FFD700"));

            for (ShopData shop : ownedShops) {
                String status = shop.isOpen()
                    ? i18n.get(playerRef, "shop.myshops.status.open")
                    : i18n.get(playerRef, "shop.myshops.status.closed");
                String statusColor = shop.isOpen() ? "#55FF55" : "#FF5555";

                int itemCount = shop.getItems().size();
                String line = " - " + shop.getName() + " [" + status + "] ("
                    + itemCount + " " + i18n.get(playerRef, "shop.myshops.items") + ")";

                player.sendMessage(Message.raw(line).color("#96a9be"));
            }
        }
    }

    private class RateCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        RateCmd() {
            super("rate", "Rate a shop");
            withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
            withRequiredArg("stars", "Rating 1-5", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.rate", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }
            // TODO: Submit rating
            player.sendMessage(Message.raw("Rating coming soon...").color("#FFD700"));
        }
    }

    private class HistoryCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        HistoryCmd() { super("history", "View transaction history"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            ShopI18n i18n = plugin.getI18n();
            UUID playerUuid = playerRef.getUuid();

            // Load recent transactions from DB (max 20 for chat display)
            List<ShopDatabase.TransactionRecord> transactions =
                plugin.getDatabase().loadTransactions(playerUuid, 20);

            if (transactions.isEmpty()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.history.empty")).color("#FFD700"));
                return;
            }

            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.history.header", transactions.size())).color("#FFD700"));

            for (ShopDatabase.TransactionRecord tx : transactions) {
                boolean isBuyer = playerUuid.toString().equals(tx.buyerUuid);
                String itemName = tx.itemId != null ? tx.itemId.replace('_', ' ') : "???";
                String timeAgo = formatRelativeTime(tx.timestamp);

                String line;
                if ("BUY".equals(tx.type)) {
                    if (isBuyer) {
                        // Player was the buyer
                        line = " [BUY] " + tx.quantity + "x " + itemName
                            + " for " + tx.totalPrice;
                        if (tx.taxAmount > 0) line += " (+" + tx.taxAmount + " tax)";
                        line += " - " + timeAgo;
                        player.sendMessage(Message.raw(line).color("#FF8888"));
                    } else {
                        // Player was the seller
                        line = " [SALE] " + tx.buyerName + " bought " + tx.quantity
                            + "x " + itemName + " for " + tx.totalPrice;
                        if (tx.taxAmount > 0) line += " (+" + tx.taxAmount + " tax)";
                        line += " - " + timeAgo;
                        player.sendMessage(Message.raw(line).color("#55FF55"));
                    }
                } else if ("SELL".equals(tx.type)) {
                    if (isBuyer) {
                        // Player sold items to the shop
                        line = " [SELL] " + tx.quantity + "x " + itemName
                            + " for " + tx.totalPrice;
                        if (tx.taxAmount > 0) line += " (-" + tx.taxAmount + " tax)";
                        line += " - " + timeAgo;
                        player.sendMessage(Message.raw(line).color("#55FF55"));
                    } else {
                        // Shop owner: someone sold items to your shop
                        line = " [BUYBACK] " + tx.buyerName + " sold " + tx.quantity
                            + "x " + itemName + " for " + tx.totalPrice;
                        line += " - " + timeAgo;
                        player.sendMessage(Message.raw(line).color("#FF8888"));
                    }
                }
            }
        }

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
    }

    private class NotificationsCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        NotificationsCmd() { super("notifications", "View sale notifications"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);

            if (!plugin.getShopConfig().getData().notifications.enabled) {
                player.sendMessage(Message.raw(
                    plugin.getI18n().get(playerRef, "shop.notifications.disabled")).color("#FF5555"));
                return;
            }

            CoreBridge.runWithI18n(playerRef, () -> {
                try {
                    ShopNotificationsPage page = new ShopNotificationsPage(playerRef, player, plugin);
                    player.getPageManager().openCustomPage(ref, store, page);
                } catch (Exception e) {
                    player.sendMessage(Message.raw(
                        plugin.getI18n().get(playerRef, "shop.error.open_failed")).color("#FF5555"));
                }
            });
        }
    }

    private class CollectCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        CollectCmd() { super("collect", "Collect shop earnings"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.collect", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            ShopI18n i18n = plugin.getI18n();
            ShopService shopService = plugin.getShopService();

            double collected = shopService.collectEarnings(playerRef);
            if (collected <= 0) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.collect.nothing")).color("#FFD700"));
            } else {
                String formatted = plugin.getEconomyBridge().format(collected);
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.collect.success", formatted)).color("#55FF55"));
            }
        }
    }

    // ==================== HELPERS ====================

    /**
     * Finds the nearest owned shop from a list, based on player position.
     */
    private ShopData findNearestOwnedShop(List<ShopData> shops, Player player, World world) {
        TransformComponent tc = player.getTransformComponent();
        double px = 0, py = 0, pz = 0;
        if (tc != null) {
            Vector3d pos = tc.getPosition();
            px = pos.x;
            py = pos.y;
            pz = pos.z;
        }
        String worldName = world.getName();

        ShopData nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (ShopData shop : shops) {
            if (!worldName.equals(shop.getWorldName())) continue;
            double dx = shop.getPosX() - px;
            double dy = shop.getPosY() - py;
            double dz = shop.getPosZ() - pz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = shop;
            }
        }

        return nearest;
    }
}
