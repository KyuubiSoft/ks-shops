package com.kyuubisoft.shops.commands;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.system.Argument;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.ShopManager;
import com.kyuubisoft.shops.ui.ShopAdminPage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Admin shop commands: /ksshopadmin (alias: /kssa)
 *
 * All subcommands require ks.shop.admin permission.
 */
public class ShopAdminCommand extends AbstractCommandCollection {

    @Override
    protected boolean canGeneratePermission() { return false; }

    private final ShopPlugin plugin;

    public ShopAdminCommand(ShopPlugin plugin) {
        super("ksshopadmin", "KyuubiSoft Shop Admin");
        addAliases("kssa");
        requirePermission("ks.shop.admin");
        this.plugin = plugin;

        addSubCommand(new AdminPanelCmd());
        addSubCommand(new CreateAdminCmd());
        addSubCommand(new EditAdminCmd());
        addSubCommand(new DeleteAdminCmd());
        addSubCommand(new DeletePlayerCmd());
        addSubCommand(new ClosePlayerCmd());
        addSubCommand(new OpenPlayerCmd());
        addSubCommand(new SetTaxCmd());
        addSubCommand(new FeatureCmd());
        addSubCommand(new StatsCmd());
        addSubCommand(new LogCmd());
        addSubCommand(new BlacklistCmd());
        addSubCommand(new ReloadCmd());
        addSubCommand(new RespawnNpcsCmd());
        addSubCommand(new DeleteNearestNpcCmd());
        addSubCommand(new CreateRentalCmd());
        addSubCommand(new CreateRentalAuctionCmd());
        addSubCommand(new DeleteRentalCmd());
        addSubCommand(new ListRentalsCmd());
        addSubCommand(new ForceExpireRentalCmd());
    }

    // ==================== ADMIN COMMANDS ====================

    private class AdminPanelCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        AdminPanelCmd() {
            super("admin", "Open admin panel");
            requirePermission("ks.shop.admin");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            ShopAdminPage page = new ShopAdminPage(playerRef, player, plugin);
            player.getPageManager().openCustomPage(ref, store, page);
        }
    }

    private class CreateAdminCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        CreateAdminCmd() {
            super("createadmin", "Create an admin shop at your position");
            requirePermission("ks.shop.admin");
            withRequiredArg("title", "Shop title", ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            ShopI18n i18n = plugin.getI18n();

            // Parse greedy title (everything after the subcommand token).
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String title = parts.length > 2 ? parts[2] : "";
            if (title.isBlank()) {
                player.sendMessage(Message.raw(
                    "Usage: /kssa createadmin <title>"
                ).color("#FF5555"));
                return;
            }

            // Resolve spawn position from the player's transform.
            TransformComponent tc = player.getTransformComponent();
            if (tc == null) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.create.failed")
                ).color("#FF5555"));
                return;
            }
            Vector3d pos = tc.getPosition();
            float rotY = 0.0f;
            try {
                var rotation = tc.getRotation();
                if (rotation != null) rotY = rotation.y;
            } catch (Throwable ignored) {}
            String worldName = world.getName();

            var result = plugin.getShopService().createAdminShop(
                title, "", "", worldName, pos.x, pos.y, pos.z
            );
            if (!result.isSuccess()) {
                String errorKey = result.getErrorKey() != null
                    ? result.getErrorKey()
                    : "shop.create.failed";
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, errorKey)
                ).color("#FF5555"));
                return;
            }

            ShopData newShop = result.getShop();
            newShop.setNpcRotY(rotY);
            plugin.getNpcManager().registerShopInWorld(newShop);
            final float finalRotY = rotY;
            world.execute(() ->
                plugin.getNpcManager().spawnNpcAtPosition(newShop, world, pos, finalRotY));

            player.sendMessage(Message.raw(
                plugin.getI18n().get("shop.admin.create.success", title)
            ).color("#44FF44"));
            player.sendMessage(Message.raw(
                "Use /kssa editadmin to add items to this shop."
            ).color("#96a9be"));
        }
    }

    /**
     * Opens the drag-and-drop editor on the nearest admin shop. Same
     * mechanism as /ksshop edit but drops the owner-check so the admin
     * can populate shops they do not personally own.
     */
    private class EditAdminCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        EditAdminCmd() {
            super("editadmin", "Edit the nearest admin shop");
            requirePermission("ks.shop.admin");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            ShopI18n i18n = plugin.getI18n();

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
            for (ShopData shop : plugin.getShopManager().getAllShops()) {
                if (!shop.isAdminShop()) continue;
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
                player.sendMessage(Message.raw(
                    "No admin shop in this world. Use /kssa createadmin <title> first."
                ).color("#FF5555"));
                return;
            }

            if (!plugin.getSessionManager().lockEditor(nearest.getId(), playerRef.getUuid())) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.error.editor_locked")
                ).color("#FF5555"));
                return;
            }

            final ShopData targetShop = nearest;
            final UUID shopUuid = targetShop.getId();
            final UUID playerUuid = playerRef.getUuid();
            try {
                com.kyuubisoft.shops.ui.ShopEditPage editPage =
                    new com.kyuubisoft.shops.ui.ShopEditPage(playerRef, player, plugin, targetShop);
                com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow window =
                    new com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow(
                        editPage.getStagingContainer());
                window.registerCloseEvent(event -> {
                    editPage.onWindowClose();
                    plugin.getSessionManager().unlockEditor(shopUuid, playerUuid);
                });
                player.getPageManager().openCustomPageWithWindows(ref, store, editPage, window);
                player.sendMessage(Message.raw(
                    "Editing admin shop: " + targetShop.getName()
                ).color("#55FF55"));
            } catch (Exception e) {
                plugin.getSessionManager().unlockEditor(shopUuid, playerUuid);
                player.sendMessage(Message.raw(
                    "Failed to open admin shop editor: " + e.getMessage()
                ).color("#FF5555"));
            }
        }
    }

    private class DeleteAdminCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        DeleteAdminCmd() {
            super("deleteadmin", "Delete an admin shop");
            requirePermission("ks.shop.admin");
            withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String shopIdStr = parts.length > 2 ? parts[2] : "";

            UUID shopId;
            try {
                shopId = UUID.fromString(shopIdStr);
            } catch (IllegalArgumentException e) {
                shopId = UUID.nameUUIDFromBytes(shopIdStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            ShopManager shopManager = plugin.getShopManager();
            ShopData shop = shopManager.getShop(shopId);
            if (shop == null) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }
            if (!shop.isAdminShop()) {
                ctx.sender().sendMessage(Message.raw("Shop '" + shop.getName() + "' is not an admin shop.").color("#FF5555"));
                return;
            }

            String shopName = shop.getName();
            plugin.getNpcManager().despawnNpc(shopId);
            shopManager.deleteShop(shopId);
            ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.delete.success", shopName)).color("#44FF44"));
        }
    }

    private class DeletePlayerCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        DeletePlayerCmd() {
            super("deleteplayer", "Force-delete a player shop");
            requirePermission("ks.shop.admin");
            withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String shopIdStr = parts.length > 2 ? parts[2] : "";

            UUID shopId;
            try {
                shopId = UUID.fromString(shopIdStr);
            } catch (IllegalArgumentException e) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }

            ShopManager shopManager = plugin.getShopManager();
            ShopData shop = shopManager.getShop(shopId);
            if (shop == null) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }
            if (!shop.isPlayerShop()) {
                ctx.sender().sendMessage(Message.raw("Shop '" + shop.getName() + "' is not a player shop. Use /kssa deleteadmin.").color("#FF5555"));
                return;
            }

            String shopName = shop.getName();
            plugin.getNpcManager().despawnNpc(shopId);
            shopManager.deleteShop(shopId);
            ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.delete.success", shopName)).color("#44FF44"));
        }
    }

    private class ClosePlayerCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }

        private final Argument<?, String> shopIdArg;
        private final Argument<?, String> reasonArg;

        ClosePlayerCmd() {
            super("closeplayer", "Force-close a player shop");
            requirePermission("ks.shop.admin");
            shopIdArg = withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
            reasonArg = withOptionalArg("reason", "Optional reason text", ArgTypes.GREEDY_STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String shopIdStr = ctx.get(shopIdArg);
            String reason = ctx.provided(reasonArg) ? ctx.get(reasonArg) : "Admin action";

            UUID shopId;
            try {
                shopId = UUID.fromString(shopIdStr);
            } catch (IllegalArgumentException e) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }

            ShopData shop = plugin.getShopManager().getShop(shopId);
            if (shop == null) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }

            shop.setOpen(false);
            plugin.getNpcManager().despawnNpc(shopId);
            ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.close.success", shop.getName(), reason)).color("#44FF44"));
        }
    }

    private class OpenPlayerCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        OpenPlayerCmd() {
            super("openplayer", "Force-open a player shop");
            requirePermission("ks.shop.admin");
            withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String shopIdStr = parts.length > 2 ? parts[2] : "";

            UUID shopId;
            try {
                shopId = UUID.fromString(shopIdStr);
            } catch (IllegalArgumentException e) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }

            ShopData shop = plugin.getShopManager().getShop(shopId);
            if (shop == null) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }

            shop.setOpen(true);
            plugin.getNpcManager().spawnNpc(shop);
            ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.open.success", shop.getName())).color("#44FF44"));
        }
    }

    private class SetTaxCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        SetTaxCmd() {
            super("settax", "Set transaction tax rate");
            requirePermission("ks.shop.admin");
            withRequiredArg("rate", "Tax rate (0.0-1.0)", ArgTypes.FLOAT);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String rateStr = parts.length > 2 ? parts[2] : "";

            double rate;
            try {
                rate = Double.parseDouble(rateStr);
            } catch (NumberFormatException e) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.invalid_arguments", "/kssa settax <0.0-1.0>")).color("#FF5555"));
                return;
            }

            if (rate < 0.0 || rate > 1.0) {
                ctx.sender().sendMessage(Message.raw("Tax rate must be between 0.0 and 1.0.").color("#FF5555"));
                return;
            }

            ShopConfig config = plugin.getShopConfig();
            double percent = rate * 100.0;
            config.getData().tax.buyTaxPercent = percent;
            config.getData().tax.sellTaxPercent = percent;
            config.getData().tax.enabled = rate > 0.0;
            config.save();

            ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.settax.success", String.format("%.1f", percent))).color("#44FF44"));
        }
    }

    private class FeatureCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        FeatureCmd() {
            super("feature", "Feature a shop");
            requirePermission("ks.shop.admin");
            withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 3);
            String shopIdStr = parts.length > 2 ? parts[2] : "";

            UUID shopId;
            try {
                shopId = UUID.fromString(shopIdStr);
            } catch (IllegalArgumentException e) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }

            ShopData shop = plugin.getShopManager().getShop(shopId);
            if (shop == null) {
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.shop_not_found", shopIdStr)).color("#FF5555"));
                return;
            }

            int durationDays = plugin.getShopConfig().getData().featured.durationDays;
            shop.setFeatured(true);
            shop.setFeaturedUntil(System.currentTimeMillis() + (durationDays * 86_400_000L));
            ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.feature.success", shop.getName(), String.valueOf(durationDays))).color("#44FF44"));
        }
    }

    private class StatsCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        StatsCmd() {
            super("stats", "View economy statistics");
            requirePermission("ks.shop.admin");
        }

        protected void executeSync(CommandContext ctx) {
            ShopManager shopManager = plugin.getShopManager();
            Collection<ShopData> allShops = shopManager.getAllShops();

            int totalShops = allShops.size();
            int adminCount = 0;
            int playerCount = 0;
            int openCount = 0;
            int featuredCount = 0;
            double totalRevenue = 0;
            double totalTax = 0;
            long now = System.currentTimeMillis();

            for (ShopData shop : allShops) {
                if (shop.isAdminShop()) adminCount++;
                else playerCount++;
                if (shop.isOpen()) openCount++;
                if (shop.isFeatured() && shop.getFeaturedUntil() > now) featuredCount++;
                totalRevenue += shop.getTotalRevenue();
                totalTax += shop.getTotalTaxPaid();
            }

            String header = plugin.getI18n().get("shop.admin.stats.header",
                java.time.LocalDate.now().toString());
            ctx.sender().sendMessage(Message.raw(header).color("#FFD700"));
            ctx.sender().sendMessage(Message.raw("Total shops: " + totalShops
                + " (" + adminCount + " admin, " + playerCount + " player)").color("#AAAAAA"));
            ctx.sender().sendMessage(Message.raw("Open: " + openCount
                + " | Featured: " + featuredCount).color("#AAAAAA"));
            ctx.sender().sendMessage(Message.raw("Total revenue: "
                + String.format("%.2f", totalRevenue)).color("#AAAAAA"));
            ctx.sender().sendMessage(Message.raw("Total tax collected: "
                + String.format("%.2f", totalTax)).color("#AAAAAA"));
            ctx.sender().sendMessage(Message.raw("Active NPCs: "
                + plugin.getNpcManager().getActiveNpcCount()).color("#AAAAAA"));
        }
    }

    private class LogCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        LogCmd() {
            super("log", "View player transaction log");
            requirePermission("ks.shop.admin");
            withRequiredArg("player", "Player name", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            // TODO: Show player transaction log
            ctx.sender().sendMessage(Message.raw("Transaction log coming soon...").color("#FFD700"));
        }
    }

    private class BlacklistCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }

        private final Argument<?, String> actionArg;
        private final Argument<?, String> itemIdArg;

        BlacklistCmd() {
            super("blacklist", "Manage item blacklist");
            requirePermission("ks.shop.admin");
            actionArg = withRequiredArg("action", "add/remove/list", ArgTypes.STRING);
            itemIdArg = withOptionalArg("itemId", "Item ID (required for add/remove)", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String action = ctx.get(actionArg).toLowerCase();
            String itemId = ctx.provided(itemIdArg) ? ctx.get(itemIdArg) : "";

            ShopConfig config = plugin.getShopConfig();
            List<String> blacklist = config.getData().itemBlacklist;

            switch (action) {
                case "add" -> {
                    if (itemId.isEmpty()) {
                        ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.invalid_arguments", "/kssa blacklist add <itemId>")).color("#FF5555"));
                        return;
                    }
                    if (blacklist.contains(itemId)) {
                        ctx.sender().sendMessage(Message.raw("Item '" + itemId + "' is already blacklisted.").color("#FF5555"));
                        return;
                    }
                    blacklist.add(itemId);
                    config.save();
                    ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.blacklist.added", itemId)).color("#44FF44"));
                }
                case "remove" -> {
                    if (itemId.isEmpty()) {
                        ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.invalid_arguments", "/kssa blacklist remove <itemId>")).color("#FF5555"));
                        return;
                    }
                    if (!blacklist.remove(itemId)) {
                        ctx.sender().sendMessage(Message.raw("Item '" + itemId + "' is not blacklisted.").color("#FF5555"));
                        return;
                    }
                    config.save();
                    ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.blacklist.removed", itemId)).color("#44FF44"));
                }
                case "list" -> {
                    if (blacklist.isEmpty()) {
                        ctx.sender().sendMessage(Message.raw("Blacklist is empty.").color("#AAAAAA"));
                    } else {
                        ctx.sender().sendMessage(Message.raw("Blacklisted items (" + blacklist.size() + "):").color("#FFD700"));
                        for (String item : blacklist) {
                            ctx.sender().sendMessage(Message.raw("  - " + item).color("#AAAAAA"));
                        }
                    }
                }
                default -> ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.error.invalid_arguments", "/kssa blacklist <add|remove|list> [itemId]")).color("#FF5555"));
            }
        }
    }

    private class ReloadCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        ReloadCmd() {
            super("reload", "Reload config and localization");
            requirePermission("ks.shop.admin");
        }

        protected void executeSync(CommandContext ctx) {
            try {
                plugin.getShopConfig().reload();
                plugin.getI18n().load();
                plugin.getDirectoryService().rebuildIndex();
                ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.reload.success")).color("#44FF44"));
            } catch (Exception e) {
                ctx.sender().sendMessage(Message.raw("Reload failed: " + e.getMessage()).color("#FF5555"));
            }
        }
    }

    /**
     * /kssa deletenpc - deletes the shop NPC closest to the caller (within
     * 5 blocks). Force-deletes the shop entirely including NPC, DB row, and
     * any pending editor session. Player-facing so we can read the caller's
     * world position without needing a UUID argument.
     */
    private class DeleteNearestNpcCmd extends AbstractPlayerCommand {
        private static final double MAX_RANGE_SQ = 5.0 * 5.0;

        @Override protected boolean canGeneratePermission() { return false; }
        DeleteNearestNpcCmd() {
            super("deletenpc", "Delete the shop NPC closest to you (within 5 blocks)");
            requirePermission("ks.shop.admin");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            if (world == null) {
                ctx.sender().sendMessage(Message.raw("No world context available").color("#FF5555"));
                return;
            }

            double px, py, pz;
            try {
                TransformComponent tc = player.getTransformComponent();
                if (tc == null || tc.getPosition() == null) {
                    ctx.sender().sendMessage(Message.raw("Could not read your position").color("#FF5555"));
                    return;
                }
                Vector3d pos = tc.getPosition();
                px = pos.x; py = pos.y; pz = pos.z;
            } catch (Exception e) {
                ctx.sender().sendMessage(Message.raw("Position read failed: " + e.getMessage()).color("#FF5555"));
                return;
            }

            String worldName = world.getName();
            ShopData nearest = null;
            double nearestDistSq = Double.MAX_VALUE;
            for (ShopData shop : plugin.getShopManager().getAllShops()) {
                if (!shop.isPlayerShop()) continue;
                if (worldName == null || !worldName.equals(shop.getWorldName())) continue;
                double dx = shop.getPosX() - px;
                double dy = shop.getPosY() - py;
                double dz = shop.getPosZ() - pz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < nearestDistSq) {
                    nearestDistSq = d;
                    nearest = shop;
                }
            }

            if (nearest == null || nearestDistSq > MAX_RANGE_SQ) {
                ctx.sender().sendMessage(Message.raw(
                    "No shop NPC within 5 blocks. Move closer or use /kssa deleteplayer <shopId>."
                ).color("#FFAA00"));
                return;
            }

            String shopName = nearest.getName();
            String ownerName = nearest.getOwnerName() != null ? nearest.getOwnerName() : "unknown";
            UUID shopId = nearest.getId();

            try {
                plugin.getNpcManager().despawnNpc(shopId);
                plugin.getShopManager().deleteShop(shopId);
                ctx.sender().sendMessage(Message.raw(
                    "Deleted shop '" + shopName + "' (owner: " + ownerName + ")"
                ).color("#44FF44"));
            } catch (Exception e) {
                ctx.sender().sendMessage(Message.raw("Delete failed: " + e.getMessage()).color("#FF5555"));
            }
        }
    }

    /**
     * /kssa respawnnpcs - despawns and respawns every shop NPC in the caller's
     * current world. Handy after a mod update changes the NPC role/skin or
     * when entities got stuck after a crash. Uses ShopData as the source of
     * truth so any drift between DB and live entity gets corrected.
     */
    private class RespawnNpcsCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        RespawnNpcsCmd() {
            super("respawnnpcs", "Despawn + respawn all shop NPCs in your current world");
            requirePermission("ks.shop.admin");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            if (world == null) {
                ctx.sender().sendMessage(Message.raw("No world context available").color("#FF5555"));
                return;
            }
            try {
                int count = plugin.getNpcManager().respawnAll(world);
                if (count == 0) {
                    ctx.sender().sendMessage(Message.raw("No shops to respawn in " + world.getName()).color("#FFD700"));
                } else {
                    ctx.sender().sendMessage(Message.raw("Respawning " + count + " shop NPC(s) in " + world.getName() + "...").color("#44FF44"));
                }
            } catch (Exception e) {
                ctx.sender().sendMessage(Message.raw("Respawn failed: " + e.getMessage()).color("#FF5555"));
            }
        }
    }

    // ==================== RENTAL STATION COMMANDS ====================

    /**
     * /kssa createrental &lt;displayName&gt; [pricePerDay] [maxDays]
     *
     * Creates a FIXED-price rental slot at the caller's position. The
     * slot is persisted immediately; the renter shop (and vacant NPC)
     * spawns on the first successful rent.
     */
    private class CreateRentalCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }

        private final Argument<?, String> displayNameArg;
        private final Argument<?, Integer> pricePerDayArg;
        private final Argument<?, Integer> maxDaysArg;

        CreateRentalCmd() {
            super("createrental", "Create a fixed-price rental slot at your position");
            requirePermission("ks.shop.admin");
            displayNameArg = withRequiredArg("displayName", "Slot display name", ArgTypes.STRING);
            pricePerDayArg = withOptionalArg("pricePerDay", "Gold per day (default from config)", ArgTypes.INTEGER);
            maxDaysArg = withOptionalArg("maxDays", "Max rental days (default from config)", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            String displayName = ctx.get(displayNameArg);
            ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
            int pricePerDay = ctx.provided(pricePerDayArg)
                ? Math.max(0, ctx.get(pricePerDayArg))
                : cfg.rentalStations.defaultPricePerDay;
            int maxDays = ctx.provided(maxDaysArg)
                ? Math.max(1, ctx.get(maxDaysArg))
                : cfg.rentalStations.defaultMaxDays;

            TransformComponent tc = player.getTransformComponent();
            if (tc == null) {
                player.sendMessage(Message.raw("No position available").color("#FF5555"));
                return;
            }
            Vector3d pos = tc.getPosition();
            float rotY = 0.0f;
            try {
                var rotation = tc.getRotation();
                if (rotation != null) rotY = rotation.y;
            } catch (Throwable ignored) {}

            var slot = plugin.getRentalService().createFixedSlot(
                displayName, world.getName(),
                pos.x, pos.y, pos.z, rotY,
                pricePerDay, maxDays, null
            );

            // Immediately spawn the vacant shell NPC so the admin sees it.
            ShopData shell = plugin.getShopManager().getShopByRentalSlotId(slot.getId());
            if (shell != null) {
                plugin.getNpcManager().registerShopInWorld(shell);
                final float fRotY = rotY;
                world.execute(() ->
                    plugin.getNpcManager().spawnNpcAtPosition(shell, world, pos, fRotY));
            }

            player.sendMessage(Message.raw(
                "Rental slot '" + displayName + "' created ("
                + pricePerDay + "g/day, max " + maxDays + "d)"
            ).color("#44FF44"));
            player.sendMessage(Message.raw(
                "Slot id: " + slot.getId()
            ).color("#96a9be"));
        }
    }

    /**
     * /kssa createrentalauction &lt;displayName&gt; [minBid] [bidIncrement]
     *     [durationMinutes] [rentalDays]
     *
     * Creates an AUCTION-mode rental slot at the caller's position and
     * immediately starts the first auction round.
     */
    private class CreateRentalAuctionCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }

        private final Argument<?, String> displayNameArg;
        private final Argument<?, Integer> minBidArg;
        private final Argument<?, Integer> bidIncrementArg;
        private final Argument<?, Integer> durationMinutesArg;
        private final Argument<?, Integer> rentalDaysArg;

        CreateRentalAuctionCmd() {
            super("createrentalauction", "Create an auction-mode rental slot at your position");
            requirePermission("ks.shop.admin");
            displayNameArg = withRequiredArg("displayName", "Slot display name", ArgTypes.STRING);
            minBidArg = withOptionalArg("minBid", "Minimum bid (default from config)", ArgTypes.INTEGER);
            bidIncrementArg = withOptionalArg("bidIncrement", "Bid increment (default from config)", ArgTypes.INTEGER);
            durationMinutesArg = withOptionalArg("durationMinutes", "Auction duration in minutes (default from config)", ArgTypes.INTEGER);
            rentalDaysArg = withOptionalArg("rentalDays", "Rental days for winner (default from config)", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            String displayName = ctx.get(displayNameArg);
            ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();
            int minBid = ctx.provided(minBidArg)
                ? Math.max(0, ctx.get(minBidArg))
                : cfg.rentalStations.defaultMinBid;
            int bidIncrement = ctx.provided(bidIncrementArg)
                ? Math.max(1, ctx.get(bidIncrementArg))
                : cfg.rentalStations.defaultBidIncrement;
            int durationMinutes = ctx.provided(durationMinutesArg)
                ? Math.max(1, ctx.get(durationMinutesArg))
                : cfg.rentalStations.defaultAuctionDurationMinutes;
            int rentalDays = ctx.provided(rentalDaysArg)
                ? Math.max(1, ctx.get(rentalDaysArg))
                : cfg.rentalStations.defaultMaxDays;

            TransformComponent tc = player.getTransformComponent();
            if (tc == null) {
                player.sendMessage(Message.raw("No position available").color("#FF5555"));
                return;
            }
            Vector3d pos = tc.getPosition();
            float rotY = 0.0f;
            try {
                var rotation = tc.getRotation();
                if (rotation != null) rotY = rotation.y;
            } catch (Throwable ignored) {}

            var slot = plugin.getRentalService().createAuctionSlot(
                displayName, world.getName(),
                pos.x, pos.y, pos.z, rotY,
                minBid, bidIncrement, durationMinutes, rentalDays, null
            );

            // Immediately spawn the vacant shell NPC.
            ShopData shell = plugin.getShopManager().getShopByRentalSlotId(slot.getId());
            if (shell != null) {
                plugin.getNpcManager().registerShopInWorld(shell);
                final float fRotY = rotY;
                world.execute(() ->
                    plugin.getNpcManager().spawnNpcAtPosition(shell, world, pos, fRotY));
            }

            player.sendMessage(Message.raw(
                "Auction slot '" + displayName + "' created (min "
                + minBid + "g, +" + bidIncrement + "g, " + durationMinutes
                + "min, " + rentalDays + " rental days)"
            ).color("#44FF44"));
            player.sendMessage(Message.raw(
                "Slot id: " + slot.getId()
            ).color("#96a9be"));
        }
    }

    /**
     * /kssa deleterental &lt;slotId&gt;
     *
     * Removes a rental slot. If a rental is currently active, the
     * renter is refunded (items + balance via mailbox) first.
     */
    private class DeleteRentalCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        DeleteRentalCmd() {
            super("deleterental", "Delete a rental slot (force-expires any active rental)");
            requirePermission("ks.shop.admin");
            withRequiredArg("slotId", "Slot UUID", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().trim().split("\\s+", 3);
            String slotIdStr = parts.length > 2 ? parts[2] : "";
            UUID slotId;
            try {
                slotId = UUID.fromString(slotIdStr);
            } catch (IllegalArgumentException e) {
                ctx.sender().sendMessage(Message.raw(
                    "Invalid slot UUID: " + slotIdStr
                ).color("#FF5555"));
                return;
            }
            boolean deleted = plugin.getRentalService().deleteSlot(slotId);
            if (deleted) {
                ctx.sender().sendMessage(Message.raw(
                    "Deleted rental slot " + slotId
                ).color("#44FF44"));
            } else {
                ctx.sender().sendMessage(Message.raw(
                    "No rental slot with id " + slotId
                ).color("#FF5555"));
            }
        }
    }

    /**
     * /kssa listrentals - chat list of every rental slot in the caller's
     * current world with its current state.
     */
    private class ListRentalsCmd extends AbstractPlayerCommand {
        @Override protected boolean canGeneratePermission() { return false; }
        ListRentalsCmd() {
            super("listrentals", "List rental slots in your current world");
            requirePermission("ks.shop.admin");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                              PlayerRef playerRef, World world) {
            Player player = ctx.senderAs(Player.class);
            Collection<com.kyuubisoft.shops.rental.RentalSlotData> all =
                plugin.getRentalService().getAllSlots();
            int count = 0;
            for (com.kyuubisoft.shops.rental.RentalSlotData slot : all) {
                if (!world.getName().equals(slot.getWorldName())) continue;
                count++;
                String state;
                if (slot.getRentedBy() != null) {
                    long remaining = slot.getRentedUntil() - System.currentTimeMillis();
                    long hours = Math.max(0, remaining / 3_600_000L);
                    state = "RENTED by " + slot.getRentedByName() + " (" + hours + "h left)";
                } else if (slot.isAuctionOpen()) {
                    state = "AUCTION open, high bid " + slot.getCurrentHighBid();
                } else {
                    state = "VACANT";
                }
                player.sendMessage(Message.raw(
                    "- " + slot.getDisplayName() + " [" + slot.getMode() + "] "
                    + state + " | id=" + slot.getId()
                ).color("#96a9be"));
            }
            if (count == 0) {
                player.sendMessage(Message.raw(
                    "No rental slots in " + world.getName()
                ).color("#FFD700"));
            } else {
                player.sendMessage(Message.raw(
                    count + " rental slot(s) total"
                ).color("#44FF44"));
            }
        }
    }

    /**
     * /kssa forceexpirerental &lt;slotId&gt; - admin override that runs the
     * normal expiry path immediately (items mailed to renter, backing
     * shop deleted, slot returns to vacant).
     */
    private class ForceExpireRentalCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        ForceExpireRentalCmd() {
            super("forceexpirerental", "Force-expire a rental (mails items back)");
            requirePermission("ks.shop.admin");
            withRequiredArg("slotId", "Slot UUID", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().trim().split("\\s+", 3);
            String slotIdStr = parts.length > 2 ? parts[2] : "";
            UUID slotId;
            try {
                slotId = UUID.fromString(slotIdStr);
            } catch (IllegalArgumentException e) {
                ctx.sender().sendMessage(Message.raw(
                    "Invalid slot UUID: " + slotIdStr
                ).color("#FF5555"));
                return;
            }
            var slot = plugin.getRentalService().getSlot(slotId);
            if (slot == null) {
                ctx.sender().sendMessage(Message.raw(
                    "No rental slot with id " + slotId
                ).color("#FF5555"));
                return;
            }
            if (slot.getRentedBy() == null) {
                ctx.sender().sendMessage(Message.raw(
                    "Slot is not rented - nothing to expire"
                ).color("#FFD700"));
                return;
            }
            plugin.getRentalService().expireSlot(
                slot,
                com.kyuubisoft.shops.rental.event.RentalExpiredEvent.Reason.FORCE_EXPIRED);
            ctx.sender().sendMessage(Message.raw(
                "Force-expired rental: " + slot.getDisplayName()
            ).color("#44FF44"));
        }
    }
}
