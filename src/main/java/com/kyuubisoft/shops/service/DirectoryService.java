package com.kyuubisoft.shops.service;

import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopItem;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Search and browse service for the shop directory.
 * Maintains a reverse index (itemId -> shopIds) for fast item-based lookups.
 */
public class DirectoryService {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    /** Minimum number of ratings a shop must have to appear in top-rated lists. */
    private static final int MIN_RATINGS_THRESHOLD = 1;

    private final ShopManager shopManager;

    /** Reverse index: itemId -> set of shopIds that stock this item */
    private final Map<String, Set<UUID>> itemIndex = new ConcurrentHashMap<>();

    public DirectoryService(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    /**
     * Rebuilds the entire item index from all loaded shops.
     * Should be called once after initial shop load and after bulk changes.
     */
    public void rebuildIndex() {
        LOGGER.info("Rebuilding shop directory index...");
        itemIndex.clear();

        for (ShopData shop : shopManager.getAllShops()) {
            UUID shopId = shop.getId();
            List<ShopItem> items = shop.getItems();
            if (items == null) continue;

            for (ShopItem item : items) {
                if (item.getItemId() == null) continue;
                itemIndex.computeIfAbsent(item.getItemId(), k -> ConcurrentHashMap.newKeySet())
                    .add(shopId);
            }
        }

        int totalEntries = itemIndex.values().stream().mapToInt(Set::size).sum();
        LOGGER.info("Directory index rebuilt: " + itemIndex.size() + " unique items across "
            + totalEntries + " shop entries");
    }

    /**
     * Updates the index when a shop's stock changes.
     *
     * @param shopId the shop that changed
     * @param itemId the item that was added or removed
     * @param added  true if item was added, false if removed
     */
    public void onStockChanged(UUID shopId, String itemId, boolean added) {
        if (shopId == null || itemId == null) return;

        if (added) {
            itemIndex.computeIfAbsent(itemId, k -> ConcurrentHashMap.newKeySet())
                .add(shopId);
        } else {
            // Check if the shop still has this item before removing from index
            ShopData shop = shopManager.getShop(shopId);
            boolean stillHasItem = false;
            if (shop != null && shop.getItems() != null) {
                for (ShopItem item : shop.getItems()) {
                    if (itemId.equals(item.getItemId())) {
                        stillHasItem = true;
                        break;
                    }
                }
            }

            if (!stillHasItem) {
                Set<UUID> shopIds = itemIndex.get(itemId);
                if (shopIds != null) {
                    shopIds.remove(shopId);
                    if (shopIds.isEmpty()) {
                        itemIndex.remove(itemId);
                    }
                }
            }
        }
    }

    /**
     * Searches shops by query text, category filter, sort order, with pagination.
     *
     * @param query    text to match against shop name/owner/item IDs (nullable)
     * @param category category filter (nullable for all)
     * @param sortBy   sort field: "name", "rating", "newest", "most_sales", "price_low", "price_high" (nullable for default)
     * @param page     zero-based page index
     * @param pageSize results per page
     * @return matching shops for the requested page
     */
    public List<ShopData> searchShops(String query, String category, String sortBy, int page, int pageSize) {
        String queryLower = (query != null && !query.isBlank()) ? query.trim().toLowerCase() : null;

        // Collect and filter
        List<ShopData> filtered = new ArrayList<>();
        for (ShopData shop : shopManager.getAllShops()) {
            // Only open shops unless we have no filter that would show closed
            if (!shop.isOpen()) continue;
            // Player shops must have an active directory listing; admin
            // shops are always listed (they're admin-controlled, not
            // commercial). See ShopData.isListedInDirectory.
            if (shop.isPlayerShop() && !shop.isListedInDirectory()) continue;

            // Category filter
            if (category != null && !category.isEmpty()) {
                if (shop.getCategory() == null || !shop.getCategory().equalsIgnoreCase(category)) {
                    continue;
                }
            }

            // Query filter: match against name, owner name, or item IDs
            if (queryLower != null) {
                boolean matches = false;

                // Match shop name
                if (shop.getName() != null && shop.getName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }

                // Match owner name
                if (!matches && shop.getOwnerName() != null
                    && shop.getOwnerName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }

                // Match item IDs
                if (!matches && shop.getItems() != null) {
                    for (ShopItem item : shop.getItems()) {
                        if (item.getItemId() != null
                            && item.getItemId().toLowerCase().contains(queryLower)) {
                            matches = true;
                            break;
                        }
                    }
                }

                if (!matches) continue;
            }

            filtered.add(shop);
        }

        // Sort
        String sort = (sortBy != null && !sortBy.isEmpty()) ? sortBy : "rating";
        Comparator<ShopData> comparator = switch (sort) {
            case "name" -> Comparator.comparing(
                s -> s.getName() != null ? s.getName().toLowerCase() : "",
                String::compareTo);
            case "newest" -> Comparator.comparingLong(ShopData::getCreatedAt).reversed();
            case "most_sales" -> Comparator.comparingDouble(ShopData::getTotalRevenue).reversed();
            case "price_low" -> Comparator.comparingInt(DirectoryService::getMinBuyPrice);
            case "price_high" -> Comparator.comparingInt(DirectoryService::getMinBuyPrice).reversed();
            default -> Comparator.comparingDouble(ShopData::getAverageRating).reversed();
        };
        filtered.sort(comparator);

        // Paginate
        if (pageSize <= 0) pageSize = 8;
        int startIndex = page * pageSize;
        if (startIndex >= filtered.size()) {
            return Collections.emptyList();
        }
        int endIndex = Math.min(startIndex + pageSize, filtered.size());
        return new ArrayList<>(filtered.subList(startIndex, endIndex));
    }

    /**
     * Returns the total number of shops matching the given filters (for pagination info).
     */
    public int countShops(String query, String category, String typeFilter) {
        String queryLower = (query != null && !query.isBlank()) ? query.trim().toLowerCase() : null;

        int count = 0;
        for (ShopData shop : shopManager.getAllShops()) {
            if (!shop.isOpen()) continue;
            // Player shops must have an active directory listing; admin
            // shops are always listed (they're admin-controlled, not
            // commercial). See ShopData.isListedInDirectory.
            if (shop.isPlayerShop() && !shop.isListedInDirectory()) continue;

            // Type filter
            if ("admin".equals(typeFilter) && !shop.isAdminShop()) continue;
            if ("player".equals(typeFilter) && !shop.isPlayerShop()) continue;
            if ("featured".equals(typeFilter) && !(shop.isFeatured() && shop.getFeaturedUntil() > System.currentTimeMillis())) continue;

            // Category filter
            if (category != null && !category.isEmpty()) {
                if (shop.getCategory() == null || !shop.getCategory().equalsIgnoreCase(category)) {
                    continue;
                }
            }

            // Query filter
            if (queryLower != null) {
                boolean matches = false;
                if (shop.getName() != null && shop.getName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }
                if (!matches && shop.getOwnerName() != null
                    && shop.getOwnerName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }
                if (!matches && shop.getItems() != null) {
                    for (ShopItem item : shop.getItems()) {
                        if (item.getItemId() != null
                            && item.getItemId().toLowerCase().contains(queryLower)) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (!matches) continue;
            }

            count++;
        }
        return count;
    }

    /**
     * Searches shops with an additional type filter (all/admin/player/featured).
     */
    public List<ShopData> searchShopsFiltered(String query, String category, String typeFilter,
                                              String sortBy, int ratingFilter,
                                              int page, int pageSize) {
        String queryLower = (query != null && !query.isBlank()) ? query.trim().toLowerCase() : null;
        long now = System.currentTimeMillis();

        // Collect and filter
        List<ShopData> filtered = new ArrayList<>();
        for (ShopData shop : shopManager.getAllShops()) {
            if (!shop.isOpen()) continue;
            // Player shops must have an active directory listing; admin
            // shops are always listed (they're admin-controlled, not
            // commercial). See ShopData.isListedInDirectory.
            if (shop.isPlayerShop() && !shop.isListedInDirectory()) continue;

            // Type filter
            if ("admin".equals(typeFilter) && !shop.isAdminShop()) continue;
            if ("player".equals(typeFilter) && !shop.isPlayerShop()) continue;
            if ("featured".equals(typeFilter) && !(shop.isFeatured() && shop.getFeaturedUntil() > now)) continue;

            // Category filter
            if (category != null && !category.isEmpty()) {
                if (shop.getCategory() == null || !shop.getCategory().equalsIgnoreCase(category)) {
                    continue;
                }
            }

            // Rating filter (minimum stars)
            if (ratingFilter > 0 && shop.getAverageRating() < ratingFilter) {
                continue;
            }

            // Query filter
            if (queryLower != null) {
                boolean matches = false;
                if (shop.getName() != null && shop.getName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }
                if (!matches && shop.getOwnerName() != null
                    && shop.getOwnerName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }
                if (!matches && shop.getItems() != null) {
                    for (ShopItem item : shop.getItems()) {
                        if (item.getItemId() != null
                            && item.getItemId().toLowerCase().contains(queryLower)) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (!matches) continue;
            }

            filtered.add(shop);
        }

        // Sort
        String sort = (sortBy != null && !sortBy.isEmpty()) ? sortBy : "rating";
        Comparator<ShopData> comparator = switch (sort) {
            case "name" -> Comparator.comparing(
                s -> s.getName() != null ? s.getName().toLowerCase() : "",
                String::compareTo);
            case "newest" -> Comparator.comparingLong(ShopData::getCreatedAt).reversed();
            case "most_sales" -> Comparator.comparingDouble(ShopData::getTotalRevenue).reversed();
            case "price_low" -> Comparator.comparingInt(DirectoryService::getMinBuyPrice);
            case "price_high" -> Comparator.comparingInt(DirectoryService::getMinBuyPrice).reversed();
            default -> Comparator.comparingDouble(ShopData::getAverageRating).reversed();
        };
        filtered.sort(comparator);

        // Paginate
        if (pageSize <= 0) pageSize = 8;
        int startIndex = page * pageSize;
        if (startIndex >= filtered.size()) {
            return Collections.emptyList();
        }
        int endIndex = Math.min(startIndex + pageSize, filtered.size());
        return new ArrayList<>(filtered.subList(startIndex, endIndex));
    }

    /**
     * Returns the total count for filtered search (for pagination).
     */
    public int countShopsFiltered(String query, String category, String typeFilter,
                                  int ratingFilter) {
        String queryLower = (query != null && !query.isBlank()) ? query.trim().toLowerCase() : null;
        long now = System.currentTimeMillis();

        int count = 0;
        for (ShopData shop : shopManager.getAllShops()) {
            if (!shop.isOpen()) continue;
            // Player shops must have an active directory listing; admin
            // shops are always listed (they're admin-controlled, not
            // commercial). See ShopData.isListedInDirectory.
            if (shop.isPlayerShop() && !shop.isListedInDirectory()) continue;
            if ("admin".equals(typeFilter) && !shop.isAdminShop()) continue;
            if ("player".equals(typeFilter) && !shop.isPlayerShop()) continue;
            if ("featured".equals(typeFilter) && !(shop.isFeatured() && shop.getFeaturedUntil() > now)) continue;

            if (category != null && !category.isEmpty()) {
                if (shop.getCategory() == null || !shop.getCategory().equalsIgnoreCase(category)) {
                    continue;
                }
            }

            if (ratingFilter > 0 && shop.getAverageRating() < ratingFilter) {
                continue;
            }

            if (queryLower != null) {
                boolean matches = false;
                if (shop.getName() != null && shop.getName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }
                if (!matches && shop.getOwnerName() != null
                    && shop.getOwnerName().toLowerCase().contains(queryLower)) {
                    matches = true;
                }
                if (!matches && shop.getItems() != null) {
                    for (ShopItem item : shop.getItems()) {
                        if (item.getItemId() != null
                            && item.getItemId().toLowerCase().contains(queryLower)) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (!matches) continue;
            }

            count++;
        }
        return count;
    }

    /**
     * Finds all shops that stock a specific item.
     *
     * @param itemId the item identifier to search for
     * @return open shops containing the item
     */
    public List<ShopData> searchByItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) return Collections.emptyList();

        Set<UUID> shopIds = itemIndex.get(itemId);
        if (shopIds == null || shopIds.isEmpty()) return Collections.emptyList();

        List<ShopData> result = new ArrayList<>();
        for (UUID shopId : shopIds) {
            ShopData shop = shopManager.getShop(shopId);
            if (shop != null && shop.isOpen()) {
                result.add(shop);
            }
        }
        return result;
    }

    /**
     * Returns currently featured shops (admin-promoted or "Shop of the Week").
     */
    public List<ShopData> getFeaturedShops() {
        long now = System.currentTimeMillis();
        List<ShopData> result = new ArrayList<>();

        for (ShopData shop : shopManager.getAllShops()) {
            if (!shop.isFeatured() || shop.getFeaturedUntil() <= now) continue;
            if (!shop.isOpen()) continue;
            if (shop.isPlayerShop() && !shop.isListedInDirectory()) continue;
            result.add(shop);
        }

        // Sort featured by rating descending
        result.sort(Comparator.comparingDouble(ShopData::getAverageRating).reversed());
        return result;
    }

    /**
     * Returns the top-rated shops up to the given limit.
     * Only includes shops with at least {@link #MIN_RATINGS_THRESHOLD} ratings.
     *
     * @param limit maximum number of shops to return
     */
    public List<ShopData> getTopRatedShops(int limit) {
        return shopManager.getAllShops().stream()
            .filter(ShopData::isOpen)
            .filter(s -> !s.isPlayerShop() || s.isListedInDirectory())
            .filter(s -> s.getTotalRatings() >= MIN_RATINGS_THRESHOLD)
            .sorted(Comparator.comparingDouble(ShopData::getAverageRating).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Helper: returns the minimum buy price across all items in a shop.
     * Used for price-based sorting.
     */
    private static int getMinBuyPrice(ShopData shop) {
        if (shop.getItems() == null || shop.getItems().isEmpty()) return Integer.MAX_VALUE;
        int min = Integer.MAX_VALUE;
        for (ShopItem item : shop.getItems()) {
            if (item.isBuyEnabled() && item.getBuyPrice() < min) {
                min = item.getBuyPrice();
            }
        }
        return min;
    }
}
