package com.kyuubisoft.shops.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

import com.hypixel.hytale.server.core.Message;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.service.ShopSessionManager;
import com.kyuubisoft.shops.ui.ShopBrowsePage;
import com.kyuubisoft.shops.ui.ShopEditPage;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NPC action executed when a player presses F on a shop NPC.
 *
 * Standalone replacement for Core's ActionCitizenInteract - looks up the shop
 * by NPC UUID via {@link ShopNpcManager#getShopIdForNpcUuid(UUID)} and opens
 * either the owner editor or the visitor browser.
 *
 * Wired up at plugin start via NPCPlugin.registerCoreComponentType("ShopNpcInteract", ...).
 * Referenced from citizen-roles/Shop_Keeper_Role.json as the HasInteracted action.
 */
public class ActionShopInteract extends ActionBase {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    public ActionShopInteract(BuilderActionBase builderActionBase) {
        super(builderActionBase);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> ref, Role role, InfoProvider sensorInfo,
                              double dt, Store<EntityStore> store) {
        boolean baseResult = super.canExecute(ref, role, sensorInfo, dt, store);
        Ref<EntityStore> target = role.getStateSupport().getInteractionIterationTarget();
        return baseResult && target != null;
    }

    @Override
    public boolean execute(Ref<EntityStore> ref, Role role, InfoProvider sensorInfo,
                           double dt, Store<EntityStore> store) {
        super.execute(ref, role, sensorInfo, dt, store);

        try {
            Ref<EntityStore> playerReference = role.getStateSupport().getInteractionIterationTarget();
            if (playerReference == null) return false;

            PlayerRef playerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
            if (playerRef == null) return false;

            Player player = store.getComponent(playerReference, Player.getComponentType());
            if (player == null) return false;

            UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
            if (uuidComponent == null) return false;
            UUID npcUuid = uuidComponent.getUuid();

            ShopPlugin plugin = ShopPlugin.getInstance();
            if (plugin == null) {
                LOGGER.warning("[ShopNpc] ShopPlugin instance is null");
                return false;
            }

            UUID shopId = plugin.getNpcManager().getShopIdForNpcUuid(npcUuid);
            if (shopId == null) {
                LOGGER.fine("[ShopNpc] No shop registered for NPC UUID " + npcUuid);
                return false;
            }

            ShopData shop = plugin.getShopManager().getShop(shopId);
            if (shop == null) {
                LOGGER.fine("[ShopNpc] Shop " + shopId + " not found in manager");
                return false;
            }

            UUID playerUuid = playerRef.getUuid();
            boolean isOwner = shop.isPlayerShop()
                && shop.getOwnerUuid() != null
                && shop.getOwnerUuid().equals(playerUuid);

            // Run on the world thread so page opening is thread-safe. The NPC
            // action runs inside the NPC role system which is driven by the
            // world tick, but page managers prefer explicit world.execute.
            World world = player.getWorld();
            if (world == null) return false;

            world.execute(() -> {
                try {
                    // Vacant rental shell: ADMIN-type ShopData with rentalSlotId set,
                    // open=false on purpose. Route to the rental confirm/bid page
                    // BEFORE the closed-shop block below, otherwise vacant slots
                    // would silently swallow the F-key. Mirrors the routing in
                    // ShopBlockInteraction.onPlayerInteract.
                    if (shop.getRentalSlotId() != null && shop.isAdminShop()) {
                        com.kyuubisoft.shops.rental.RentalService rentalService =
                            plugin.getRentalService();
                        if (rentalService != null) {
                            com.kyuubisoft.shops.rental.RentalSlotData slot =
                                rentalService.getSlot(shop.getRentalSlotId());
                            if (slot != null && slot.getRentedBy() == null) {
                                if (slot.getMode() == com.kyuubisoft.shops.rental.RentalSlotData.Mode.AUCTION) {
                                    com.kyuubisoft.shops.rental.ui.RentalBidPage bid =
                                        new com.kyuubisoft.shops.rental.ui.RentalBidPage(
                                            playerRef, player, plugin, slot);
                                    player.getPageManager().openCustomPage(
                                        playerReference, store, bid);
                                } else {
                                    com.kyuubisoft.shops.rental.ui.RentalRentConfirmPage rent =
                                        new com.kyuubisoft.shops.rental.ui.RentalRentConfirmPage(
                                            playerRef, player, plugin, slot);
                                    player.getPageManager().openCustomPage(
                                        playerReference, store, rent);
                                }
                                return;
                            }
                        }
                    }

                    // Block non-owners on closed shops. Owners keep full access
                    // (editor) so they can still manage their own shop while it
                    // is temporarily closed. Mirrors the check in
                    // ShopBlockInteraction.handleInteraction.
                    if (!shop.isOpen() && !isOwner) {
                        String msg = plugin.getI18n().get(playerRef, "shop.error.closed");
                        player.sendMessage(Message.raw(msg).color("#FF5555"));
                        return;
                    }

                    if (isOwner) {
                        ShopSessionManager sessionManager = plugin.getSessionManager();
                        if (sessionManager == null || !sessionManager.lockEditor(shop.getId(), playerUuid)) {
                            // Already being edited by someone else - fall back to browse
                            ShopBrowsePage browsePage = new ShopBrowsePage(playerRef, player, plugin, shop);
                            player.getPageManager().openCustomPage(playerReference, store, browsePage);
                            return;
                        }

                        ShopEditPage editPage = new ShopEditPage(playerRef, player, plugin, shop);
                        com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow window =
                            new com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow(
                                editPage.getStagingContainer());
                        window.registerCloseEvent(event -> {
                            editPage.onWindowClose();
                            sessionManager.unlockEditor(shop.getId(), playerUuid);
                        });
                        player.getPageManager().openCustomPageWithWindows(playerReference, store, editPage, window);
                    } else {
                        ShopBrowsePage browsePage = new ShopBrowsePage(playerRef, player, plugin, shop);
                        player.getPageManager().openCustomPage(playerReference, store, browsePage);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[ShopNpc] Failed to open page for shop " + shop.getName(), e);
                }
            });

            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[ShopNpc] Interaction error", e);
            return false;
        }
    }
}
