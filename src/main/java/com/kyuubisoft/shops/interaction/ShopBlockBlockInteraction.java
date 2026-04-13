package com.kyuubisoft.shops.interaction;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.Interaction;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.service.ShopManager;
import com.kyuubisoft.shops.ui.ShopBrowsePage;
import com.kyuubisoft.shops.ui.ShopEditPage;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Custom block interaction for shop blocks (registered as "shop_block_use").
 *
 * When a player presses F on a shop block:
 * - If a shop exists at this location → open the shop UI
 *   - Owner → ShopEditPage (with ContainerWindow for drag&drop editing)
 *   - Visitor → ShopBrowsePage (read-only buy/sell)
 * - If no shop exists → toggle the door open/close animation only
 */
public class ShopBlockBlockInteraction extends SimpleBlockInteraction {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final String STATE_OPEN = "Open";
    private static final String STATE_DEFAULT = "default";

    public static final BuilderCodec<ShopBlockBlockInteraction> CODEC = BuilderCodec.builder(
        ShopBlockBlockInteraction.class, ShopBlockBlockInteraction::new, SimpleBlockInteraction.CODEC
    ).build();

    @Override
    protected void interactWithBlock(@Nonnull World world,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull InteractionType interactionType,
                                     @Nonnull InteractionContext context,
                                     ItemStack heldItemStack,
                                     @Nonnull Vector3i targetBlock,
                                     @Nonnull CooldownHandler cooldownHandler) {
        try {
            // 1. Toggle door animation
            toggleDoorState(world, targetBlock);

            // 2. Find shop at this location
            ShopPlugin plugin = ShopPlugin.getInstance();
            if (plugin == null) {
                context.getState().state = InteractionState.Finished;
                return;
            }

            ShopManager manager = plugin.getShopManager();
            ShopData shop = findShopAtBlock(manager, world.getName(), targetBlock);

            if (shop == null) {
                // No shop linked to this block — just play animation
                context.getState().state = InteractionState.Finished;
                return;
            }

            // 3. Get the interacting player
            Ref<EntityStore> ref = context.getEntity();
            if (ref == null) {
                context.getState().state = InteractionState.Finished;
                return;
            }
            Store<EntityStore> store = ref.getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                context.getState().state = InteractionState.Finished;
                return;
            }
            PlayerRef playerRef = player.getPlayerRef();
            UUID playerUuid = playerRef.getUuid();

            // 4. Determine if player is owner or visitor
            boolean isOwner = shop.isPlayerShop()
                && shop.getOwnerUuid() != null
                && shop.getOwnerUuid().equals(playerUuid);

            if (isOwner) {
                openEditorPage(plugin, player, playerRef, ref, store, shop, playerUuid);
            } else {
                openBrowsePage(plugin, player, playerRef, ref, store, shop);
            }

            context.getState().state = InteractionState.Finished;
        } catch (Exception e) {
            LOGGER.warning("Shop block interaction error: " + e.getMessage());
            e.printStackTrace();
            context.getState().state = InteractionState.Failed;
        }
    }

    /**
     * Find a shop whose location is within 2 blocks of the target block.
     */
    private ShopData findShopAtBlock(ShopManager manager, String worldName, Vector3i targetBlock) {
        ShopData best = null;
        double bestDist = 4.0; // 2 blocks squared
        for (ShopData shop : manager.getAllShops()) {
            if (!worldName.equals(shop.getWorldName())) continue;
            double dx = shop.getPosX() - targetBlock.x;
            double dy = shop.getPosY() - targetBlock.y;
            double dz = shop.getPosZ() - targetBlock.z;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                best = shop;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Toggle the block between default and Open state for animation.
     */
    private void toggleDoorState(World world, Vector3i targetBlock) {
        try {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z);
            WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            if (chunk == null) return;

            BlockType currentType = chunk.getBlockType(targetBlock);
            if (currentType == null) return;

            String currentState = currentType.getStateForBlock(currentType);
            if (currentState == null) currentState = STATE_DEFAULT;
            String newState = STATE_DEFAULT.equals(currentState) ? STATE_OPEN : STATE_DEFAULT;

            String newBlockKey = currentType.getBlockKeyForState(newState);
            if (newBlockKey == null) return;

            int newBlockIndex = BlockType.getAssetMap().getIndex(newBlockKey);
            if (newBlockIndex == Integer.MIN_VALUE) return;

            int rotation = chunk.getRotationIndex(targetBlock.x, targetBlock.y, targetBlock.z);
            BlockType newBlockType = currentType.getBlockForState(newState);
            if (newBlockType == null) newBlockType = currentType;

            chunk.setBlock(targetBlock.x, targetBlock.y, targetBlock.z,
                newBlockIndex, newBlockType, rotation, 0, 0);
        } catch (Exception e) {
            LOGGER.warning("Failed to toggle shop block door: " + e.getMessage());
        }
    }

    private void openEditorPage(ShopPlugin plugin, Player player, PlayerRef playerRef,
                                Ref<EntityStore> ref, Store<EntityStore> store,
                                ShopData shop, UUID playerUuid) {
        try {
            if (!plugin.getSessionManager().lockEditor(shop.getId(), playerUuid)) {
                player.getPlayerRef().sendMessage(
                    com.hypixel.hytale.server.core.Message.raw(
                        plugin.getI18n().get("shop.edit.locked")).color("#FF5555"));
                return;
            }

            ShopEditPage editPage = new ShopEditPage(playerRef, player, plugin, shop);
            com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow window =
                new com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow(editPage.getStagingContainer());
            window.registerCloseEvent(event -> {
                editPage.onWindowClose();
                plugin.getSessionManager().unlockEditor(shop.getId(), playerUuid);
            });
            player.getPageManager().openCustomPageWithWindows(ref, store, editPage, window);
        } catch (Exception e) {
            LOGGER.warning("Failed to open shop editor: " + e.getMessage());
            plugin.getSessionManager().unlockEditor(shop.getId(), playerUuid);
        }
    }

    private void openBrowsePage(ShopPlugin plugin, Player player, PlayerRef playerRef,
                                Ref<EntityStore> ref, Store<EntityStore> store, ShopData shop) {
        try {
            if (!shop.isOpen()) {
                player.getPlayerRef().sendMessage(
                    com.hypixel.hytale.server.core.Message.raw(
                        plugin.getI18n().get("shop.buy.shop_closed")).color("#FF5555"));
                return;
            }
            ShopBrowsePage browsePage = new ShopBrowsePage(playerRef, player, plugin, shop);
            player.getPageManager().openCustomPage(ref, store, browsePage);
        } catch (Exception e) {
            LOGGER.warning("Failed to open shop browse page: " + e.getMessage());
        }
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType interactionType,
                                             @Nonnull InteractionContext context,
                                             ItemStack heldItemStack,
                                             @Nonnull World world,
                                             @Nonnull Vector3i targetBlock) {
        // Server-side only
    }

    @Override
    protected Interaction generatePacket() {
        return null;
    }
}
