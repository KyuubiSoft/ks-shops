package com.kyuubisoft.shops.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
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

    /**
     * NPC role name for shop NPCs. Shipped by shops itself in
     * citizen-roles/Shop_Keeper_Role.json. Uses a non-KS_NPC_ prefix on purpose
     * so Citizens' orphan scan (which matches prefixes KS_NPC_, KS_Path_,
     * Citizen_, Empty_Role) never classifies shop NPCs as citizens in the
     * first place - no protection hack required, fully standalone.
     */
    private static final String NPC_ROLE_INTERACTABLE = "Shop_Keeper_Role";
    /** Legacy fallback if Shop_Keeper_Role failed to load (e.g. old Core-only env) */
    private static final String NPC_ROLE_LEGACY_FALLBACK = "KS_NPC_Interactable_Role";
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
            // First: sweep any persisted shop NPCs left over from a previous
            // server run. Hytale's PersistentModel keeps the NPC entity alive
            // across restarts in the chunk data, but the PlayerSkinComponent is
            // not persisted - so these "naked" duplicates would stack on top
            // of the freshly spawned (skinned) NPCs we create below.
            int removed = sweepStalePersistentNpcs(world);
            if (removed > 0) {
                LOGGER.info("onPlayerAddedToWorld: removed " + removed
                    + " stale persisted shop NPC(s) in " + worldName);
            }

            for (UUID shopId : shopsInWorld) {
                ShopData shop = shopManager.getShop(shopId);
                if (shop == null) continue;
                if (!shop.isOpen()) continue; // Don't spawn NPCs for closed shops
                if (shop.isPacked()) continue; // Packed shops stay despawned

                try {
                    spawnNpcInternal(shop, world);
                } catch (Exception e) {
                    LOGGER.warning("Failed to spawn NPC for shop " + shopId + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * Scans the world's ECS for any NPC entity running the {@code Shop_Keeper_Role}
     * and removes it. Used on first-player-in-world to clean out NPCs that Hytale
     * persisted across a server restart - these entities come back without their
     * PlayerSkinComponent and would stack on top of the fresh skinned NPCs we
     * spawn in {@link #onPlayerAddedToWorld}.
     *
     * Must run inside {@code world.execute()}.
     */
    private int sweepStalePersistentNpcs(World world) {
        try {
            var store = world.getEntityStore().getStore();
            var query = Archetype.of(NPCEntity.getComponentType());
            List<Ref<EntityStore>> toRemove = new ArrayList<>();

            // Only match OUR shop role. The legacy KS_NPC_Interactable_Role is
            // also used by Core Citizens, quest NPCs, pet NPCs, etc. - matching
            // on it would delete other mods' NPCs. Shop_Keeper_Role is a
            // shops-only identifier.
            Set<UUID> trackedUuids = new HashSet<>(entityUuids.values());

            store.forEachChunk(query, (chunk, commandBuffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    try {
                        NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                        if (npc == null) continue;
                        String roleName = npc.getRoleName();
                        if (!NPC_ROLE_INTERACTABLE.equals(roleName)) continue;
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        if (ref == null || !ref.isValid()) continue;
                        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
                        if (uuidComp == null || uuidComp.getUuid() == null) continue;
                        // Keep tracked NPCs - we own those.
                        if (trackedUuids.contains(uuidComp.getUuid())) continue;
                        toRemove.add(ref);
                    } catch (Exception ignored) {
                        // Skip broken entities
                    }
                }
            });

            int removed = 0;
            for (Ref<EntityStore> ref : toRemove) {
                try {
                    if (ref.isValid()) {
                        store.removeEntity(ref, RemoveReason.REMOVE);
                        removed++;
                    }
                } catch (Exception e) {
                    LOGGER.fine("sweepStalePersistentNpcs: remove failed: " + e.getMessage());
                }
            }
            return removed;
        } catch (Exception e) {
            LOGGER.warning("sweepStalePersistentNpcs: scan failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Public scheduler entry: runs the stale-NPC sweep periodically against the
     * default world. Called every 30 seconds from ShopPlugin so chunk-restored
     * duplicates that appear after the player walks into a new area also get
     * cleaned up (not just the first-player-join path).
     */
    public int runPeriodicOrphanSweep() {
        try {
            World world = resolveWorld(null);
            if (world == null) return 0;
            final int[] removed = { 0 };
            // Dispatch to world thread for entity ops
            world.execute(() -> {
                int r = sweepStalePersistentNpcs(world);
                removed[0] = r;
                if (r > 0) {
                    LOGGER.info("Periodic orphan sweep: removed " + r + " chunk-restored shop NPC(s)");
                }
            });
            return removed[0];
        } catch (Exception e) {
            LOGGER.fine("runPeriodicOrphanSweep failed: " + e.getMessage());
            return 0;
        }
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
     * Spawns a shop NPC at an explicit world position, bypassing the block-offset math.
     *
     * Used by the Shop_NPC_Token interaction and by the startup migration that
     * converts pre-NPC-only shops (which used to be anchored on Shop_Block
     * positions) into standalone NPC shops.
     *
     * MUST be called inside {@code world.execute()} — callers are responsible
     * for dispatching to the world thread.
     *
     * @param shop     the shop whose NPC is being spawned
     * @param world    the target world (already resolved)
     * @param position the exact spawn coordinates
     * @param rotY     yaw (radians) — typically the owner's facing direction
     */
    public void spawnNpcAtPosition(ShopData shop, World world, Vector3d position, float rotY) {
        if (shop == null || world == null || position == null) return;

        // Persist the new coords so future lookups/despawns match what we spawned.
        shop.setWorldName(world.getName());
        shop.setPosX(position.x);
        shop.setPosY(position.y);
        shop.setPosZ(position.z);
        shop.setNpcRotY(rotY);

        // Mark the world as initialized so subsequent spawnNpc() calls know it's live.
        spawnedWorlds.add(world.getName());
        registerShopInWorld(shop);

        try {
            spawnNpcInternalAt(shop, world, position, rotY);
        } catch (Exception e) {
            LOGGER.warning("spawnNpcAtPosition failed for shop " + shop.getId()
                + ": " + e.getMessage());
        }
    }

    /**
     * Direct-position spawn helper used by {@link #spawnNpcAtPosition}.
     * Mirrors {@link #spawnNpcInternal} but skips the block-offset math.
     * MUST be called from the world thread.
     */
    private void spawnNpcInternalAt(ShopData shop, World world, Vector3d position, float rotY) {
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

            Vector3f npcRotation = new Vector3f(0.0f, rotY, 0.0f);

            // Resolve role: prefer our standalone Shop_Keeper_Role, fall back to
            // Core's KS_NPC_* roles if the shipped JSON somehow failed to load.
            String roleName = NPC_ROLE_INTERACTABLE;
            int roleIndex = npcPlugin.getIndex(roleName);
            if (roleIndex < 0) {
                roleName = NPC_ROLE_LEGACY_FALLBACK;
                roleIndex = npcPlugin.getIndex(roleName);
            }
            if (roleIndex < 0) {
                roleName = NPC_ROLE_IDLE;
                roleIndex = npcPlugin.getIndex(roleName);
            }
            if (roleIndex < 0) {
                LOGGER.warning("No suitable NPC role found for shop NPC ("
                    + NPC_ROLE_INTERACTABLE + " / " + NPC_ROLE_LEGACY_FALLBACK + " / " + NPC_ROLE_IDLE + ")");
                return;
            }

            var store = world.getEntityStore().getStore();

            var result = npcPlugin.spawnEntity(
                store,
                roleIndex,
                position,
                npcRotation,
                null,
                (npcEntity, holder, st) -> {
                    holder.addComponent(Interactable.getComponentType(), Interactable.INSTANCE);
                    holder.addComponent(
                        com.hypixel.hytale.server.core.modules.entity.component.Invulnerable.getComponentType(),
                        com.hypixel.hytale.server.core.modules.entity.component.Invulnerable.INSTANCE);
                },
                null
            );

            if (result == null || result.first() == null) {
                LOGGER.warning("NPC spawn returned null for shop " + shop.getName());
                return;
            }

            Ref<EntityStore> entityRef = result.first();
            NPCEntity npcEntity = result.second();

            entityRefs.put(shopId, entityRef);

            String npcEntityId;
            UUID npcEntityUuid = null;
            if (npcEntity != null) {
                npcEntityUuid = npcEntity.getUuid();
                npcEntityId = npcEntityUuid.toString();
                entityUuids.put(shopId, npcEntityUuid);
            } else {
                npcEntityId = "shop_npc_" + shopId;
                LOGGER.warning("NPC entity is null after spawn for shop " + shop.getName()
                    + " — using fallback ID");
            }

            shopNpcIds.put(shopId, npcEntityId);
            npcToShopMap.put(npcEntityId, shopId);
            shop.setNpcEntityId(npcEntityId);

            // Protect the NPC from Core's Citizens orphan scan (which would otherwise
            // delete any NPC using KS_NPC_* roles that isn't in its own registry).
            if (npcEntityUuid != null) {
                CoreBridge.protectNpcFromCitizens(npcEntityUuid);
            }

            applySkinIfAvailable(shop, entityRef, world, store);
            applyNameTag(shop, entityRef, store);

            LOGGER.info("Standalone shop NPC spawned: '" + shop.getName() + "' at ["
                + String.format("%.1f, %.1f, %.1f", position.x, position.y, position.z)
                + "] in world '" + world.getName() + "'");
        } catch (Exception e) {
            LOGGER.warning("spawnNpcInternalAt failed for shop " + shop.getName()
                + ": " + e.getMessage());
            e.printStackTrace();
        }
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
                    holder.addComponent(
                        com.hypixel.hytale.server.core.modules.entity.component.Invulnerable.getComponentType(),
                        com.hypixel.hytale.server.core.modules.entity.component.Invulnerable.INSTANCE);
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

            // Protect the NPC from Core's Citizens orphan scan (same role prefix,
            // different ownership). Safe no-op if Core is not loaded.
            if (npcEntityUuid != null) {
                CoreBridge.protectNpcFromCitizens(npcEntityUuid);
            }

            // Apply owner skin if configured
            applySkinIfAvailable(shop, entityRef, world, store);

            // Apply nameplate text (shop name) if the owner has it enabled
            applyNameTag(shop, entityRef, store);

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

        // Standalone: use our own ShopSkinManager (PlayerDB + CosmeticsModule).
        // No Core dependency needed.
        com.kyuubisoft.shops.skin.ShopSkinManager skinManager = plugin.getSkinManager();
        if (skinManager == null) {
            LOGGER.fine("ShopSkinManager not initialized - skipping skin apply for " + shop.getName());
            return;
        }

        final String resolvedUsername = skinUsername;
        skinManager.fetchAndApplySkin(resolvedUsername, 1.0f, entityRef, world::execute)
            .thenAccept(skin -> {
                if (skin != null) {
                    LOGGER.fine("Applied skin '" + resolvedUsername + "' to shop NPC: " + shop.getName());
                }
            })
            .exceptionally(ex -> {
                LOGGER.warning("Skin fetch/apply failed for shop '" + shop.getName()
                    + "' (username=" + resolvedUsername + "): " + ex.getMessage());
                return null;
            });
    }

    /**
     * Applies the shop name as a Nameplate component on the NPC entity so
     * players can read the shop name above the NPC's head. Controlled by
     * {@link ShopData#isShowNameTag()} — when disabled the nameplate is
     * cleared (empty string) so a previously-visible tag disappears on
     * respawn. MUST be called inside world.execute().
     */
    private void applyNameTag(ShopData shop, Ref<EntityStore> entityRef, Store<EntityStore> store) {
        if (entityRef == null || !entityRef.isValid()) return;
        try {
            Nameplate nameplate = (Nameplate) store.ensureAndGetComponent(
                entityRef, Nameplate.getComponentType());
            if (shop.isShowNameTag() && shop.getName() != null && !shop.getName().isEmpty()) {
                nameplate.setText(shop.getName());
            } else {
                nameplate.setText("");
            }
        } catch (Exception e) {
            LOGGER.fine("Failed to set nameplate for shop " + shop.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Public entry point for runtime nameplate updates (e.g. when the owner
     * toggles the show-name-tag option in the editor or renames the shop).
     * Resolves the tracked entity ref + dispatches to the world thread.
     */
    public void refreshNameTag(ShopData shop, World world) {
        if (shop == null || world == null) return;
        Ref<EntityStore> ref = entityRefs.get(shop.getId());
        if (ref == null || !ref.isValid()) return;
        world.execute(() -> {
            var store = world.getEntityStore().getStore();
            applyNameTag(shop, ref, store);
        });
    }

    /**
     * Despawns the NPC for a given shop. Removes the live entity from the
     * world (not just the tracking maps) so the pickup flow and admin delete
     * actions actually make the NPC disappear. Safe to call from any thread
     * - dispatches the ECS removal via world.execute().
     */
    public void despawnNpc(UUID shopId) {
        if (shopId == null) return;

        String npcEntityId = shopNpcIds.get(shopId);
        ShopData shop = shopManager.getShop(shopId);

        // No shop row left (already deleted) - just clean the tracking map.
        if (shop == null) {
            if (npcEntityId != null) cleanupTracking(shopId, npcEntityId);
            return;
        }

        World world = resolveWorld(shop.getWorldName());
        if (world == null) {
            LOGGER.warning("despawnNpc: could not resolve world '" + shop.getWorldName()
                + "' for shop " + shopId + " - cleaning tracking only");
            cleanupTracking(shopId, npcEntityId);
            return;
        }

        // Dispatch the ECS removal to the world thread. The internal method
        // handles both the entityRefs map lookup and the cleanupTracking call,
        // so the maps stay consistent even if the entity ref is null/invalid.
        final UUID finalShopId = shopId;
        world.execute(() -> despawnNpcInternal(finalShopId, world));
        LOGGER.fine("despawnNpc: dispatched removal for shop " + shopId);
    }

    /**
     * Finds a world by its stored name. Hytale does not expose Universe.getWorld(name)
     * as public API, so we fall back to the default world (same pattern Core's
     * CitizenDamageListener uses).
     */
    private World resolveWorld(String worldName) {
        try {
            Universe universe = Universe.get();
            if (universe == null) return null;
            return universe.getDefaultWorld();
        } catch (Exception e) {
            LOGGER.fine("resolveWorld error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Internal despawn — MUST be called inside world.execute()!
     * Removes the tracked entity, cleans tracking, and then sweeps any
     * chunk-persisted duplicates so pickup doesn't leave a ghost NPC.
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

        // After the tracked entity is gone, sweep any chunk-persisted NPC
        // duplicates for this role. This is what makes pickup visually work:
        // without the sweep the ghost NPC from Hytale's chunk persistence
        // would still be standing there, even after we removed the tracked
        // one.
        try {
            int extra = sweepStalePersistentNpcs(world);
            if (extra > 0) {
                LOGGER.fine("despawnNpcInternal: sweep removed " + extra
                    + " stale NPC(s) for " + shopId);
            }
        } catch (Exception e) {
            LOGGER.fine("despawnNpcInternal sweep failed: " + e.getMessage());
        }
    }

    /**
     * Cleans up all tracking maps for a shop's NPC.
     */
    private void cleanupTracking(UUID shopId, String npcEntityId) {
        // Release Citizens protection for the entity UUID (safe no-op if Core absent).
        UUID entityUuid = entityUuids.get(shopId);
        if (entityUuid != null) {
            CoreBridge.unprotectNpcFromCitizens(entityUuid);
        }
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
     * Bulk despawn + respawn for every player shop in the given world. Returns
     * the number of shops scheduled for respawn. Intended for admin use: lets
     * an operator recover from stuck/missing NPCs without restarting the
     * server. The respawn uses the current ShopData state (position, skin,
     * name tag), so any desync between the DB and the live entity is fixed
     * in one pass.
     */
    public int respawnAll(World world) {
        if (world == null) return 0;
        String worldName = world.getName();
        if (worldName == null) return 0;

        // Collect every shop whose stored world matches, not just the ones
        // currently tracked in shopNpcIds (stale entities after a crash might
        // not be in the tracking map yet).
        List<ShopData> shopsInWorld = new ArrayList<>();
        for (ShopData shop : shopManager.getAllShops()) {
            if (shop.isAdminShop() || shop.getOwnerUuid() == null) continue;
            if (worldName.equals(shop.getWorldName())) {
                shopsInWorld.add(shop);
            }
        }

        if (shopsInWorld.isEmpty()) {
            LOGGER.info("respawnAll: no shops in world " + worldName);
            return 0;
        }

        world.execute(() -> {
            for (ShopData shop : shopsInWorld) {
                try {
                    despawnNpcInternal(shop.getId(), world);
                    Vector3d pos = new Vector3d(shop.getPosX(), shop.getPosY(), shop.getPosZ());
                    spawnNpcInternalAt(shop, world, pos, shop.getNpcRotY());
                } catch (Exception e) {
                    LOGGER.warning("respawnAll: failed for shop '" + shop.getName()
                        + "': " + e.getMessage());
                }
            }
            LOGGER.info("respawnAll: processed " + shopsInWorld.size() + " shops in " + worldName);
        });

        return shopsInWorld.size();
    }

    /**
     * Respawns the NPC for a shop (despawn + spawn).
     * Used when a shop is renamed, moved, or its NPC skin changes.
     *
     * The caller MUST provide the live World reference and dispatch this
     * inside {@code world.execute()} so entity operations run on the world
     * thread. If the world is null, we still issue a despawn (tracking-only
     * cleanup) so the skin-change path at least stops referencing the old NPC.
     */
    public void respawnNpc(ShopData shop, World world) {
        if (shop == null) return;
        UUID shopId = shop.getId();

        if (world != null) {
            world.execute(() -> {
                despawnNpcInternal(shopId, world);
                Vector3d position = new Vector3d(
                    shop.getPosX(), shop.getPosY(), shop.getPosZ());
                spawnNpcInternalAt(shop, world, position, shop.getNpcRotY());
            });
        } else {
            despawnNpc(shopId);
        }
    }

    /**
     * Legacy world-less respawn used when the caller does not have a World
     * reference handy. Cleans up tracking but does not re-spawn the entity —
     * the next player-enters-world event will re-spawn via lazy init.
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
