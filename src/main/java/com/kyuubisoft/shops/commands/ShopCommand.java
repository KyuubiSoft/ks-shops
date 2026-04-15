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
import com.kyuubisoft.shops.service.CreateShopResult;
import com.kyuubisoft.shops.service.ShopManager;
import com.kyuubisoft.shops.service.ShopService;
import com.kyuubisoft.shops.service.ShopSessionManager;
import com.kyuubisoft.shops.ui.ShopBrowsePage;
import com.kyuubisoft.shops.ui.ShopDirectoryPage;
import com.kyuubisoft.shops.ui.ShopEditPage;
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

    // BUG #12: Pending delete confirmations per player (re-run with "confirm" within window).
    private final java.util.Map<UUID, DeleteConfirm> pendingDeletes =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DELETE_CONFIRM_WINDOW_MS = 60_000L;

    private static final class DeleteConfirm {
        final UUID shopId;
        final long expiresAt;
        DeleteConfirm(UUID shopId, long expiresAt) {
            this.shopId = shopId;
            this.expiresAt = expiresAt;
        }
    }

    // P3 polish: Pending ownership transfer confirmations per player.
    private final java.util.Map<UUID, TransferConfirm> pendingTransfers =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TRANSFER_CONFIRM_WINDOW_MS = 60_000L;

    private static final class TransferConfirm {
        final UUID shopId;
        final String targetName;
        final UUID targetUuid;
        final long expiresAt;
        TransferConfirm(UUID shopId, String targetName, UUID targetUuid, long expiresAt) {
            this.shopId = shopId;
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.expiresAt = expiresAt;
        }
    }

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
        addSubCommand(new DepositCmd());
        addSubCommand(new StatsCmd());
        addSubCommand(new TransferCmd());
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

            // BUG #8 fix: Getting-started block so new players know the basic flow
            // before they see the raw command list.
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.gs.header")).color("#FFD700"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.gs.step1")).color("#bfcdd5"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.gs.step2")).color("#bfcdd5"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.gs.step3")).color("#bfcdd5"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.gs.step4")).color("#bfcdd5"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.gs.step5")).color("#bfcdd5"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.gs.spacer")).color("#bfcdd5"));

            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.header")).color("#FFD700"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.create")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.edit")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.browse")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.search")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.visit")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.rate")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.myshops")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.collect")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.history")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.stats")).color("#96a9be"));
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.help.transfer")).color("#96a9be"));
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
            // Admin kill-switch: features.directory = false hides the
            // global directory entirely so visiting requires walking up
            // to the NPC in person.
            if (!plugin.getShopConfig().getData().features.directory) {
                player.sendMessage(Message.raw(
                    plugin.getI18n().get(playerRef, "shop.directory.disabled")
                ).color("#FF5555"));
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

            // BUG #12 fix: Actual item/shop name search (replaces the coming-soon stub).
            ShopI18n i18n = plugin.getI18n();
            ShopManager shopManager = plugin.getShopManager();

            String[] parts = ctx.getInputString().split("\\s+", 3);
            String rawQuery = parts.length > 2 ? parts[2] : "";
            String needle = rawQuery.trim().toLowerCase();
            if (needle.isBlank()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.search.empty_query")).color("#FF5555"));
                return;
            }

            // Collect unique shops that match either by name or by item id.
            java.util.LinkedHashSet<ShopData> matches = new java.util.LinkedHashSet<>();
            for (ShopData shop : shopManager.getAllShops()) {
                if (!shop.isOpen()) continue;
                if (shop.getName() != null && shop.getName().toLowerCase().contains(needle)) {
                    matches.add(shop);
                    if (matches.size() >= 10) break;
                    continue;
                }
                for (com.kyuubisoft.shops.data.ShopItem item : shop.getItems()) {
                    if (item.getItemId() != null
                            && item.getItemId().toLowerCase().contains(needle)) {
                        matches.add(shop);
                        break;
                    }
                }
                if (matches.size() >= 10) break;
            }

            if (matches.isEmpty()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.search.no_results", rawQuery)).color("#FFAA00"));
                return;
            }

            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.search.header", matches.size(), rawQuery)).color("#FFD700"));
            for (ShopData shop : matches) {
                String typeLabel = shop.isAdminShop()
                    ? "[ADMIN]"
                    : "[" + (shop.getOwnerName() != null ? shop.getOwnerName() : "?") + "]";
                player.sendMessage(Message.raw(
                    " - " + typeLabel + " " + shop.getName()
                        + " (" + shop.getItems().size() + " items)").color("#96a9be"));
            }
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

            // Get player position for shop placement. The NPC spawns at the player's
            // exact coordinates (no Shop_Block scan — the new flow is NPC-only).
            TransformComponent tc = player.getTransformComponent();
            if (tc == null) {
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.create.failed")).color("#FF5555"));
                return;
            }
            Vector3d pos = tc.getPosition();
            String worldName = world.getName();

            // Pull the player's yaw so the NPC faces the owner by default.
            float rotY = 0.0f;
            try {
                var rotation = tc.getRotation();
                if (rotation != null) rotY = rotation.y;
            } catch (Throwable ignored) {}

            // Create the shop via ShopService (handles validation, cost, limits).
            // Pass the Player so per-rank ks.shop.limit.shops.N permissions
            // are honored by the max-shops check.
            CreateShopResult result = shopService.createPlayerShop(
                playerRef, player, shopName, "", "", worldName, pos.x, pos.y, pos.z
            );

            if (!result.isSuccess()) {
                String errorKey = result.getErrorKey() != null
                    ? result.getErrorKey()
                    : "shop.create.failed";
                player.sendMessage(Message.raw(i18n.get(playerRef, errorKey)).color("#FF5555"));
                return;
            }

            ShopData newShop = result.getShop();
            newShop.setNpcRotY(rotY);

            // Register in NPC world index and spawn a standalone NPC at the
            // player's feet. World.execute() hops onto the entity thread.
            final com.hypixel.hytale.math.vector.Vector3d spawnPos = pos;
            final float finalRotY = rotY;
            plugin.getNpcManager().registerShopInWorld(newShop);
            world.execute(() ->
                plugin.getNpcManager().spawnNpcAtPosition(newShop, world, spawnPos, finalRotY));

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
                // Packed shops have no NPC and stale coords - they are dormant
                // until the owner replants them via the Shop_NPC_Token. Skip
                // them here so /ksshop edit always targets a live shop.
                if (shop.isPacked()) continue;
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

            // Open the shop editor page with ContainerWindow for drag&drop
            final ShopData targetShop = nearest;
            final UUID shopUuid = targetShop.getId();
            try {
                ShopEditPage editPage = new ShopEditPage(playerRef, player, plugin, targetShop);

                com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow window =
                    new com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow(editPage.getStagingContainer());

                // Register close event for cleanup (like Bank)
                window.registerCloseEvent(event -> {
                    editPage.onWindowClose();
                    sessionManager.unlockEditor(shopUuid, playerUuid);
                });

                player.getPageManager().openCustomPageWithWindows(ref, store, editPage, window);
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.edit.opened", targetShop.getName())).color("#55FF55"));
            } catch (Exception e) {
                e.printStackTrace();
                sessionManager.unlockEditor(shopUuid, playerUuid);
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.open_failed")).color("#FF5555"));
            }
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

            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            // BUG #12 fix: Full two-step confirm delete flow (replaces coming-soon stub).
            ShopI18n i18n = plugin.getI18n();
            ShopManager shopManager = plugin.getShopManager();
            UUID playerUuid = playerRef.getUuid();

            // Parse arguments: /ksshop delete <shopId> [confirm]
            String[] parts = ctx.getInputString().split("\\s+", 4);
            String shopIdentifier = parts.length > 2 ? parts[2].trim() : "";
            boolean confirm = parts.length > 3 && "confirm".equalsIgnoreCase(parts[3].trim());

            if (shopIdentifier.isEmpty()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.visit.id_required")).color("#FF5555"));
                return;
            }

            // Resolve the shop: UUID first, then by case-insensitive name.
            ShopData shop = null;
            try {
                UUID uuid = UUID.fromString(shopIdentifier);
                shop = shopManager.getShop(uuid);
            } catch (IllegalArgumentException ignored) {
                // Fall through to name lookup
            }
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
                    i18n.get(playerRef, "shop.delete.not_found", shopIdentifier)).color("#FF5555"));
                return;
            }

            // Ownership check — players can only delete shops they own.
            if (shop.getOwnerUuid() == null || !shop.getOwnerUuid().equals(playerUuid)) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.delete.not_owner")).color("#FF5555"));
                return;
            }

            UUID shopId = shop.getId();
            String shopName = shop.getName();

            // Check for an active confirm window.
            DeleteConfirm pending = pendingDeletes.get(playerUuid);
            long now = System.currentTimeMillis();
            if (pending != null && pending.expiresAt < now) {
                pendingDeletes.remove(playerUuid);
                pending = null;
            }

            if (confirm && pending != null && pending.shopId.equals(shopId)) {
                // Perform the delete. Despawn NPC first so there is no orphan entity.
                pendingDeletes.remove(playerUuid);

                try {
                    plugin.getNpcManager().despawnNpc(shopId);
                } catch (Exception e) {
                    // Non-fatal — fall through to delete the shop regardless.
                }

                boolean deleted = plugin.getShopService().deletePlayerShop(playerRef, shopId);
                if (!deleted) {
                    player.sendMessage(Message.raw(
                        i18n.get(playerRef, "shop.delete.failed")).color("#FF5555"));
                    return;
                }

                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.delete.confirmed", shopName)).color("#55FF55"));
                return;
            }

            // Stage a new confirmation.
            pendingDeletes.put(playerUuid,
                new DeleteConfirm(shopId, now + DELETE_CONFIRM_WINDOW_MS));
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.delete.confirm_prompt", shopIdentifier, shopName))
                .color("#FFAA00"));
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
        MyShopsCmd() { super("myshops", "Open a visual list of your shops"); }

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

            try {
                com.kyuubisoft.shops.ui.ShopDirectoryPage page =
                    new com.kyuubisoft.shops.ui.ShopDirectoryPage(playerRef, player, plugin, playerUuid);
                player.getPageManager().openCustomPage(ref, store, page);
            } catch (Exception e) {
                e.printStackTrace();
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.open_failed")).color("#FF5555"));
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

            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            // BUG #12 fix: Actual rating submission (replaces coming-soon stub).
            ShopI18n i18n = plugin.getI18n();
            ShopManager shopManager = plugin.getShopManager();
            ShopDatabase database = plugin.getDatabase();
            UUID raterUuid = playerRef.getUuid();

            // Parse args: /ksshop rate <shopId> <stars>
            String[] parts = ctx.getInputString().split("\\s+", 4);
            String shopIdentifier = parts.length > 2 ? parts[2].trim() : "";
            String starsStr = parts.length > 3 ? parts[3].trim() : "";

            if (shopIdentifier.isEmpty() || starsStr.isEmpty()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.error.invalid_arguments",
                        "/ksshop rate <shopId> <1-5>")).color("#FF5555"));
                return;
            }

            int stars;
            try {
                stars = Integer.parseInt(starsStr);
            } catch (NumberFormatException e) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.rate.invalid_stars")).color("#FF5555"));
                return;
            }
            if (stars < 1 || stars > 5) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.rate.invalid_stars")).color("#FF5555"));
                return;
            }

            // Resolve shop: UUID first, then name.
            ShopData shop = null;
            try {
                UUID uuid = UUID.fromString(shopIdentifier);
                shop = shopManager.getShop(uuid);
            } catch (IllegalArgumentException ignored) {
                // Fall through to name lookup
            }
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
                    i18n.get(playerRef, "shop.error.shop_not_found", shopIdentifier))
                    .color("#FF5555"));
                return;
            }

            // Owners cannot rate their own shops.
            if (shop.getOwnerUuid() != null && shop.getOwnerUuid().equals(raterUuid)) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.rate.own_shop")).color("#FF5555"));
                return;
            }

            // NOTE: requirePurchaseToRate is not present in the current Ratings config,
            // so we skip the purchase gate. If/when that field is added, this is where
            // we'd check plugin.getDatabase().hasPlayerPurchasedFromShop(...).

            UUID shopId = shop.getId();
            try {
                // Insert (or replace on PK (rater_uuid, shop_id)) the rating row.
                com.kyuubisoft.shops.data.ShopRating rating = new com.kyuubisoft.shops.data.ShopRating(
                    raterUuid, playerRef.getUsername(), shopId,
                    stars, "", System.currentTimeMillis());
                database.saveRating(rating);

                // Recalculate average/count from the full rating set and persist.
                java.util.List<com.kyuubisoft.shops.data.ShopRating> allRatings =
                    database.loadRatings(shopId);
                shop.recalculateRating(allRatings);
                database.saveShop(shop);
            } catch (Exception e) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.rate.failed")).color("#FF5555"));
                return;
            }

            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.rate.success", shop.getName(), stars))
                .color("#55FF55"));
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

    /**
     * Phase 3 (mailbox refactor): {@code /ksshop collect} is now a compat
     * shortcut that points the player at the Mailbox block. shopBalance is
     * a buyback pool — the owner's earnings live in the mailbox and are
     * claimed via the Mailbox UI (place a Mailbox_Block, press F).
     *
     * <p>Kept as a subcommand so existing muscle memory + help docs do not
     * dead-end. Prints the unclaimed mail count so the player knows whether
     * it is worth setting up a mailbox right now.
     */
    private class CollectCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        CollectCmd() { super("collect", "Open your mailbox to claim earnings"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.collect", true)) {
                player.sendMessage(Message.raw(plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            ShopI18n i18n = plugin.getI18n();

            int unclaimed = 0;
            try {
                unclaimed = plugin.getMailboxService().countUnclaimedForPlayer(playerRef.getUuid());
            } catch (Exception e) {
                // Non-fatal — still show the redirect message
            }

            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.collect.use_mailbox", unclaimed)
            ).color("#FFD700"));
        }
    }

    private class DepositCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        DepositCmd() {
            super("deposit", "Deposit money into your shop balance");
            withRequiredArg("amount", "Amount to deposit", ArgTypes.INTEGER);
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
            ShopService shopService = plugin.getShopService();
            UUID playerUuid = playerRef.getUuid();

            // Parse amount
            String[] parts = ctx.getInputString().split("\\s+", 3);
            int amount;
            try {
                amount = Integer.parseInt(parts.length > 2 ? parts[2] : "0");
            } catch (NumberFormatException e) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.error.invalid_arguments",
                        "/ksshop deposit <amount>")).color("#FF5555"));
                return;
            }

            if (amount <= 0) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.error.invalid_arguments",
                        "/ksshop deposit <amount>")).color("#FF5555"));
                return;
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

            boolean success = shopService.depositToShop(playerRef, targetShop.getId(), amount);
            if (success) {
                String formatted = plugin.getEconomyBridge().format(amount);
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.deposit.success",
                        formatted, targetShop.getName())).color("#55FF55"));
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.deposit.new_balance",
                        plugin.getEconomyBridge().format(targetShop.getShopBalance()))
                ).color("#96a9be"));
            } else {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.deposit.failed")).color("#FF5555"));
            }
        }
    }

    /**
     * P3 polish: {@code /ksshop stats} — personal shop statistics overview.
     *
     * <p>Aggregates revenue/tax/rating/sales across every shop the player owns and
     * shows pending (uncollected) earnings. Rating is a weighted average:
     * {@code sum(avgRating * count) / sum(count)}. Sale count is queried directly
     * against {@code shop_transactions} via
     * {@link ShopDatabase#countSalesForOwner(UUID)}.
     */
    private class StatsCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        StatsCmd() { super("stats", "View your shop statistics"); }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            ShopI18n i18n = plugin.getI18n();
            UUID playerUuid = playerRef.getUuid();

            List<ShopData> ownedShops = plugin.getShopManager().getShopsByOwner(playerUuid);
            if (ownedShops.isEmpty()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.stats.no_shops")).color("#FFD700"));
                return;
            }

            int maxShops = com.kyuubisoft.shops.util.PermissionLimits.resolveMaxShops(
                player, plugin.getShopConfig().getData().playerShops.maxShopsPerPlayer);

            double totalRevenue = 0;
            double totalTax = 0;
            double ratingStarSum = 0;
            int ratingCountSum = 0;
            for (ShopData s : ownedShops) {
                totalRevenue += s.getTotalRevenue();
                totalTax += s.getTotalTaxPaid();
                ratingStarSum += s.getAverageRating() * s.getTotalRatings();
                ratingCountSum += s.getTotalRatings();
            }

            // Phase 3: pending earnings now means unclaimed mailbox entries,
            // not shopBalance (which is now strictly a buyback pool).
            int unclaimedMails = 0;
            try {
                unclaimedMails = plugin.getMailboxService().countUnclaimedForPlayer(playerUuid);
            } catch (Exception ignored) {}

            int totalSales = plugin.getDatabase().countSalesForOwner(playerUuid);
            double avgRating = ratingCountSum > 0 ? ratingStarSum / ratingCountSum : 0.0;

            var econ = plugin.getEconomyBridge();
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.stats.header")).color("#FFD700"));
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.stats.shops_owned",
                    ownedShops.size(), maxShops)).color("#bfcdd5"));
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.stats.total_revenue",
                    econ.format(totalRevenue))).color("#bfcdd5"));
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.stats.total_tax",
                    econ.format(totalTax))).color("#bfcdd5"));
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.stats.total_sales",
                    totalSales)).color("#bfcdd5"));
            if (ratingCountSum > 0) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.stats.avg_rating",
                        String.format("%.1f", avgRating), ratingCountSum)).color("#bfcdd5"));
            } else {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.stats.no_ratings")).color("#96a9be"));
            }
            if (unclaimedMails > 0) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.stats.pending_mails",
                        unclaimedMails)).color("#55FF55"));
            }
        }
    }

    /**
     * P3 polish: {@code /ksshop transfer <shopId> <playerName> [confirm]} —
     * transfers ownership of a player-owned shop to another online player.
     *
     * <p>Uses an in-memory 60-second confirmation window keyed by the sender's
     * UUID (mirrors {@link DeleteCmd}). Requires the target to be online so we
     * can validate they exist and notify them of the ownership change.
     */
    private class TransferCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        TransferCmd() {
            super("transfer", "Transfer shop ownership to another player");
            withRequiredArg("shopId", "Shop ID or name", ArgTypes.STRING);
            withRequiredArg("playerName", "Target player name", ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (!player.hasPermission("ks.shop.user.edit", true)) {
                player.sendMessage(Message.raw(
                    plugin.getI18n().get("shop.error.no_permission")).color("#FF5555"));
                return;
            }

            if (CoreBridge.showcaseWriteGuard(player, playerRef)) return;

            ShopI18n i18n = plugin.getI18n();
            ShopManager shopManager = plugin.getShopManager();
            UUID playerUuid = playerRef.getUuid();

            // Parse arguments: /ksshop transfer <shopId> <targetName> [confirm]
            String[] parts = ctx.getInputString().split("\\s+", 5);
            String shopIdentifier = parts.length > 2 ? parts[2].trim() : "";
            String targetName = parts.length > 3 ? parts[3].trim() : "";
            boolean confirm = parts.length > 4 && "confirm".equalsIgnoreCase(parts[4].trim());

            if (shopIdentifier.isEmpty() || targetName.isEmpty()) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.error.invalid_arguments",
                        "/ksshop transfer <shopId> <playerName>")).color("#FF5555"));
                return;
            }

            // Resolve shop: UUID first, then case-insensitive name lookup (mirrors VisitCmd).
            ShopData shop = null;
            try {
                UUID uuid = UUID.fromString(shopIdentifier);
                shop = shopManager.getShop(uuid);
            } catch (IllegalArgumentException ignored) {
                // Fall through to name lookup
            }
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
                    i18n.get(playerRef, "shop.error.shop_not_found", shopIdentifier))
                    .color("#FF5555"));
                return;
            }

            // Ownership check.
            if (shop.getOwnerUuid() == null || !shop.getOwnerUuid().equals(playerUuid)) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.transfer.not_owner")).color("#FF5555"));
                return;
            }

            // Resolve target player by name — must be online.
            PlayerRef targetRef = null;
            for (PlayerRef candidate : world.getPlayerRefs()) {
                if (candidate != null && targetName.equalsIgnoreCase(candidate.getUsername())) {
                    targetRef = candidate;
                    break;
                }
            }

            if (targetRef == null) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.transfer.target_offline")).color("#FF5555"));
                return;
            }

            UUID targetUuid = targetRef.getUuid();
            if (targetUuid == null) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.transfer.target_not_found", targetName))
                    .color("#FF5555"));
                return;
            }

            // Reject self-transfer.
            if (targetUuid.equals(playerUuid)) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.transfer.self_target")).color("#FF5555"));
                return;
            }

            UUID shopId = shop.getId();
            String shopName = shop.getName();
            String resolvedTargetName = targetRef.getUsername();

            // Expire any stale confirmation first.
            TransferConfirm pending = pendingTransfers.get(playerUuid);
            long now = System.currentTimeMillis();
            if (pending != null && pending.expiresAt < now) {
                pendingTransfers.remove(playerUuid);
                pending = null;
            }

            if (confirm && pending != null
                    && pending.shopId.equals(shopId)
                    && pending.targetUuid.equals(targetUuid)) {
                // Execute the transfer.
                pendingTransfers.remove(playerUuid);

                boolean ok = plugin.getShopService().transferShop(
                    shopId, targetUuid, resolvedTargetName);
                if (!ok) {
                    player.sendMessage(Message.raw(
                        i18n.get(playerRef, "shop.transfer.failed")).color("#FF5555"));
                    return;
                }

                // Respawn the NPC so its nameplate/skin reflects the new owner.
                try {
                    plugin.getNpcManager().respawnNpc(shop);
                } catch (Exception ignored) {
                    // Non-fatal — the transfer itself already persisted.
                }

                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.transfer.confirmed_sender",
                        shopName, resolvedTargetName)).color("#55FF55"));

                // Notify the new owner if they are still online.
                try {
                    targetRef.sendMessage(Message.raw(
                        i18n.get(targetRef, "shop.transfer.confirmed_target",
                            playerRef.getUsername(), shopName)).color("#55FF55"));
                } catch (Exception ignored) {
                    // Target may have logged out between resolution and send — ignore.
                }
                return;
            }

            // Stage a new confirmation.
            pendingTransfers.put(playerUuid,
                new TransferConfirm(shopId, resolvedTargetName, targetUuid,
                    now + TRANSFER_CONFIRM_WINDOW_MS));
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.transfer.confirm_prompt",
                    shopIdentifier, resolvedTargetName, shopName)).color("#FFAA00"));
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
