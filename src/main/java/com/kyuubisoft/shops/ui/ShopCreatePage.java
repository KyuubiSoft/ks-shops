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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.kyuubisoft.shops.ShopPlugin;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopData;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.CreateShopResult;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

/**
 * Shop creation wizard page.
 *
 * Opened via /ksshop create <name> or the directory "Create Shop" button.
 * Allows the player to set name, category, description, see a live preview,
 * review the creation cost, and confirm.
 *
 * Usage:
 * <pre>
 *   ShopCreatePage page = new ShopCreatePage(playerRef, player, ref, store, plugin);
 *   player.getPageManager().openCustomPage(ref, store, page);
 * </pre>
 */
public class ShopCreatePage extends InteractiveCustomUIPage<ShopCreatePage.CreateData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private static final String[] CATEGORIES = {
        "weapons", "armor", "tools", "resources", "potions", "food", "building", "misc"
    };

    private static final String[] CATEGORY_DISPLAY = {
        "Weapons", "Armor", "Tools", "Resources", "Potions", "Food", "Building", "Misc"
    };

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;

    // Staged input values
    private String shopName;
    private String category;
    private String description;

    /**
     * @param playerRef player reference
     * @param player    player entity (for messages)
     * @param plugin    main plugin instance
     */
    public ShopCreatePage(PlayerRef playerRef, Player player, ShopPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, CreateData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.shopName = "";
        this.category = CATEGORIES[0];
        this.description = "";
    }

    /**
     * Constructor with pre-filled name (from /ksshop create <name>).
     */
    public ShopCreatePage(PlayerRef playerRef, Player player, ShopPlugin plugin, String initialName) {
        this(playerRef, player, plugin);
        this.shopName = initialName != null ? initialName.trim() : "";
    }

    // ==================== BUILD ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/ShopCreate.ui");

        bindAllEvents(events);
        buildUI(ui);
    }

    // ==================== EVENT HANDLING ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull CreateData data) {
        super.handleDataEvent(ref, store, data);

        // Text field changes: update staged values and refresh preview
        if (data.name != null) {
            handleNameChange(data.name);
            return;
        }

        if (data.desc != null) {
            handleDescChange(data.desc);
            return;
        }

        if (data.category != null) {
            handleCategoryChange(data.category);
            return;
        }

        if (data.button != null) {
            switch (data.button) {
                case "create" -> handleCreate(ref, store);
                case "cancel" -> handleCancel(ref, store);
                default -> this.sendUpdate(new UICommandBuilder(), false);
            }
            return;
        }

        // Catch-all: prevent permanent "Loading..." state
        this.sendUpdate(new UICommandBuilder(), false);
    }

    // ==================== INPUT HANDLERS ====================

    private void handleNameChange(String value) {
        shopName = value != null ? value.trim() : "";
        refreshUI();
    }

    private void handleDescChange(String value) {
        description = value != null ? value.trim() : "";
        refreshUI();
    }

    private void handleCategoryChange(String value) {
        // Value comes from dropdown as the entry's Value string
        if (value != null && !value.isEmpty()) {
            category = value;
        }
        refreshUI();
    }

    // ==================== CREATE ====================

    private void handleCreate(Ref<EntityStore> ref, Store<EntityStore> store) {
        ShopI18n i18n = plugin.getI18n();
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();

        // --- Validate name ---
        if (shopName.isEmpty()) {
            player.sendMessage(Message.raw(i18n.get(playerRef, "shop.create.name_empty")).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        if (shopName.length() < cfg.playerShops.nameMinLength) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.create.name_too_short", cfg.playerShops.nameMinLength)
            ).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }
        if (shopName.length() > cfg.playerShops.nameMaxLength) {
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.create.name_too_long", cfg.playerShops.nameMaxLength)
            ).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
            return;
        }

        // --- Validate funds ---
        int cost = cfg.playerShops.creationCost;
        if (cost > 0) {
            double balance = plugin.getEconomyBridge().getBalance(playerRef.getUuid());
            if (balance < cost) {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.create.not_enough", cost)
                ).color("#FF5555"));
                this.sendUpdate(new UICommandBuilder(), false);
                return;
            }
        }

        // --- Create the shop via ShopService ---
        // Position: use player's current position
        String worldName = "";
        double x = 0, y = 0, z = 0;
        try {
            TransformComponent tc = player.getTransformComponent();
            if (tc != null) {
                var pos = tc.getPosition();
                x = pos.x;
                y = pos.y;
                z = pos.z;
            }
            var world = player.getWorld();
            if (world != null) {
                worldName = world.getName();
            }
        } catch (Exception e) {
            LOGGER.warning("[ShopCreate] Failed to get player position: " + e.getMessage());
        }

        CreateShopResult result = plugin.getShopService().createPlayerShop(
            playerRef, shopName, category, description,
            worldName, x, y, z
        );

        if (result.isSuccess()) {
            ShopData created = result.getShop();
            player.sendMessage(Message.raw(
                i18n.get(playerRef, "shop.create.success", created.getName())
            ).color("#55FF55"));

            // Close the page
            try {
                this.close();
            } catch (Exception e) {
                LOGGER.warning("[ShopCreate] Failed to close page: " + e.getMessage());
            }
        } else {
            String errorKey = result.getErrorKey() != null
                ? result.getErrorKey()
                : "shop.create.failed";
            player.sendMessage(Message.raw(
                i18n.get(playerRef, errorKey)
            ).color("#FF5555"));
            this.sendUpdate(new UICommandBuilder(), false);
        }
    }

    private void handleCancel(Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            this.close();
        } catch (Exception e) {
            LOGGER.warning("[ShopCreate] Failed to close page on cancel: " + e.getMessage());
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
        // Text field changes
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ShopNameField",
            EventData.of("@Name", "#ShopNameField.Value"));

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#DescField",
            EventData.of("@Desc", "#DescField.Value"));

        // Category dropdown
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CategoryDropdown",
            EventData.of("@Category", "#CategoryDropdown.Value"));

        // Buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CreateBtn",
            EventData.of("Button", "create"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelBtn",
            EventData.of("Button", "cancel"), false);
    }

    // ==================== BUILD UI ====================

    private void buildUI(UICommandBuilder ui) {
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();

        // Preview section
        String displayName = shopName.isEmpty() ? "..." : shopName;
        ui.set("#PreviewName.Text", displayName);

        String categoryDisplay = getCategoryDisplay(category);
        ui.set("#PreviewCategory.Text", categoryDisplay);

        ui.set("#PreviewOwner.Text", "by " + playerRef.getUsername());

        // Cost section
        int cost = cfg.playerShops.creationCost;
        ui.set("#CostAmount.Text", String.valueOf(cost));

        // Tax info
        if (cfg.tax.enabled) {
            ui.set("#TaxInfo.Text", String.format("+ %.1f%% buy tax, %.1f%% sell tax",
                cfg.tax.buyTaxPercent, cfg.tax.sellTaxPercent));
        } else {
            ui.set("#TaxInfo.Text", "No taxes");
        }

        // Pre-fill fields on initial load
        if (!shopName.isEmpty()) {
            ui.set("#ShopNameField.Value", shopName);
        }
        if (!description.isEmpty()) {
            ui.set("#DescField.Value", description);
        }
        // Reflect the currently-staged category in the dropdown so users see a
        // default selection instead of a blank widget on initial open.
        if (category != null && !category.isEmpty()) {
            ui.set("#CategoryDropdown.Value", category);
        }
    }

    // ==================== HELPERS ====================

    private String getCategoryDisplay(String catId) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(catId)) {
                return CATEGORY_DISPLAY[i];
            }
        }
        return "Misc";
    }

    // ==================== DISMISS ====================

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            super.onDismiss(ref, store);
        } catch (Exception e) {
            LOGGER.warning("[ShopCreate] onDismiss error: " + e.getMessage());
        }
        LOGGER.fine("[ShopCreate] Page dismissed for " + playerRef.getUsername());
    }

    // ==================== EVENT DATA CODEC ====================

    public static class CreateData {
        public static final BuilderCodec<CreateData> CODEC = BuilderCodec
            .<CreateData>builder(CreateData.class, CreateData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .addField(new KeyedCodec<>("@Name", Codec.STRING),
                (data, value) -> data.name = value,
                data -> data.name)
            .addField(new KeyedCodec<>("@Desc", Codec.STRING),
                (data, value) -> data.desc = value,
                data -> data.desc)
            .addField(new KeyedCodec<>("@Category", Codec.STRING),
                (data, value) -> data.category = value,
                data -> data.category)
            .build();

        private String button;
        private String name;
        private String desc;
        private String category;

        public CreateData() {}
    }
}
