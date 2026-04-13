package com.kyuubisoft.shops.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.ShopRating;
import com.kyuubisoft.shops.i18n.ShopI18n;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Rating page for KS-Shops.
 *
 * Allows a player to rate a shop (1-5 stars + optional comment)
 * and browse existing reviews with pagination.
 *
 * Opened from the ShopBrowsePage "Rate" button or /ksshop rate.
 *
 * Usage:
 * <pre>
 *   ShopRatingPage page = new ShopRatingPage(playerRef, player, plugin, shopData);
 *   player.getPageManager().openCustomPage(ref, store, page);
 * </pre>
 */
public class ShopRatingPage extends InteractiveCustomUIPage<ShopRatingPage.RatingData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final int REVIEWS_PER_PAGE = 5;

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;
    private final ShopData shopData;

    private int selectedStars = 0;
    private String comment = "";
    private int reviewPage = 0;
    private List<ShopRating> reviews;

    // Cooldown tracking: timestamp of last rating by this player for this shop
    private long lastRatingTimestamp = 0;

    public ShopRatingPage(PlayerRef playerRef, Player player, ShopPlugin plugin, ShopData shopData) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, RatingData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.shopData = shopData;
        this.reviews = new ArrayList<>();
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/ShopRating.ui");

        // Load reviews from DB
        loadReviews();

        // Check if player already rated this shop
        loadExistingRating();

        bindAllEvents(events);
        buildUI(ui);
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull RatingData data) {
        super.handleDataEvent(ref, store, data);

        if (data.stars != null) {
            handleStarClick(data.stars);
            return;
        }

        if (data.comment != null) {
            this.comment = data.comment.trim();
            // No visual refresh needed for comment typing, just store the value
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        if (data.button != null) {
            switch (data.button) {
                case "submit" -> {
                    handleSubmit();
                    return;
                }
                case "prevReview" -> {
                    handleReviewPagination(-1);
                    return;
                }
                case "nextReview" -> {
                    handleReviewPagination(1);
                    return;
                }
            }
        }

        // Catch-all: prevent permanent "Loading..." state
        this.sendUpdate(new UICommandBuilder(), false);
    }

    // ==================== STAR SELECTION ====================

    private void handleStarClick(String starStr) {
        try {
            int star = Integer.parseInt(starStr);
            if (star >= 1 && star <= 5) {
                selectedStars = star;
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("[ShopRating] Invalid star value: " + starStr);
        }
        refreshUI();
    }

    // ==================== SUBMIT ====================

    private void handleSubmit() {
        ShopI18n i18n = plugin.getI18n();
        ShopConfig.Ratings ratingsConfig = plugin.getShopConfig().getData().ratings;

        // Validate: must select stars
        if (selectedStars < 1 || selectedStars > 5) {
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.rate.select_stars")).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // Validate: cannot rate own shop
        if (playerRef.getUuid().equals(shopData.getOwnerUuid())) {
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.rate.own_shop")).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // Validate: cooldown check
        if (ratingsConfig.cooldownMinutes > 0 && lastRatingTimestamp > 0) {
            long cooldownMs = TimeUnit.MINUTES.toMillis(ratingsConfig.cooldownMinutes);
            long elapsed = System.currentTimeMillis() - lastRatingTimestamp;
            if (elapsed < cooldownMs) {
                long remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(cooldownMs - elapsed) + 1;
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.rate.cooldown", remainingMinutes)).color("#FF5555"));
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }
        }

        // Validate: comment length
        String sanitizedComment = comment != null ? comment.trim() : "";
        if (sanitizedComment.length() > ratingsConfig.commentMaxLength) {
            sanitizedComment = sanitizedComment.substring(0, ratingsConfig.commentMaxLength);
        }

        // Save the rating
        ShopDatabase database = plugin.getDatabase();
        ShopRating rating = new ShopRating(
            playerRef.getUuid(),
            playerRef.getUsername(),
            shopData.getId(),
            selectedStars,
            sanitizedComment,
            System.currentTimeMillis()
        );

        database.saveRating(rating);

        // Recalculate shop average
        List<ShopRating> allRatings = database.loadRatings(shopData.getId());
        shopData.recalculateRating(allRatings);

        // Save updated shop data (markDirty already called by recalculateRating;
        // persist immediately so the new average is reflected right away)
        database.saveShop(shopData);

        // Update local state
        this.reviews = allRatings;
        this.lastRatingTimestamp = rating.getTimestamp();

        player.sendMessage(Message.raw(
            i18n.get(playerRef, "shop.rate.success", selectedStars)).color("#55FF55"));

        // Refresh to show the updated reviews
        refreshUI();
    }

    // ==================== REVIEW PAGINATION ====================

    private void handleReviewPagination(int direction) {
        int totalPages = getReviewTotalPages();
        int newPage = reviewPage + direction;
        if (newPage >= 0 && newPage < totalPages) {
            reviewPage = newPage;
        }
        refreshUI();
    }

    // ==================== DATA LOADING ====================

    private void loadReviews() {
        try {
            reviews = plugin.getDatabase().loadRatings(shopData.getId());
        } catch (Exception e) {
            LOGGER.warning("[ShopRating] Failed to load reviews: " + e.getMessage());
            reviews = new ArrayList<>();
        }
    }

    private void loadExistingRating() {
        UUID playerUuid = playerRef.getUuid();
        for (ShopRating r : reviews) {
            if (r.getRaterUuid().equals(playerUuid)) {
                selectedStars = r.getStars();
                comment = r.getComment() != null ? r.getComment() : "";
                lastRatingTimestamp = r.getTimestamp();
                break;
            }
        }
    }

    // ==================== REFRESH ====================

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindAllEvents(events);
        buildUI(ui);
        this.sendUpdate(ui, events, false);
    }

    // ==================== EVENT BINDING ====================

    private void bindAllEvents(UIEventBuilder events) {
        // Star buttons
        for (int i = 1; i <= 5; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Star" + i,
                EventData.of("Stars", String.valueOf(i)), false);
        }

        // Comment field (ValueChanged)
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CommentField",
            EventData.of("@Comment", "#CommentField.Value"));

        // Submit button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SubmitBtn",
            EventData.of("Button", "submit"), false);

        // Review pagination
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RevPrevBtn",
            EventData.of("Button", "prevReview"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RevNextBtn",
            EventData.of("Button", "nextReview"), false);
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();

        // Title
        String shopName = shopData.getName() != null ? shopData.getName() : "Shop";
        ui.set("#RatingTitle.Text", i18n.get(playerRef, "shop.rating.title", shopName.toUpperCase()));

        // Build star visuals
        buildStars(ui);

        // Selected rating text
        if (selectedStars > 0) {
            ui.set("#SelectedRating.Text", i18n.get(playerRef, "shop.rating.selected_stars", selectedStars));
        } else {
            ui.set("#SelectedRating.Text", i18n.get(playerRef, "shop.rating.click_to_rate"));
        }

        // Pre-fill comment field if player already rated
        if (comment != null && !comment.isEmpty()) {
            ui.set("#CommentField.Value", comment);
        }

        // Average rating display
        buildAverageRating(ui);

        // Reviews list
        buildReviews(ui);
    }

    private void buildStars(UICommandBuilder ui) {
        for (int i = 1; i <= 5; i++) {
            String btnId = "#Star" + i;
            String labelId = "#StarLabel" + i;

            if (i <= selectedStars) {
                // Selected: gold background, white text
                ui.set(btnId + ".Style.Default.Background.Color", "#ffd700");
                ui.set(btnId + ".Style.Hovered.Background.Color", "#ffe44d");
                ui.set(labelId + ".Style.TextColor", "#ffffff");
            } else {
                // Unselected: gray background, dim text
                ui.set(btnId + ".Style.Default.Background.Color", "#333344");
                ui.set(btnId + ".Style.Hovered.Background.Color", "#444455");
                ui.set(labelId + ".Style.TextColor", "#555566");
            }
        }
    }

    private void buildAverageRating(UICommandBuilder ui) {
        ShopI18n i18n = plugin.getI18n();
        double avgRating = shopData.getAverageRating();
        int totalRatings = shopData.getTotalRatings();

        if (totalRatings > 0) {
            int maxStars = plugin.getShopConfig().getData().ratings.maxStars;
            int filled = (int) Math.round(avgRating);
            StringBuilder starBar = new StringBuilder();
            for (int i = 0; i < maxStars; i++) {
                starBar.append(i < filled ? "*" : ".");
            }
            ui.set("#AvgRating.Text", i18n.get(playerRef, "shop.rating.average",
                starBar.toString(), String.format("%.1f", avgRating), totalRatings));
        } else {
            ui.set("#AvgRating.Text", i18n.get(playerRef, "shop.rating.no_reviews"));
        }
    }

    private void buildReviews(UICommandBuilder ui) {
        int totalReviews = reviews.size();
        int totalPages = getReviewTotalPages();

        // Clamp review page
        if (reviewPage >= totalPages) reviewPage = Math.max(0, totalPages - 1);

        int startIndex = reviewPage * REVIEWS_PER_PAGE;

        for (int i = 0; i < REVIEWS_PER_PAGE; i++) {
            String prefix = "#Review" + i;
            int actualIndex = startIndex + i;

            if (actualIndex < totalReviews) {
                ShopRating review = reviews.get(actualIndex);
                ui.set(prefix + ".Visible", true);

                // Stars as ASCII
                StringBuilder starStr = new StringBuilder();
                for (int s = 0; s < 5; s++) {
                    starStr.append(s < review.getStars() ? "*" : ".");
                }
                ui.set(prefix + " #RevStars.Text", starStr.toString());

                // Author
                ui.set(prefix + " #RevAuthor.Text", review.getRaterName());

                // Time (relative)
                ui.set(prefix + " #RevTime.Text", formatRelativeTime(review.getTimestamp()));

                // Comment
                String revComment = review.getComment();
                if (revComment != null && !revComment.isEmpty()) {
                    ui.set(prefix + " #RevComment.Text", revComment);
                    ui.set(prefix + " #RevComment.Visible", true);
                } else {
                    ui.set(prefix + " #RevComment.Text", "");
                    ui.set(prefix + " #RevComment.Visible", false);
                }
            } else {
                ui.set(prefix + ".Visible", false);
            }
        }

        // Pagination controls
        ui.set("#RevPageInfo.Text", (reviewPage + 1) + "/" + Math.max(1, totalPages));
        ui.set("#RevPrevBtn.Visible", reviewPage > 0);
        ui.set("#RevNextBtn.Visible", reviewPage < totalPages - 1);
    }

    // ==================== HELPERS ====================

    private int getReviewTotalPages() {
        int totalReviews = (reviews != null) ? reviews.size() : 0;
        return Math.max(1, (int) Math.ceil(totalReviews / (double) REVIEWS_PER_PAGE));
    }

    /**
     * Formats a timestamp into a relative time string.
     */
    private String formatRelativeTime(long timestamp) {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        if (diff < 0) return "just now";

        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";

        long hours = TimeUnit.MILLISECONDS.toHours(diff);
        if (hours < 24) return hours + "h ago";

        long days = TimeUnit.MILLISECONDS.toDays(diff);
        if (days < 30) return days + "d ago";

        long months = days / 30;
        if (months < 12) return months + "mo ago";

        return (days / 365) + "y ago";
    }

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[ShopRating] onDismiss error: " + e.getMessage());
        }
        LOGGER.fine("[ShopRating] Page dismissed for " + playerRef.getUsername()
            + " at shop " + shopData.getName());
    }

    // ==================== EVENT DATA CODEC ====================

    public static class RatingData {
        public static final BuilderCodec<RatingData> CODEC = BuilderCodec
            .<RatingData>builder(RatingData.class, RatingData::new)
            .addField(new KeyedCodec<>("Stars", Codec.STRING),
                (data, value) -> data.stars = value,
                data -> data.stars)
            .addField(new KeyedCodec<>("@Comment", Codec.STRING),
                (data, value) -> data.comment = value,
                data -> data.comment)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .build();

        private String stars;
        private String comment;
        private String button;

        public RatingData() {}
    }
}
