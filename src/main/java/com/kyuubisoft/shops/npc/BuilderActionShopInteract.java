package com.kyuubisoft.shops.npc;

import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

/**
 * Builder for the ShopNpcInteract NPC action.
 * Registered via NPCPlugin.registerCoreComponentType("ShopNpcInteract", ...).
 *
 * Standalone clone of Core's BuilderActionCitizenInteract so shops does not
 * depend on Core for the F-key flow on its NPCs.
 */
public class BuilderActionShopInteract extends BuilderActionBase {

    @Override
    public String getShortDescription() {
        return "Shop NPC Interaction";
    }

    @Override
    public String getLongDescription() {
        return "F-key interaction for KS-Shops NPCs - opens the owner editor or visitor browser.";
    }

    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Override
    public Action build(BuilderSupport builderSupport) {
        return new ActionShopInteract(this);
    }
}
