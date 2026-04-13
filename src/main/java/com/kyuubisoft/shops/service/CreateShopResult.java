package com.kyuubisoft.shops.service;

import com.kyuubisoft.shops.data.ShopData;

/**
 * Result of a {@link ShopService#createPlayerShop} call.
 *
 * <p>Wraps either a successfully created {@link ShopData} or a specific
 * i18n error key identifying why creation failed. Allows callers (commands,
 * UI pages) to surface a targeted error message instead of a generic
 * "failed" string.</p>
 *
 * <p>Exactly one of {@code shop} or {@code errorKey} is non-null:</p>
 * <ul>
 *   <li>{@link #success(ShopData)} — {@code shop} set, {@code errorKey} null</li>
 *   <li>{@link #error(String)}     — {@code shop} null, {@code errorKey} set</li>
 * </ul>
 */
public final class CreateShopResult {

    private final ShopData shop;
    private final String errorKey;

    private CreateShopResult(ShopData shop, String errorKey) {
        this.shop = shop;
        this.errorKey = errorKey;
    }

    /** Creates a success result wrapping the newly created shop. */
    public static CreateShopResult success(ShopData shop) {
        return new CreateShopResult(shop, null);
    }

    /** Creates an error result with the given i18n key. */
    public static CreateShopResult error(String errorKey) {
        return new CreateShopResult(null, errorKey);
    }

    /** @return the created shop, or {@code null} if creation failed */
    public ShopData getShop() {
        return shop;
    }

    /** @return the i18n error key, or {@code null} on success */
    public String getErrorKey() {
        return errorKey;
    }

    /** @return {@code true} if creation succeeded */
    public boolean isSuccess() {
        return shop != null;
    }
}
