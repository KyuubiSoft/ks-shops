package com.kyuubisoft.shops.bridge;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

/**
 * Optional bridge to KyuubiSoft Core.
 * All Core access goes through this class — if Core is not loaded,
 * everything degrades gracefully with sensible defaults.
 */
public class CoreBridge {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private static Boolean coreAvailable;

    // ==================== AVAILABILITY ====================

    /**
     * Returns true if the Core plugin is loaded and CoreAPI is available.
     * Result is cached after first check.
     */
    public static boolean isCoreAvailable() {
        if (coreAvailable == null) {
            try {
                Class<?> clazz = Class.forName("com.kyuubisoft.core.api.CoreAPI");
                Method isAvailable = clazz.getMethod("isAvailable");
                coreAvailable = (Boolean) isAvailable.invoke(null);
            } catch (ClassNotFoundException e) {
                coreAvailable = false;
            } catch (Exception e) {
                LOGGER.warning("CoreAPI check failed: " + e.getMessage());
                coreAvailable = false;
            }
        }
        return coreAvailable;
    }

    // ==================== DEBUG ====================

    /**
     * Returns true if Core's debug/verbose logging is enabled.
     * Falls back to false if Core is not available.
     */
    public static boolean isDebug() {
        if (!isCoreAvailable()) return false;
        try {
            Class<?> clazz = Class.forName("com.kyuubisoft.core.api.CoreAPI");
            Method isDebug = clazz.getMethod("isDebug");
            return (Boolean) isDebug.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== I18N CONTEXT ====================

    /**
     * Runs an action within the I18nContext for the given player.
     * If Core is not available, runs the action directly (no language context).
     */
    public static void runWithI18n(com.hypixel.hytale.server.core.universe.PlayerRef playerRef, Runnable action) {
        if (!isCoreAvailable()) {
            action.run();
            return;
        }
        try {
            Class<?> clazz = Class.forName("com.kyuubisoft.core.i18n.I18nContext");
            Method run = clazz.getMethod("run", com.hypixel.hytale.server.core.universe.PlayerRef.class, Runnable.class);
            run.invoke(null, playerRef, action);
        } catch (Exception e) {
            // Fallback: run without language context
            action.run();
        }
    }

    // ==================== NPC PAGE OPENER ====================

    @FunctionalInterface
    public interface PageOpener {
        void open(com.hypixel.hytale.server.core.entity.entities.Player player,
                  com.hypixel.hytale.server.core.universe.PlayerRef playerRef,
                  com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
                  com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store);
    }

    /**
     * Registers an NPC page opener with CoreAPI via reflection.
     * Wraps our local PageOpener into Core's NpcPageOpener via a dynamic proxy.
     * No-op if Core is not available.
     */
    public static void registerNpcPageOpener(String id, PageOpener opener) {
        if (!isCoreAvailable()) return;
        try {
            Class<?> coreApi = Class.forName("com.kyuubisoft.core.api.CoreAPI");
            Class<?> openerInterface = Class.forName("com.kyuubisoft.core.api.CoreAPI$NpcPageOpener");

            // Create a dynamic proxy that delegates to our PageOpener
            Object proxy = Proxy.newProxyInstance(
                openerInterface.getClassLoader(),
                new Class<?>[]{ openerInterface },
                (p, method, args) -> {
                    if ("open".equals(method.getName()) && args != null && args.length == 4) {
                        opener.open(
                            (com.hypixel.hytale.server.core.entity.entities.Player) args[0],
                            (com.hypixel.hytale.server.core.universe.PlayerRef) args[1],
                            (com.hypixel.hytale.component.Ref) args[2],
                            (com.hypixel.hytale.component.Store) args[3]);
                    }
                    return null;
                }
            );

            Method register = coreApi.getMethod("registerNpcPageOpener", String.class, openerInterface);
            register.invoke(null, id, proxy);
            LOGGER.info("Registered NPC page opener: " + id);
        } catch (Exception e) {
            LOGGER.fine("Failed to register NPC page opener '" + id + "': " + e.getMessage());
        }
    }

    // ==================== SHOWCASE ====================

    /**
     * Returns true if the action should be BLOCKED (showcase mode + player is not real admin).
     * Sends a "blocked" message to the player if blocked.
     */
    public static boolean showcaseWriteGuard(Object player, Object playerRef) {
        if (!isCoreAvailable()) return false;
        try {
            Class<?> coreApi = Class.forName("com.kyuubisoft.core.api.CoreAPI");
            Class<?> playerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.Player");
            Class<?> playerRefClass = Class.forName("com.hypixel.hytale.server.core.universe.PlayerRef");
            Method guard = coreApi.getMethod("showcaseWriteGuard", playerClass, playerRefClass);
            return (Boolean) guard.invoke(null, player, playerRef);
        } catch (Exception e) {
            return false; // fail-open
        }
    }

    // ==================== NPC PROTECTION ====================

    /**
     * Registers an NPC UUID with Core's Citizens service so its orphan scan
     * skips the entity. Shops NPCs share citizen roles (KS_NPC_Interactable_Role)
     * but are owned by this mod; without this call Citizens would consider them
     * orphans and delete them during the next scan.
     *
     * Fails silently if Core is not loaded.
     */
    public static void protectNpcFromCitizens(java.util.UUID npcUuid) {
        if (npcUuid == null) return;
        if (!isCoreAvailable()) return;
        try {
            Class<?> coreApi = Class.forName("com.kyuubisoft.core.api.CoreAPI");
            Method protect = coreApi.getMethod("protectNpcUuid", java.util.UUID.class);
            protect.invoke(null, npcUuid);
        } catch (NoSuchMethodException e) {
            LOGGER.fine("Core's CoreAPI does not expose protectNpcUuid yet - shop NPC "
                + npcUuid + " may be removed by orphan scan.");
        } catch (Exception e) {
            LOGGER.warning("Failed to protect shop NPC " + npcUuid + " from Citizens: " + e.getMessage());
        }
    }

    /**
     * Removes the NPC UUID from Core's Citizens protected-set. Call when the
     * shop NPC is permanently despawned (e.g. shop deleted).
     */
    public static void unprotectNpcFromCitizens(java.util.UUID npcUuid) {
        if (npcUuid == null) return;
        if (!isCoreAvailable()) return;
        try {
            Class<?> coreApi = Class.forName("com.kyuubisoft.core.api.CoreAPI");
            Method unprotect = coreApi.getMethod("unprotectNpcUuid", java.util.UUID.class);
            unprotect.invoke(null, npcUuid);
        } catch (NoSuchMethodException e) {
            // Silently tolerated - older Core without the API
        } catch (Exception e) {
            LOGGER.fine("Failed to unprotect shop NPC " + npcUuid + " from Citizens: " + e.getMessage());
        }
    }

    // ==================== RESET ====================

    /**
     * Resets the cached availability check (e.g. for reload scenarios).
     */
    public static void reset() {
        coreAvailable = null;
    }
}
