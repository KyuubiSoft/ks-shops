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

    private PermissionLimits() {}

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

    private static int resolve(Player player, String prefix, int globalDefault) {
        if (player == null) return globalDefault;
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
