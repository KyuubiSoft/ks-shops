package com.kyuubisoft.shops.interaction;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.ui.MailboxPage;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

/**
 * Custom block interaction for mailbox blocks (registered as "mailbox_block_use").
 *
 * When a player presses F on a mailbox block:
 * - Toggles the door open/close animation.
 * - Opens the MailboxPage listing all unclaimed mails for the interacting
 *   player (global mail list - every mailbox shows the same list).
 */
public class MailboxBlockBlockInteraction extends SimpleBlockInteraction {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final String STATE_OPEN = "Open";
    private static final String STATE_DEFAULT = "default";

    public static final BuilderCodec<MailboxBlockBlockInteraction> CODEC = BuilderCodec.builder(
        MailboxBlockBlockInteraction.class, MailboxBlockBlockInteraction::new, SimpleBlockInteraction.CODEC
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
            // 1. Toggle door animation (reuses the shop block model + anim)
            toggleDoorState(world, targetBlock);

            // 2. Open the mailbox page for the interacting player
            ShopPlugin plugin = ShopPlugin.getInstance();
            if (plugin == null) {
                context.getState().state = InteractionState.Finished;
                return;
            }

            // Resolve player + refs from the interaction context (same pattern as
            // ShopBlockBlockInteraction).
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

            try {
                MailboxPage page = new MailboxPage(playerRef, player, plugin);
                player.getPageManager().openCustomPage(ref, store, page);
            } catch (Exception e) {
                LOGGER.warning("[Mailbox] Failed to open page: " + e.getMessage());
            }

            context.getState().state = InteractionState.Finished;
        } catch (Exception e) {
            LOGGER.warning("Mailbox block interaction error: " + e.getMessage());
            e.printStackTrace();
            context.getState().state = InteractionState.Failed;
        }
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
            LOGGER.warning("Failed to toggle mailbox block door: " + e.getMessage());
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
        // IMPORTANT: must return a protocol-level packet instance, not the
        // server-side interaction class. Matches ShopBlockBlockInteraction.
        return new com.hypixel.hytale.protocol.SimpleBlockInteraction();
    }
}
