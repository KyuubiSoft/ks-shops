package com.kyuubisoft.shops.interaction;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.InteractionType;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.bridge.CoreBridge;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopType;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.npc.ShopNpcManager;
import com.kyuubisoft.shops.service.ShopManager;
import com.kyuubisoft.shops.service.ShopSessionManager;
import com.kyuubisoft.shops.ui.ShopBrowsePage;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles F-key interaction with shop NPCs and shop block positions.
 *
 * Registered as a global PlayerInteractEvent listener.
 * When a player presses F near a shop NPC or block:
 * - Looks up the shop via NPC entity ID (NpcManager reverse lookup)
 * - Or via proximity to a shop block position (distance check)
 * - Opens the appropriate page based on ownership:
 *   - Owner -> ShopEditPage (editor mode)
 *   - Non-owner -> ShopBrowsePage (buyer mode)
 *   - Admin shops -> always ShopBrowsePage
 *
 * Registration pattern (in ShopPlugin.setup()):
 * <pre>
 *   ShopBlockInteraction interaction = new ShopBlockInteraction(plugin);
 *   getEventRegistry().registerGlobal(PlayerInteractEvent.class, interaction::onPlayerInteract);
 * </pre>
 */
public class ShopBlockInteraction {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private final ShopPlugin plugin;

    public ShopBlockInteraction(ShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== EVENT HANDLER ====================

    /**
     * Global PlayerInteractEvent handler for shop NPC/block interaction.
     *
     * Checks both NPC entity interaction (target entity is a tracked shop NPC)
     * and block proximity (target block is near a shop position).
     */
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle F-key (Use) interactions
        if (event.getActionType() != InteractionType.Use) return;
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (player == null) return;

        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null) return;

        World world = player.getWorld();
        if (world == null) return;

        ShopNpcManager npcManager = plugin.getNpcManager();
        ShopManager shopManager = plugin.getShopManager();
        if (npcManager == null || shopManager == null) return;

        ShopData shop = null;

        // --- Path 1: NPC entity interaction ---
        // Check if the target entity is a shop NPC
        var targetEntity = event.getTargetEntity();
        var targetRef = event.getTargetRef();

        if (targetEntity != null) {
            UUID targetUuid = targetEntity.getUuid();
            if (targetUuid != null) {
                // Lookup by NPC entity UUID
                UUID shopId = npcManager.getShopIdForNpcUuid(targetUuid);
                if (shopId != null) {
                    shop = shopManager.getShop(shopId);
                }
            }
        }

        // Also check by entity ID string (stored in shopNpcIds)
        if (shop == null && targetRef != null) {
            String refId = targetRef.toString();
            UUID shopId = npcManager.getShopIdForNpc(refId);
            if (shopId != null) {
                shop = shopManager.getShop(shopId);
            }
        }

        // --- Path 2: Block proximity interaction ---
        // If no NPC match, check if the interacted block is near a shop position
        if (shop == null) {
            var targetBlock = event.getTargetBlock();
            if (targetBlock != null) {
                float interactionRange = plugin.getShopConfig().getData().npc.interactionRange;
                shop = npcManager.findNearestShop(
                    world.getName(),
                    targetBlock.x + 0.5, targetBlock.y + 0.5, targetBlock.z + 0.5,
                    interactionRange
                );

                // Validate proximity: the block must be very close to the shop position
                // (within 2 blocks, to avoid false matches from distant shops)
                if (shop != null) {
                    double dx = shop.getPosX() - (targetBlock.x + 0.5);
                    double dy = shop.getPosY() - (targetBlock.y + 0.5);
                    double dz = shop.getPosZ() - (targetBlock.z + 0.5);
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > 4.0) { // > 2 blocks
                        shop = null;
                    }
                }
            }
        }

        // No shop found — not our interaction
        if (shop == null) return;

        // Cancel the event to prevent other handlers from processing it
        event.setCancelled(true);

        // Resolve Ref/Store for page opening (player.getReference() -> Ref<EntityStore>)
        Ref<EntityStore> entityRef = player.getReference();
        if (entityRef == null) return;

        Store<EntityStore> store = entityRef.getStore();
        if (store == null) return;

        // Handle the interaction
        handleInteraction(player, playerRef, entityRef, store, shop);
    }

    // ==================== INTERACTION LOGIC ====================

    /**
     * Handles a shop interaction for a player.
     *
     * Determines whether to open the editor (owner) or browse page (visitor).
     * Can be called from both NPC interaction and block interaction paths.
     *
     * @param player    the interacting Player entity
     * @param playerRef the player reference
     * @param ref       the entity ref for page opening
     * @param store     the entity store for page opening
     * @param shop      the shop being interacted with
     */
    public void handleInteraction(Player player, PlayerRef playerRef,
                                   Ref<EntityStore> ref, Store<EntityStore> store,
                                   ShopData shop) {
        if (player == null || playerRef == null || shop == null) return;

        UUID playerUuid = playerRef.getUuid();
        ShopI18n i18n = plugin.getI18n();

        // Check if shop is open (unless the player is the owner)
        boolean isOwner = shop.isPlayerShop()
            && shop.getOwnerUuid() != null
            && shop.getOwnerUuid().equals(playerUuid);

        if (!shop.isOpen() && !isOwner) {
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.closed")).color("#FF5555"));
            return;
        }

        // Permission check for browsing
        if (!isOwner && !player.hasPermission("ks.shop.user.browse", true)) {
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.no_permission")).color("#FF5555"));
            return;
        }

        // Showcase write guard (if applicable)
        if (isOwner && CoreBridge.showcaseWriteGuard(player, playerRef)) {
            return;
        }

        if (isOwner) {
            // Owner: open shop editor page
            openEditorPage(player, playerRef, ref, store, shop);
        } else {
            // Visitor: open shop browse page
            openBrowsePage(player, playerRef, ref, store, shop);
        }
    }

    /**
     * Opens the ShopBrowsePage for a visitor (buyer/seller).
     */
    private void openBrowsePage(Player player, PlayerRef playerRef,
                                 Ref<EntityStore> ref, Store<EntityStore> store,
                                 ShopData shop) {
        ShopI18n i18n = plugin.getI18n();

        // Mark player as active buyer
        plugin.getSessionManager().addActiveBuyer(playerRef.getUuid());

        // Wrap in I18n context for language-aware page building
        CoreBridge.runWithI18n(playerRef, () -> {
            try {
                ShopBrowsePage page = new ShopBrowsePage(playerRef, player, plugin, shop);
                player.getPageManager().openCustomPage(ref, store, page);

                LOGGER.fine("Opened shop browse page for " + playerRef.getUsername()
                    + " at shop '" + shop.getName() + "'");
            } catch (Exception e) {
                LOGGER.warning("Failed to open browse page for " + playerRef.getUsername()
                    + ": " + e.getMessage());
                player.sendMessage(Message.raw(i18n.get(playerRef, "shop.error.open_failed")).color("#FF5555"));
            }
        });
    }

    /**
     * Opens the ShopEditPage for the shop owner.
     * Acquires an editor lock to prevent concurrent editing.
     */
    private void openEditorPage(Player player, PlayerRef playerRef,
                                 Ref<EntityStore> ref, Store<EntityStore> store,
                                 ShopData shop) {
        ShopI18n i18n = plugin.getI18n();
        ShopSessionManager sessionManager = plugin.getSessionManager();
        UUID playerUuid = playerRef.getUuid();
        UUID shopId = shop.getId();

        // Acquire editor lock
        if (!sessionManager.lockEditor(shopId, playerUuid)) {
            UUID currentEditor = sessionManager.getEditorPlayer(shopId);
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.error.editor_locked")).color("#FF5555"));
            LOGGER.fine("Editor lock denied for " + playerRef.getUsername()
                + " on shop '" + shop.getName() + "' (locked by " + currentEditor + ")");
            return;
        }

        CoreBridge.runWithI18n(playerRef, () -> {
            try {
                // TODO: Open ShopEditPage when implemented
                // ShopEditPage page = new ShopEditPage(playerRef, player, plugin, shop);
                // player.getPageManager().openCustomPage(ref, store, page);

                // For now, open the browse page as a placeholder (owner can still see their shop)
                ShopBrowsePage page = new ShopBrowsePage(playerRef, player, plugin, shop);
                player.getPageManager().openCustomPage(ref, store, page);

                LOGGER.fine("Opened shop editor for " + playerRef.getUsername()
                    + " at shop '" + shop.getName() + "'");
            } catch (Exception e) {
                // Release the lock if page opening fails
                sessionManager.unlockEditor(shopId, playerUuid);
                LOGGER.warning("Failed to open editor page for " + playerRef.getUsername()
                    + ": " + e.getMessage());
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.error.open_failed")).color("#FF5555"));
            }
        });
    }
}
