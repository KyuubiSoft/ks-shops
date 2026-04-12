package com.kyuubisoft.shops.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks open UI sessions and editor locks.
 * Ensures only one player can edit a shop at a time, and tracks active buyers.
 */
public class ShopSessionManager {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    /** shopId -> editorPlayerUuid (only one editor per shop at a time) */
    private final Map<UUID, UUID> editorLocks = new ConcurrentHashMap<>();

    /** Players currently browsing/buying in any shop */
    private final Set<UUID> activeBuyers = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to acquire an editor lock for the given shop.
     *
     * @return true if the lock was acquired (or the player already holds it)
     */
    public boolean lockEditor(UUID shopId, UUID playerUuid) {
        UUID existing = editorLocks.putIfAbsent(shopId, playerUuid);
        return existing == null || existing.equals(playerUuid);
    }

    /**
     * Releases an editor lock. Only the holding player can release it.
     */
    public void unlockEditor(UUID shopId, UUID playerUuid) {
        editorLocks.remove(shopId, playerUuid);
    }

    /**
     * Checks if a shop currently has an active editor lock.
     */
    public boolean isEditorLocked(UUID shopId) {
        return editorLocks.containsKey(shopId);
    }

    /**
     * Returns the UUID of the player currently editing the shop, or null.
     */
    public UUID getEditorPlayer(UUID shopId) {
        return editorLocks.get(shopId);
    }

    /**
     * Marks a player as an active buyer.
     */
    public void addActiveBuyer(UUID playerUuid) {
        activeBuyers.add(playerUuid);
    }

    /**
     * Removes a player from the active buyer set.
     */
    public void removeActiveBuyer(UUID playerUuid) {
        activeBuyers.remove(playerUuid);
    }

    /**
     * Closes all sessions for a given player (editor locks + buyer state).
     * Called on disconnect.
     */
    public void closeSession(UUID playerUuid) {
        // Remove any editor locks held by this player
        editorLocks.entrySet().removeIf(entry -> entry.getValue().equals(playerUuid));
        // Remove from active buyers
        activeBuyers.remove(playerUuid);
    }

    /**
     * Closes all sessions (shutdown cleanup).
     */
    public void closeAll() {
        editorLocks.clear();
        activeBuyers.clear();
    }
}
