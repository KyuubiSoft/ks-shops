package com.kyuubisoft.shops.interaction;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.bridge.CoreBridge;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.npc.ShopNpcManager;
import com.kyuubisoft.shops.service.CreateShopResult;
import com.kyuubisoft.shops.service.ShopService;
import com.kyuubisoft.shops.util.PlayerInventoryAccess;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Right-click / F interaction for the Shop_NPC_Token item ("Shop Business License").
 *
 * Flow:
 * 1. Validate the player can create another shop (max shops, economy, showcase guard).
 * 2. Call {@link ShopService#createPlayerShop(PlayerRef, String, String, String, String, double, double, double)}
 *    with the player's current position as the shop location.
 * 3. If successful, spawn a standalone NPC via {@link ShopNpcManager#spawnNpcAtPlayer(ShopData, World, Vector3d, float)}.
 * 4. Consume one Shop_NPC_Token from the player's inventory.
 *
 * This replaces the legacy Shop_Block placement flow. The NPC IS the shop entry
 * point - no block is required. Owners can manage the NPC skin from the editor's
 * SHOP NPC settings panel.
 */
public class ShopNpcTokenInteraction extends SimpleInstantInteraction {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    /** ItemId of the token - used to identify and consume the stack in hand. */
    public static final String TOKEN_ITEM_ID = "Shop_NPC_Token";

    public static final BuilderCodec<ShopNpcTokenInteraction> CODEC = BuilderCodec.builder(
        ShopNpcTokenInteraction.class, ShopNpcTokenInteraction::new, SimpleInstantInteraction.CODEC
    ).build();

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType,
                            @Nonnull InteractionContext interactionContext,
                            @Nonnull CooldownHandler cooldownHandler) {
        var commandBuffer = interactionContext.getCommandBuffer();
        if (commandBuffer == null) {
            interactionContext.getState().state = InteractionState.Failed;
            return;
        }

        Ref<EntityStore> ref = interactionContext.getEntity();
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null) {
            interactionContext.getState().state = InteractionState.Failed;
            return;
        }

        World world = commandBuffer.getExternalData().getWorld();
        Store<EntityStore> store = commandBuffer.getExternalData().getStore();
        if (world == null || store == null) {
            interactionContext.getState().state = InteractionState.Failed;
            return;
        }

        try {
            ShopPlugin plugin = ShopPlugin.getInstance();
            if (plugin == null) {
                interactionContext.getState().state = InteractionState.Failed;
                return;
            }

            ShopI18n i18n = plugin.getI18n();
            ShopService shopService = plugin.getShopService();
            ShopConfig cfg = plugin.getShopConfig();

            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                interactionContext.getState().state = InteractionState.Failed;
                return;
            }

            // Showcase write guard (no-op if Core absent)
            if (CoreBridge.showcaseWriteGuard(player, playerRef)) {
                interactionContext.getState().state = InteractionState.Finished;
                return;
            }

            // Permission check (mirrors /ksshop create)
            if (!player.hasPermission("ks.shop.user.create", true)) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.error.no_permission")).color("#FF5555"));
                interactionContext.getState().state = InteractionState.Finished;
                return;
            }

            // Resolve player position (needed both for replant and fresh-create).
            TransformComponent tc = player.getTransformComponent();
            if (tc == null) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.create.failed")).color("#FF5555"));
                interactionContext.getState().state = InteractionState.Failed;
                return;
            }
            final Vector3d pos = tc.getPosition();
            Vector3f rotation = null;
            try {
                rotation = tc.getRotation();
            } catch (Throwable ignored) {
                // Rotation is optional - used only for the NPC facing direction.
            }
            final float rotY = rotation != null ? rotation.y : 0.0f;
            String worldName = world.getName();

            // --- Replant check: if the player already has a packed shop, reactivate
            // the oldest one at this location instead of creating a fresh one. The
            // packed shop still counts against the max-shops cap, but that's OK
            // because we're not adding a new row — we're just unpacking an
            // existing one. This path completely bypasses the max-shops check.
            UUID playerUuid = playerRef.getUuid();
            ShopData packedShop = plugin.getShopManager().getShopsByOwner(playerUuid).stream()
                .filter(ShopData::isPacked)
                .min(Comparator.comparingLong(ShopData::getCreatedAt))
                .orElse(null);

            if (packedShop != null) {
                final ShopData replantTarget = packedShop;
                final UUID replantId = replantTarget.getId();
                world.execute(() -> {
                    try {
                        boolean ok = shopService.replantShop(
                            player, playerRef, replantId, world,
                            pos.x, pos.y, pos.z, rotY);
                        if (ok) {
                            consumeOneToken(player);
                            player.sendMessage(Message.raw(
                                i18n.get(playerRef, "shop.token.replanted",
                                    replantTarget.getName())
                            ).color("#55FF55"));
                        } else {
                            player.sendMessage(Message.raw(
                                i18n.get(playerRef, "shop.token.failed",
                                    i18n.get(playerRef, "shop.create.failed"))
                            ).color("#FF5555"));
                        }
                    } catch (Exception e) {
                        LOGGER.warning("Shop NPC token: replant failed for shop "
                            + replantId + ": " + e.getMessage());
                    }
                });
                interactionContext.getState().state = InteractionState.Finished;
                return;
            }

            // Max shops per player (pre-check so we don't waste the token).
            // Honors per-rank ks.shop.limit.shops.N permission overrides via
            // PermissionLimits — highest matching N wins, falling back to
            // the global config default.
            int ownedCount = plugin.getShopManager().getShopsByOwner(playerUuid).size();
            int maxShops = com.kyuubisoft.shops.util.PermissionLimits.resolveMaxShops(
                player, cfg.getData().playerShops.maxShopsPerPlayer);
            if (ownedCount >= maxShops) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.token.not_enough_shops")).color("#FF5555"));
                interactionContext.getState().state = InteractionState.Finished;
                return;
            }

            String shopName = buildDefaultShopName(plugin, playerRef.getUsername());

            CreateShopResult result = shopService.createPlayerShop(
                playerRef, player, shopName, "", "", worldName, pos.x, pos.y, pos.z
            );

            if (!result.isSuccess()) {
                String errorKey = result.getErrorKey() != null
                    ? result.getErrorKey()
                    : "shop.create.failed";
                String message = i18n.get(playerRef, errorKey);
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.token.failed", message)).color("#FF5555"));
                interactionContext.getState().state = InteractionState.Finished;
                return;
            }

            ShopData newShop = result.getShop();
            newShop.setNpcRotY(rotY);

            // Register + spawn NPC at the player's location (runs on the world thread).
            ShopNpcManager npcManager = plugin.getNpcManager();
            npcManager.registerShopInWorld(newShop);

            world.execute(() -> {
                try {
                    npcManager.spawnNpcAtPosition(newShop, world, pos, rotY);
                } catch (Exception e) {
                    LOGGER.warning("Shop NPC token: spawn failed for shop "
                        + newShop.getId() + ": " + e.getMessage());
                }
            });

            // Consume one token from the player's inventory.
            consumeOneToken(player);

            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.token.spawned")).color("#55FF55"));

            interactionContext.getState().state = InteractionState.Finished;

        } catch (Exception e) {
            LOGGER.warning("Shop NPC token interaction error: " + e.getMessage());
            e.printStackTrace();
            interactionContext.getState().state = InteractionState.Failed;
        }
    }

    /**
     * Builds a sensible default shop name that is GUARANTEED to be unique
     * against {@link ShopPlugin#getShopManager()}. Tries {@code "<user>'s Shop"}
     * first, then falls back to numbered suffixes ({@code "#2"}, {@code "#3"},
     * ...) until an unused slot is found. Previously this method just counted
     * the owner's existing shops and appended {@code " Shop N"} — which could
     * collide with a pre-existing name from another player or a deleted-then-
     * recreated shop, triggering the server-side uniqueness check and
     * surfacing "name already exists" to the user.
     */
    private String buildDefaultShopName(ShopPlugin plugin, String username) {
        String base = (username == null || username.isBlank()) ? "Player" : username.trim();

        String first = clampName(base + "'s Shop");
        if (!isShopNameTaken(plugin, first)) {
            return first;
        }

        for (int n = 2; n <= 999; n++) {
            String candidate = clampName(base + "'s Shop #" + n);
            if (!isShopNameTaken(plugin, candidate)) {
                return candidate;
            }
        }

        // Safety valve — should never hit unless the player already has 999
        // shops named "<base>'s Shop #N". Fall back to a timestamp tail.
        return clampName(base + " Shop " + (System.currentTimeMillis() % 100000));
    }

    private static String clampName(String name) {
        return name.length() > 48 ? name.substring(0, 48) : name;
    }

    private static boolean isShopNameTaken(ShopPlugin plugin, String candidate) {
        if (plugin == null || candidate == null) return false;
        for (ShopData existing : plugin.getShopManager().getAllShops()) {
            if (existing == null || existing.getName() == null) continue;
            if (existing.getName().equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    /**
     * Consumes exactly one Shop_NPC_Token from the player's hotbar (or storage as fallback).
     * Preserves the rest of the stack if the player is holding more than one.
     */
    private void consumeOneToken(Player player) {
        if (player == null) return;
        try {
            PlayerInventoryAccess inv = PlayerInventoryAccess.of(player);
            if (removeOneFromContainer(inv.getHotbar())) {
                inv.markChanged();
                return;
            }
            if (removeOneFromContainer(inv.getStorage())) {
                inv.markChanged();
                return;
            }
            LOGGER.warning("Shop NPC token: could not locate token to consume for "
                + player.getPlayerRef().getUsername());
        } catch (Exception e) {
            LOGGER.warning("Shop NPC token: consume failed: " + e.getMessage());
        }
    }

    /**
     * Removes one Shop_NPC_Token from the given container. Returns true if a stack
     * was found and decremented.
     */
    private boolean removeOneFromContainer(ItemContainer container) {
        if (container == null) return false;
        for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty()) continue;
            String id = stack.getItemId();
            if (id == null) continue;
            // DynamicTooltips virtual IDs use a "__dtt_" separator - strip before comparing.
            String baseId = id.contains("__") ? id.substring(0, id.indexOf("__")) : id;
            if (!TOKEN_ITEM_ID.equals(baseId)) continue;

            int qty = stack.getQuantity();
            if (qty <= 1) {
                container.removeItemStackFromSlot(i);
            } else {
                container.setItemStackForSlot(i, new ItemStack(id, qty - 1));
            }
            return true;
        }
        return false;
    }

}
