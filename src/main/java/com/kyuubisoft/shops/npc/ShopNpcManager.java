package com.kyuubisoft.shops.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.bridge.CoreBridge;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.service.ShopManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages NPC spawning and despawning for shop entities.
 *
 * Handles the full NPC lifecycle:
 * - Lazy per-world spawning on first player entry (AddPlayerToWorldEvent)
 * - NPC spawn at shop position with configurable offset behind the shop block
 * - Despawn on shop deletion/plugin shutdown
 * - Respawn on rename/move
 * - Reverse lookup: npcEntityId -> shopId for interaction handling
 *
 * PITFALLS:
 * - Entity operations MUST run inside world.execute() (thread safety)
 * - PersistentModel creates naked duplicates on chunk reload — track and re-spawn
 * - Do NOT remove PersistentModel component (causes crash)
 */
public class ShopNpcManager {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    /** NPC role name for interactable shop NPCs (IDLE, F-key enabled) */
    private static final String NPC_ROLE_INTERACTABLE = "KS_NPC_Interactable_Role";
    private static final String NPC_ROLE_IDLE = "KS_NPC_Idle_Role";

    /** Offset distance behind the shop block where the NPC spawns */
    private static final double NPC_OFFSET_DISTANCE = 1.2;

    private final ShopPlugin plugin;
    private final ShopConfig config;
    private final ShopManager shopManager;

    /** Tracks which worlds have had their NPCs spawned (lazy init) */
    private final Set<String> spawnedWorlds = ConcurrentHashMap.newKeySet();

    /** Maps shopId -> spawned NPC entity ID string for tracking */
    private final Map<UUID, String> shopNpcIds = new ConcurrentHashMap<>();

    /** Reverse map: npcEntityId -> shopId (for interaction lookups) */
    private final Map<String, UUID> npcToShopMap = new ConcurrentHashMap<>();

    /** Maps worldName -> set of shopIds for per-world lazy spawning */
    private final Map<String, Set<UUID>> worldShops = new ConcurrentHashMap<>();

    /** Maps shopId -> spawned entity Ref for despawn operations */
    private final Map<UUID, Ref<EntityStore>> entityRefs = new ConcurrentHashMap<>();

    /** Maps shopId -> spawned NPCEntity UUID (for chunk reload detection) */
    private final Map<UUID, UUID> entityUuids = new ConcurrentHashMap<>();

    public ShopNpcManager(ShopPlugin plugin, ShopConfig config, ShopManager shopManager) {
        this.plugin = plugin;
        this.config = config;
        this.shopManager = shopManager;
        buildWorldShopIndex();
    }

    // ==================== INDEX ====================

    /**
     * Builds the worldName -> shopIds index from all loaded shops.
     * Called once at startup and whenever shops are bulk-loaded.
     */
    private void buildWorldShopIndex() {
        worldShops.clear();
        for (ShopData shop : shopManager.getAllShops()) {
            String worldName = shop.getWorldName();
            if (worldName == null || worldName.isEmpty()) continue;
            worldShops.computeIfAbsent(worldName, k -> ConcurrentHashMap.newKeySet())
                .add(shop.getId());
        }
    }

    /**
     * Registers a shop in the world index (called when a new shop is created).
     */
    public void registerShopInWorld(ShopData shop) {
        if (shop == null || shop.getWorldName() == null) return;
        worldShops.computeIfAbsent(shop.getWorldName(), k -> ConcurrentHashMap.newKeySet())
            .add(shop.getId());
    }

    /**
     * Removes a shop from the world index (called when a shop is deleted).
     */
    public void unregisterShopFromWorld(UUID shopId, String worldName) {
        if (shopId == null || worldName == null) return;
        Set<UUID> shops = worldShops.get(worldName);
        if (shops != null) {
            shops.remove(shopId);
        }
    }

    // ==================== EVENT HANDLERS ====================

    /**
     * Called when a player is added to a world.
     * Lazily spawns shop NPCs for that world on first player entry.
     *
     * Uses the same pattern as Core CitizenService: AddPlayerToWorldEvent
     * triggers per-world initialization on first player join.
     */
    public void onPlayerAddedToWorld(AddPlayerToWorldEvent event) {
        World world = event.getWorld();
        if (world == null) return;

        String worldName = world.getName();
        if (worldName == null) return;

        // Only initialize once per world
        if (!spawnedWorlds.add(worldName)) return;

        Set<UUID> shopsInWorld = worldShops.get(worldName);
        if (shopsInWorld == null || shopsInWorld.isEmpty()) {
            LOGGER.fine("No shops to spawn in world: " + worldName);
            return;
        }

        LOGGER.info("First player in world '" + worldName + "' — spawning "
            + shopsInWorld.size() + " shop NPC(s)");

        // PITFALL: Entity operations MUST run inside world.execute()
        world.execute(() -> {
            for (UUID shopId : shopsInWorld) {
                ShopData shop = shopManager.getShop(shopId);
                if (shop == null) continue;
                if (!shop.isOpen()) continue; // Don't spawn NPCs for closed shops

                try {
                    spawnNpcInternal(shop, world);
                } catch (Exception e) {
                    LOGGER.warning("Failed to spawn NPC for shop " + shopId + ": " + e.getMessage());
                }
            }
        });
    }

    // ==================== NPC LIFECYCLE ====================

    /**
     * Spawns the NPC for a given shop.
     * Calculates NPC position behind the shop block based on npcRotY,
     * spawns an interactable NPC entity, and tracks it.
     *
     * This method is safe to call from any thread — it dispatches
     * to world.execute() internally.
     */
    public void spawnNpc(ShopData shop) {
        if (shop == null) return;

        String worldName = shop.getWorldName();
        if (worldName == null || worldName.isEmpty()) {
            LOGGER.warning("Cannot spawn NPC for shop " + shop.getId() + ": no worldName");
            return;
        }

        // Only spawn if the world has been initialized (has players)
        if (!spawnedWorlds.contains(worldName)) {
            LOGGER.fine("Deferring NPC spawn for shop " + shop.getId()
                + ": world '" + worldName + "' not yet initialized");
            registerShopInWorld(shop);
            return;
        }

        // Resolve world and dispatch to world thread
        // TODO: Resolve world reference from Hytale API
        // World world = HytaleServer.getInstance().getWorld(worldName);
        // if (world == null) return;
        // world.execute(() -> spawnNpcInternal(shop, world));

        LOGGER.fine("NPC spawn requested for shop: " + shop.getName() + " (" + shop.getId() + ")");
    }

    /**
     * Internal NPC spawn — MUST be called inside world.execute()!
     *
     * Spawn flow:
     * 1. Calculate position offset behind shop block
     * 2. Resolve NPC role (interactable)
     * 3. Spawn entity via NPCPlugin
     * 4. Set nameplate, make invulnerable
     * 5. Track in maps for interaction lookup
     */
    private void spawnNpcInternal(ShopData shop, World world) {
        UUID shopId = shop.getId();

        // Already spawned? Despawn first.
        if (shopNpcIds.containsKey(shopId)) {
            despawnNpcInternal(shopId, world);
        }

        try {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null) {
                LOGGER.warning("NPCPlugin not available — cannot spawn shop NPC for " + shop.getName());
                return;
            }

            // Calculate NPC position: offset behind the shop block
            // npcRotY is the facing direction of the NPC (radians).
            // The NPC stands behind the block, facing toward the player.
            Vector3d npcPosition = calculateNpcPosition(shop);
            Vector3f npcRotation = new Vector3f(0.0f, shop.getNpcRotY(), 0.0f);

            // Resolve role — prefer interactable role for F-key interaction
            String roleName = NPC_ROLE_INTERACTABLE;
            int roleIndex = npcPlugin.getIndex(roleName);
            if (roleIndex < 0) {
                // Fallback to idle role
                roleName = NPC_ROLE_IDLE;
                roleIndex = npcPlugin.getIndex(roleName);
            }
            if (roleIndex < 0) {
                LOGGER.warning("No suitable NPC role found for shop NPC ("
                    + NPC_ROLE_INTERACTABLE + " / " + NPC_ROLE_IDLE + ")");
                return;
            }

            var store = world.getEntityStore().getStore();
            final int finalRoleIndex = roleIndex;

            // Spawn the NPC entity with Interactable component for F-key
            var result = npcPlugin.spawnEntity(
                store,
                finalRoleIndex,
                npcPosition,
                npcRotation,
                null, // Model: null = default player model
                // Pre-spawn callback: add Interactable component
                (npcEntity, holder, st) -> {
                    holder.addComponent(Interactable.getComponentType(), Interactable.INSTANCE);
                },
                null  // Post-spawn callback
            );

            if (result == null || result.first() == null) {
                LOGGER.warning("NPC spawn returned null for shop " + shop.getName());
                return;
            }

            Ref<EntityStore> entityRef = result.first();
            NPCEntity npcEntity = result.second();

            // Track the entity
            entityRefs.put(shopId, entityRef);

            String npcEntityId;
            UUID npcEntityUuid = null;
            if (npcEntity != null) {
                npcEntityUuid = npcEntity.getUuid();
                npcEntityId = npcEntityUuid.toString();
                entityUuids.put(shopId, npcEntityUuid);

                // Make NPC invulnerable — NPCs should not take damage
                // NPCEntity does not take damage by default with IDLE/Interactable roles
            } else {
                // Fallback: use shopId as entity identifier
                npcEntityId = "shop_npc_" + shopId;
                LOGGER.warning("NPC entity is null after spawn for shop " + shop.getName()
                    + " — using fallback ID");
            }

            // Update tracking maps
            shopNpcIds.put(shopId, npcEntityId);
            npcToShopMap.put(npcEntityId, shopId);

            // Update ShopData with the NPC entity ID
            shop.setNpcEntityId(npcEntityId);

            // Set nameplate text to shop name
            // NOTE: Nameplate is handled by the NPC role system.
            // For custom text, we'd use HologramManager or Nameplate component.
            // TODO: Set nameplate text via Nameplate component or HologramManager
            // HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            //     world.execute(() -> setNpcNameplate(entityRef, shop.getName(), store));
            // }, 50, TimeUnit.MILLISECONDS);

            // Apply owner skin if configured and Core is available
            applySkinIfAvailable(shop, entityRef, world, store);

            LOGGER.info("Shop NPC spawned: '" + shop.getName() + "' at ["
                + String.format("%.1f, %.1f, %.1f", npcPosition.x, npcPosition.y, npcPosition.z)
                + "] in world '" + world.getName() + "'");

        } catch (Exception e) {
            LOGGER.warning("Failed to spawn NPC for shop " + shop.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Calculates the NPC position behind the shop block.
     * The NPC is placed at an offset opposite to its facing direction (npcRotY).
     */
    private Vector3d calculateNpcPosition(ShopData shop) {
        double x = shop.getPosX();
        double y = shop.getPosY();
        double z = shop.getPosZ();
        float rotY = shop.getNpcRotY();

        // Offset behind the block: move in the direction the NPC is facing
        // rotY is in radians — the NPC faces toward the block, so offset is in facing direction
        double offsetX = -Math.sin(rotY) * NPC_OFFSET_DISTANCE;
        double offsetZ = Math.cos(rotY) * NPC_OFFSET_DISTANCE;

        return new Vector3d(x + offsetX, y, z + offsetZ);
    }

    /**
     * Applies the owner's skin to the NPC if Core is available.
     * Falls back to default skin from config otherwise.
     */
    private void applySkinIfAvailable(ShopData shop, Ref<EntityStore> entityRef,
                                       World world, Store<EntityStore> store) {
        String skinUsername = shop.getNpcSkinUsername();
        if (skinUsername == null || skinUsername.isEmpty()) {
            // Use owner name as skin (for player shops)
            if (shop.getOwnerName() != null && !shop.getOwnerName().isEmpty()
                    && config.getData().playerShops.allowNpcCustomization) {
                skinUsername = shop.getOwnerName();
            } else {
                // Use default skin from config
                skinUsername = config.getData().npc.defaultSkinUsername;
            }
        }

        if (skinUsername == null || skinUsername.isEmpty()) return;

        // Skin application requires Core's SkinManager — use CoreBridge
        // In standalone mode, NPCs use the default player model without custom skin
        if (!CoreBridge.isCoreAvailable()) {
            LOGGER.fine("Core not available — using default player model for shop NPC");
            return;
        }

        // TODO: Apply skin via CoreBridge reflection to SkinManager
        // CoreBridge does not currently expose SkinManager — add when needed
        LOGGER.fine("Skin application for shop NPCs requires Core SkinManager integration");
    }

    /**
     * Despawns the NPC for a given shop.
     * Safe to call from any thread — dispatches to world.execute() internally.
     */
    public void despawnNpc(UUID shopId) {
        if (shopId == null) return;

        String npcEntityId = shopNpcIds.get(shopId);
        if (npcEntityId == null) {
            LOGGER.fine("No NPC tracked for shop: " + shopId);
            return;
        }

        // Resolve world from ShopData
        ShopData shop = shopManager.getShop(shopId);
        if (shop == null) {
            // Shop already deleted — clean up tracking only
            cleanupTracking(shopId, npcEntityId);
            return;
        }

        // TODO: Resolve world reference from Hytale API and dispatch to world.execute()
        // World world = HytaleServer.getInstance().getWorld(shop.getWorldName());
        // if (world != null) {
        //     world.execute(() -> despawnNpcInternal(shopId, world));
        // } else {
        //     cleanupTracking(shopId, npcEntityId);
        // }

        // For now, clean up tracking immediately
        cleanupTracking(shopId, npcEntityId);
        LOGGER.fine("NPC despawned for shop: " + shopId);
    }

    /**
     * Internal despawn — MUST be called inside world.execute()!
     */
    private void despawnNpcInternal(UUID shopId, World world) {
        Ref<EntityStore> entityRef = entityRefs.get(shopId);
        String npcEntityId = shopNpcIds.get(shopId);

        if (entityRef != null && entityRef.isValid()) {
            try {
                var store = world.getEntityStore().getStore();
                store.removeEntity(entityRef, RemoveReason.REMOVE);
                LOGGER.fine("NPC entity removed for shop: " + shopId);
            } catch (Exception e) {
                LOGGER.warning("Failed to remove NPC entity for shop " + shopId + ": " + e.getMessage());
            }
        }

        cleanupTracking(shopId, npcEntityId);
    }

    /**
     * Cleans up all tracking maps for a shop's NPC.
     */
    private void cleanupTracking(UUID shopId, String npcEntityId) {
        shopNpcIds.remove(shopId);
        entityRefs.remove(shopId);
        entityUuids.remove(shopId);
        if (npcEntityId != null) {
            npcToShopMap.remove(npcEntityId);
        }
    }

    /**
     * Despawns all active shop NPCs. Called on plugin shutdown.
     * Iterates all tracked NPCs and removes them from the world.
     */
    public void despawnAll() {
        int count = shopNpcIds.size();
        if (count == 0) return;

        // Copy keys to avoid ConcurrentModificationException
        Set<UUID> shopIds = new HashSet<>(shopNpcIds.keySet());
        for (UUID shopId : shopIds) {
            try {
                String npcEntityId = shopNpcIds.get(shopId);
                cleanupTracking(shopId, npcEntityId);
            } catch (Exception e) {
                LOGGER.warning("Error despawning NPC for shop " + shopId + ": " + e.getMessage());
            }
        }

        // Note: On shutdown, world.execute() may no longer be available.
        // Entity cleanup is handled by the server on world unload.
        shopNpcIds.clear();
        npcToShopMap.clear();
        entityRefs.clear();
        entityUuids.clear();
        spawnedWorlds.clear();

        if (count > 0) {
            LOGGER.info("Despawned " + count + " shop NPC(s)");
        }
    }

    /**
     * Respawns the NPC for a shop (despawn + spawn).
     * Used when a shop is renamed, moved, or its NPC skin changes.
     */
    public void respawnNpc(ShopData shop) {
        if (shop == null) return;
        despawnNpc(shop.getId());
        spawnNpc(shop);
    }

    // ==================== LOOKUPS ====================

    /**
     * Reverse lookup: returns the shopId for a given NPC entity ID.
     * Used by ShopBlockInteraction to determine which shop an NPC belongs to.
     *
     * @return shopId or null if the entity is not a shop NPC
     */
    public UUID getShopIdForNpc(String npcEntityId) {
        if (npcEntityId == null) return null;
        return npcToShopMap.get(npcEntityId);
    }

    /**
     * Reverse lookup by NPC entity UUID.
     */
    public UUID getShopIdForNpcUuid(UUID npcEntityUuid) {
        if (npcEntityUuid == null) return null;
        for (Map.Entry<UUID, UUID> entry : entityUuids.entrySet()) {
            if (npcEntityUuid.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Finds the nearest shop to a given position in a world.
     * Used for proximity-based interaction (block interaction fallback).
     *
     * @param worldName  the world to search in
     * @param x          player X position
     * @param y          player Y position
     * @param z          player Z position
     * @param maxRange   maximum search range
     * @return nearest ShopData or null if none in range
     */
    public ShopData findNearestShop(String worldName, double x, double y, double z, double maxRange) {
        if (worldName == null) return null;

        double maxRangeSq = maxRange * maxRange;
        ShopData nearest = null;
        double nearestDistSq = maxRangeSq;

        Set<UUID> shopsInWorld = worldShops.get(worldName);
        if (shopsInWorld == null) return null;

        for (UUID shopId : shopsInWorld) {
            ShopData shop = shopManager.getShop(shopId);
            if (shop == null || !shop.isOpen()) continue;

            double dx = shop.getPosX() - x;
            double dy = shop.getPosY() - y;
            double dz = shop.getPosZ() - z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = shop;
            }
        }

        return nearest;
    }

    // ==================== GETTERS ====================

    public boolean isNpcSpawned(UUID shopId) {
        return shopNpcIds.containsKey(shopId);
    }

    public int getActiveNpcCount() {
        return shopNpcIds.size();
    }

    public String getNpcEntityId(UUID shopId) {
        return shopNpcIds.get(shopId);
    }
}
