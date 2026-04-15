package com.kyuubisoft.shops.npc;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.EntityUpdate;
import com.hypixel.hytale.protocol.ModelTransform;
import com.hypixel.hytale.protocol.TransformUpdate;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Rotates shop NPCs so each nearby player sees the NPC facing them.
 *
 * Uses the same pattern as Core's CitizenRotationManager: per-player
 * packet-based rotation (EntityUpdates with ModelTransform). Each player
 * receives their own rotation packet, so multiple players see the NPC
 * looking at THEM individually - no shared world-state.
 *
 * Runs entirely off cached data (shop position + cached NetworkId), so no
 * world.execute() is required. Packets are sent via
 * playerRef.getPacketHandler().write(packet) which is thread-safe.
 */
public final class ShopNpcRotationManager {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private static final double MAX_ROTATION_DISTANCE = 25.0;
    private static final double MAX_ROTATION_DISTANCE_SQ = MAX_ROTATION_DISTANCE * MAX_ROTATION_DISTANCE;
    private static final float YAW_THRESHOLD = 0.015f;
    private static final float PITCH_THRESHOLD = 0.015f;

    private final Map<UUID, TrackedNpc> trackedNpcs = new ConcurrentHashMap<>();

    /** One-shot diagnostics so the user can see the pipeline fire end-to-end. */
    private final AtomicBoolean firstTickLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstInRangeLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstPacketLogged = new AtomicBoolean(false);

    public void trackNpc(UUID shopId, int networkId, Vector3d position) {
        if (shopId == null || networkId <= 0 || position == null) return;
        trackedNpcs.put(shopId, new TrackedNpc(networkId, position.x, position.y, position.z));
    }

    public void untrackNpc(UUID shopId) {
        if (shopId == null) return;
        trackedNpcs.remove(shopId);
    }

    public void clearAll() {
        trackedNpcs.clear();
        firstTickLogged.set(false);
        firstInRangeLogged.set(false);
        firstPacketLogged.set(false);
    }

    public int getTrackedCount() {
        return trackedNpcs.size();
    }

    /**
     * Per-tick rotation pass. Iterates tracked NPCs and sends per-player
     * rotation packets for any player within {@link #MAX_ROTATION_DISTANCE}.
     *
     * Threshold-filtered so we only send when the target direction has
     * moved more than {@link #YAW_THRESHOLD} / {@link #PITCH_THRESHOLD}
     * radians, which keeps the packet volume low at idle.
     */
    public void tick(Collection<? extends Player> onlinePlayers) {
        if (trackedNpcs.isEmpty() || onlinePlayers == null || onlinePlayers.isEmpty()) return;

        if (firstTickLogged.compareAndSet(false, true)) {
            LOGGER.info("[Rotation] first tick: tracked=" + trackedNpcs.size()
                + " onlinePlayers=" + onlinePlayers.size());
        }

        for (Map.Entry<UUID, TrackedNpc> entry : trackedNpcs.entrySet()) {
            TrackedNpc npc = entry.getValue();
            if (npc == null) continue;

            for (Player player : onlinePlayers) {
                try {
                    PlayerRef playerRef = player.getPlayerRef();
                    if (playerRef == null) continue;

                    Vector3d playerPos = new Vector3d(playerRef.getTransform().getPosition());
                    double dx = playerPos.x - npc.x;
                    double dz = playerPos.z - npc.z;
                    double distSq = dx * dx + dz * dz;
                    if (distSq > MAX_ROTATION_DISTANCE_SQ) continue;

                    if (firstInRangeLogged.compareAndSet(false, true)) {
                        LOGGER.info("[Rotation] first in-range check OK: shop="
                            + entry.getKey() + " netId=" + npc.networkId
                            + " player=" + playerRef.getUsername()
                            + " distSq=" + String.format("%.1f", distSq));
                    }

                    float yaw = (float) (Math.atan2(dx, dz) + Math.PI);

                    double dy = playerPos.y - npc.y;
                    double horizontalDistance = Math.sqrt(distSq);
                    float pitch = (float) Math.atan2(dy, horizontalDistance);

                    UUID playerId = playerRef.getUuid();
                    Direction lastDir = npc.lastDirections.get(playerId);
                    if (lastDir != null) {
                        float yawDiff = Math.abs(yaw - lastDir.yaw);
                        float pitchDiff = Math.abs(pitch - lastDir.pitch);
                        if (yawDiff < YAW_THRESHOLD && pitchDiff < PITCH_THRESHOLD) continue;
                    }

                    Direction lookDirection = new Direction(yaw, pitch, 0f);
                    Direction bodyDirection = new Direction(yaw, 0f, 0f);

                    ModelTransform transform = new ModelTransform();
                    transform.lookOrientation = lookDirection;
                    transform.bodyOrientation = bodyDirection;

                    TransformUpdate update = new TransformUpdate(transform);
                    EntityUpdate entityUpdate = new EntityUpdate(
                        npc.networkId, null, new ComponentUpdate[] { update });
                    EntityUpdates packet = new EntityUpdates(
                        null, new EntityUpdate[] { entityUpdate });

                    playerRef.getPacketHandler().write(packet);
                    npc.lastDirections.put(playerId, lookDirection);

                    if (firstPacketLogged.compareAndSet(false, true)) {
                        LOGGER.info("[Rotation] first rotation packet sent: shop="
                            + entry.getKey() + " netId=" + npc.networkId
                            + " player=" + playerRef.getUsername()
                            + " yaw=" + String.format("%.3f", yaw)
                            + " pitch=" + String.format("%.3f", pitch));
                    }
                } catch (Exception e) {
                    LOGGER.fine("[Rotation] tick error for shop "
                        + entry.getKey() + ": " + e.getMessage());
                }
            }
        }
    }

    private static final class TrackedNpc {
        final int networkId;
        final double x;
        final double y;
        final double z;
        final Map<UUID, Direction> lastDirections = new ConcurrentHashMap<>();

        TrackedNpc(int networkId, double x, double y, double z) {
            this.networkId = networkId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
