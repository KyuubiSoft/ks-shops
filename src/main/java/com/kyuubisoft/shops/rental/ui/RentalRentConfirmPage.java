package com.kyuubisoft.shops.rental.ui;

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
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.rental.RentalService;
import com.kyuubisoft.shops.rental.RentalSlotData;
import com.kyuubisoft.shops.util.PermissionLimits;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

/**
 * Fixed-price rental confirmation dialog. Opened when a player F-keys
 * near a vacant FIXED-mode rental slot. Player picks a duration (1..maxDays),
 * sees the live total cost and their active-rental cap, then confirms to
 * commit the rental via {@link RentalService#rentSlot}.
 */
public class RentalRentConfirmPage extends InteractiveCustomUIPage<RentalRentConfirmPage.RentData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private final PlayerRef playerRef;
    private final Player player;
    private final ShopPlugin plugin;
    private final RentalSlotData slot;
    private int days;

    public RentalRentConfirmPage(PlayerRef playerRef, Player player,
                                 ShopPlugin plugin, RentalSlotData slot) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, RentData.CODEC);
        this.playerRef = playerRef;
        this.player = player;
        this.plugin = plugin;
        this.slot = slot;
        this.days = 1;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Shop/RentalRentConfirm.ui");
        bindEvents(events);
        buildUI(ui);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull RentData data) {
        super.handleDataEvent(ref, store, data);

        if (data.daysValue != null) {
            int clamped = Math.max(1, Math.min(slot.getMaxDays(), data.daysValue));
            if (clamped != days) {
                days = clamped;
                refreshUI();
            } else {
                this.sendUpdate(new UICommandBuilder(), false);
            }
            return;
        }

        if (data.button != null) {
            switch (data.button) {
                case "rent" -> handleRent();
                case "cancel" -> handleCancel();
                default -> this.sendUpdate(new UICommandBuilder(), false);
            }
            return;
        }
        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleRent() {
        ShopI18n i18n = plugin.getI18n();
        RentalService service = plugin.getRentalService();
        RentalService.RentResult result = service.rentSlot(playerRef, player, slot.getId(), days);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Message.raw(
                    i18n.get(playerRef, "shop.rental.rent_success",
                        slot.getDisplayName(), days)
                ).color("#55FF55"));
                try { this.close(); } catch (Exception ignored) {}
            }
            case NOT_VACANT -> sendError("shop.rental.already_rented");
            case LIMIT_REACHED -> sendError("shop.rental.limit_reached");
            case NO_PERMISSION -> sendError("shop.error.no_permission");
            case INVALID_DAYS -> sendError("shop.rental.invalid_days");
            case NOT_ENOUGH_FUNDS -> sendError("shop.rental.not_enough_funds");
            case ECONOMY_UNAVAILABLE -> sendError("shop.create.economy_unavailable");
            case SLOT_NOT_FOUND -> sendError("shop.rental.slot_not_found");
            default -> sendError("shop.rental.rent_failed");
        }
    }

    private void sendError(String key) {
        player.sendMessage(Message.raw(
            plugin.getI18n().get(playerRef, key)
        ).color("#FF5555"));
        this.sendUpdate(new UICommandBuilder(), false);
    }

    private void handleCancel() {
        try { this.close(); }
        catch (Exception e) {
            LOGGER.warning("[RentalRentConfirm] close failed: " + e.getMessage());
        }
    }

    private void refreshUI() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindEvents(events);
        buildUI(ui);
        this.sendUpdate(ui, events, false);
    }

    private void bindEvents(UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#DaysSlider",
            EventData.of("@Days", "#DaysSlider.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RentBtn",
            EventData.of("Button", "rent"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelBtn",
            EventData.of("Button", "cancel"), false);
    }

    private void buildUI(UICommandBuilder ui) {
        ShopConfig.ConfigData cfg = plugin.getShopConfig().getData();

        ui.set("#SlotName.Text", slot.getDisplayName());
        ui.set("#PriceValue.Text", slot.getPricePerDay() + " Gold");
        ui.set("#MaxDaysValue.Text", slot.getMaxDays() + " day(s)");
        ui.set("#DaysValue.Text", String.valueOf(days));
        ui.set("#DaysSlider.Value", days);

        int total = days * slot.getPricePerDay();
        ui.set("#TotalValue.Text", total + " Gold");

        int cap = PermissionLimits.resolveMaxRentals(
            player, cfg.rentalStations.maxConcurrentRentalsDefault);
        int current = plugin.getRentalService().countActiveRentalsFor(playerRef.getUuid());
        ui.set("#CapValue.Text", current + " / " + cap);

        String world = slot.getWorldName() == null ? "?" : slot.getWorldName();
        ui.set("#PositionValue.Text", world + " ["
            + (int) slot.getPosX() + ", "
            + (int) slot.getPosY() + ", "
            + (int) slot.getPosZ() + "]");
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try { super.onDismiss(ref, store); }
        catch (Exception e) {
            LOGGER.warning("[RentalRentConfirm] onDismiss error: " + e.getMessage());
        }
    }

    // ==================== EVENT DATA CODEC ====================

    public static class RentData {
        public static final BuilderCodec<RentData> CODEC = BuilderCodec
            .<RentData>builder(RentData.class, RentData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, value) -> data.button = value,
                data -> data.button)
            .addField(new KeyedCodec<>("@Days", Codec.INTEGER),
                (data, value) -> data.daysValue = value,
                data -> data.daysValue)
            .build();

        private String button;
        private Integer daysValue;

        public RentData() {}
    }
}
