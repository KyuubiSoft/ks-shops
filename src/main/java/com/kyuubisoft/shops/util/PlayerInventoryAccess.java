package com.kyuubisoft.shops.util;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * ECS-based Player inventory access.
 * Local copy — no dependency on Core module.
 */
public class PlayerInventoryAccess {

    private final Ref<EntityStore> ref;
    private final Store<EntityStore> store;

    private PlayerInventoryAccess(Ref<EntityStore> ref, Store<EntityStore> store) {
        this.ref = ref;
        this.store = store;
    }

    public static PlayerInventoryAccess of(Player player) {
        Ref<EntityStore> ref = player.getReference();
        return new PlayerInventoryAccess(ref, ref.getStore());
    }

    public static PlayerInventoryAccess of(Ref<EntityStore> ref, Store<EntityStore> store) {
        return new PlayerInventoryAccess(ref, store);
    }

    public ItemContainer getHotbar() {
        var comp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        return comp != null ? comp.getInventory() : null;
    }

    public ItemContainer getStorage() {
        var comp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        return comp != null ? comp.getInventory() : null;
    }

    public ItemContainer getArmor() {
        var comp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        return comp != null ? comp.getInventory() : null;
    }

    public CombinedItemContainer getCombinedHotbarFirst() {
        return InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
    }

    public CombinedItemContainer getCombinedStorageFirst() {
        return InventoryComponent.getCombined(store, ref, InventoryComponent.STORAGE_FIRST);
    }

    public void markChanged() {
        try {
            var hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            if (hotbar != null) hotbar.markDirty();
        } catch (Exception ignored) {}
        try {
            var storage = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            if (storage != null) storage.markDirty();
        } catch (Exception ignored) {}
    }
}
