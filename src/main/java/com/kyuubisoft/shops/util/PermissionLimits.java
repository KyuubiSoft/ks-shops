package com.kyuubisoft.shops.util;

import com.hypixel.hytale.server.core.entity.entities.Player;

/**
 * Resolves per-player shop / item count limits from permission nodes.
 *
 * <p>Pattern: {@code ks.shop.limit.shops.N} grants the player a maximum
 * of {@code N} player shops. Multiple nodes may be granted (typically via
 * rank inheritance) and the highest N wins. The global default from
 * {@code config.playerShops.maxShopsPerPlayer} is always the floor:
 * a player with no permission node still gets the global default.
 *
 * <p>Same convention for {@code ks.shop.limit.items.N} - the maximum
 * number of distinct item listings inside a single shop.
 *
 * <p>Permission scan caps at {@link #MAX_SCAN} to avoid checking arbitrary
 * large nodes; admins who need a higher ceiling can extend this constant.
 */
public final class PermissionLimits {

    /** Upper bound on the {@code N} we scan. Anything above is ignored. */
    private static final int MAX_SCAN = 200;

    private static final String SHOPS_PREFIX = "ks.shop.limit.shops.";
    private static final String ITEMS_PREFIX = "ks.shop.limit.items.";
    private static final String RENTALS_PREFIX = "ks.rental.limit.";

    /**
     * Sentinel node a real permission system would never grant. If this
     * comes back true it means the player has a wildcard like {@code *}
     * or {@code ks.*} (LuckPerms OP, etc) - which makes every numbered
     * scan / boolean check below return true and produce nonsensically
     * generous results. Treat such a player as "no specific grant" and
     * fall back to the global default.
     */
    private static final String WILDCARD_SENTINEL =
        "ks.shop.__wildcard_probe.sentinel_check_zzz";

    private PermissionLimits() {}

    /**
     * Returns true when the player's permission backend grants a
     * never-defined sentinel node, indicating a wildcard-style match
     * (e.g. LuckPerms {@code *} or OP). Use to gate explicit-grant
     * checks like {@code ks.shop.list.permanent} so an OP account does
     * not silently inherit privileged behaviour.
     */
    public static boolean hasWildcard(Player player) {
        if (player == null) return false;
        try {
            return player.hasPermission(WILDCARD_SENTINEL, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true only when the player has the given permission node
     * granted **explicitly** (not via a wildcard). Wraps
     * {@link Player#hasPermission(String, boolean)} with a wildcard
     * sentinel probe so OP / {@code *}-style grants do not unlock
     * permission-gated features the way an intentional grant would.
     */
    public static boolean hasExplicit(Player player, String node) {
        if (player == null || node == null) return false;
        if (hasWildcard(player)) return false;
        try {
            return player.hasPermission(node, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves the effective max-shops limit for a player. Returns the
     * global default if {@code player} is null or has no matching nodes.
     */
    public static int resolveMaxShops(Player player, int globalDefault) {
        return resolve(player, SHOPS_PREFIX, globalDefault);
    }

    /**
     * Resolves the effective max-items-per-shop limit for a player. Returns
     * the global default if {@code player} is null or has no matching nodes.
     */
    public static int resolveMaxItems(Player player, int globalDefault) {
        return resolve(player, ITEMS_PREFIX, globalDefault);
    }

    /**
     * Resolves the effective max-concurrent-rentals limit for a player via
     * {@code ks.rental.limit.N} nodes. Returns the global default if the
     * player has no matching nodes. Used by the rental-station flow to cap
     * how many leasable slots a single player can hold at once.
     */
    public static int resolveMaxRentals(Player player, int globalDefault) {
        return resolve(player, RENTALS_PREFIX, globalDefault);
    }

    private static int resolve(Player player, String prefix, int globalDefault) {
        if (player == null) return globalDefault;
        // Wildcard backends (OP / `*` in LuckPerms) would return true for
        // every prefix.N node and we'd report MAX_SCAN as the limit -
        // effectively unlimited. Detect that and fall back to the global
        // default so OP accounts behave like normal players for these
        // numeric limits. Operators who want a specific cap should grant
        // a single explicit prefix.N node on top of the wildcard.
        if (hasWildcard(player)) return globalDefault;
        int highest = globalDefault;
        for (int n = 1; n <= MAX_SCAN; n++) {
            try {
                if (player.hasPermission(prefix + n, false) && n > highest) {
                    highest = n;
                }
            } catch (Exception ignored) {
                // hasPermission can throw when the player has no live
                // session / permission backend - treat as "not granted".
            }
        }
        return highest;
    }
}
