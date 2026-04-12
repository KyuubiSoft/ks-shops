package com.kyuubisoft.shops.bridge;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Optional Claims integration for KS-Shops.
 * Checks whether a player owns the claim at a given position before allowing
 * shop placement. If Claims is not loaded, all checks return true (fail-open)
 * so the shop system works standalone.
 */
public class ClaimsBridge {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private static Boolean available;

    // Cached reflection handles
    private static Object claimManagerInstance;
    private static Method getClaimAtMethod;     // ClaimManager.getClaimAt(String world, int x, int z)
    private static Method getOwnerMethod;       // ClaimData.getOwnerUuid() -> String
    private static Method isTrustedMethod;      // ClaimData.isTrusted(String uuid) -> boolean

    // ==================== AVAILABILITY ====================

    /**
     * Returns true if the Claims plugin is loaded.
     * Result is cached after first check.
     */
    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class<?> claimManagerClass = Class.forName("com.hytale.claims.service.ClaimManager");
                Method getInstance = claimManagerClass.getMethod("getInstance");
                claimManagerInstance = getInstance.invoke(null);
                if (claimManagerInstance == null) {
                    available = false;
                    return false;
                }

                getClaimAtMethod = claimManagerClass.getMethod("getClaimAt", String.class, int.class, int.class);

                Class<?> claimDataClass = Class.forName("com.hytale.claims.data.ClaimData");
                getOwnerMethod = claimDataClass.getMethod("getOwnerUuid");
                isTrustedMethod = claimDataClass.getMethod("isTrusted", String.class);

                available = true;
                LOGGER.info("Claims bridge connected");
            } catch (ClassNotFoundException e) {
                available = false;
            } catch (Exception e) {
                LOGGER.warning("Claims bridge init failed: " + e.getMessage());
                available = false;
            }
        }
        return available;
    }

    // ==================== CLAIM CHECKS ====================

    /**
     * Checks if a player owns or is trusted on the claim at the given position.
     * Returns true (allowed) if:
     * - Claims is not available (fail-open for standalone)
     * - No claim exists at that position (unclaimed land)
     * - The player is the claim owner
     * - The player is trusted on the claim
     *
     * @param playerUuid The player's UUID
     * @param worldName  The world name
     * @param x          Block X coordinate
     * @param z          Block Z coordinate
     * @return true if the player is allowed to place a shop here
     */
    public static boolean isPlayerClaim(UUID playerUuid, String worldName, double x, double z) {
        if (!isAvailable()) return true; // fail-open

        try {
            Object claim = getClaimAtMethod.invoke(claimManagerInstance, worldName, (int) x, (int) z);
            if (claim == null) {
                // No claim at this position — unclaimed land, allow
                return true;
            }

            // Check if player is the owner
            String ownerUuid = (String) getOwnerMethod.invoke(claim);
            if (playerUuid.toString().equals(ownerUuid)) {
                return true;
            }

            // Check if player is trusted
            Boolean trusted = (Boolean) isTrustedMethod.invoke(claim, playerUuid.toString());
            return trusted != null && trusted;
        } catch (Exception e) {
            LOGGER.warning("Claims check failed: " + e.getMessage());
            return true; // fail-open on error
        }
    }

    // ==================== RESET ====================

    /**
     * Resets cached state for reload scenarios.
     */
    public static void reset() {
        available = null;
        claimManagerInstance = null;
        getClaimAtMethod = null;
        getOwnerMethod = null;
        isTrustedMethod = null;
    }
}
