package com.kyuubisoft.shops.service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Per-shop breakdown of an earnings collection call.
 * The total is the sum of every entry's amount.
 */
public final class CollectResult {

    public static final class ShopEntry {
        public final UUID shopId;
        public final String shopName;
        public final double amount;
        public ShopEntry(UUID shopId, String shopName, double amount) {
            this.shopId = shopId;
            this.shopName = shopName;
            this.amount = amount;
        }
    }

    private final double total;
    private final List<ShopEntry> entries;
    private final boolean economyFailed;

    private CollectResult(double total, List<ShopEntry> entries, boolean economyFailed) {
        this.total = total;
        this.entries = entries;
        this.economyFailed = economyFailed;
    }

    public static CollectResult empty() {
        return new CollectResult(0.0, Collections.emptyList(), false);
    }

    public static CollectResult success(double total, List<ShopEntry> entries) {
        return new CollectResult(total, entries, false);
    }

    public static CollectResult economyFailure() {
        return new CollectResult(0.0, Collections.emptyList(), true);
    }

    public double getTotal() { return total; }
    public List<ShopEntry> getEntries() { return entries; }
    public boolean isEconomyFailure() { return economyFailed; }
    public boolean isEmpty() { return total <= 0.0 && entries.isEmpty() && !economyFailed; }
}
