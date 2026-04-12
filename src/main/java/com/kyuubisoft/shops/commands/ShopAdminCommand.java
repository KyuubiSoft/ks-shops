package com.kyuubisoft.shops.commands;

import com.hypixel.hytale.server.core.entity.entities.Player;
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

    private class CreateAdminCmd extends CommandBase {
        @Override protected boolean canGeneratePermission() { return false; }
        CreateAdminCmd() {
            super("createadmin", "Create an admin shop");
            requirePermission("ks.shop.admin");
            withRequiredArg("id", "Shop ID", ArgTypes.STRING);
            withRequiredArg("title", "Shop title", ArgTypes.GREEDY_STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 4);
            String id = parts.length > 2 ? parts[2] : "";
            String title = parts.length > 3 ? parts[3] : "";
            // TODO: Create admin shop at sender location
            ctx.sender().sendMessage(Message.raw(plugin.getI18n().get("shop.admin.create.success", title)).color("#44FF44"));
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
        ClosePlayerCmd() {
            super("closeplayer", "Force-close a player shop");
            requirePermission("ks.shop.admin");
            withRequiredArg("shopId", "Shop ID", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 4);
            String shopIdStr = parts.length > 2 ? parts[2] : "";
            String reason = parts.length > 3 ? parts[3] : "Admin action";

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
        BlacklistCmd() {
            super("blacklist", "Manage item blacklist");
            requirePermission("ks.shop.admin");
            withRequiredArg("action", "add/remove/list", ArgTypes.STRING);
        }

        protected void executeSync(CommandContext ctx) {
            String[] parts = ctx.getInputString().split("\\s+", 4);
            String action = parts.length > 2 ? parts[2].toLowerCase() : "";
            String itemId = parts.length > 3 ? parts[3] : "";

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
}
