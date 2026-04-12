package com.kyuubisoft.common.kslang;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * UI page for language selection.
 * Shows a grid of language buttons, the current selection, registered mods, and an auto-detect button.
 */
public class KsLangPage extends InteractiveCustomUIPage<KsLangPage.PageData> {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft KsLang");

    /** Language codes in display order — must match .ui button IDs */
    private static final String[] LANG_CODES = {
        "en-US", "de-DE", "fr-FR", "es-ES", "pt-BR", "ru-RU", "pl-PL", "tr-TR", "it-IT"
    };

    public KsLangPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder builder,
                      UIEventBuilder events, Store<EntityStore> store) {
        builder.append("Pages/KsLang/LanguagePage.ui");

        // Bind language buttons (IDs must be CamelCase, no underscores)
        for (String code : LANG_CODES) {
            String btnId = "#Btn" + langCodeToId(code);
            events.addEventBinding(CustomUIEventBindingType.Activating, btnId,
                EventData.of("Button", "lang_" + code), false);
        }

        // Bind auto button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BtnAuto",
            EventData.of("Button", "auto"), false);

        updateDisplay(builder);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) {
            sendUpdate(new UICommandBuilder(), false);
            return;
        }

        if (data.action.startsWith("lang_")) {
            String langCode = data.action.substring(5); // strip "lang_"
            KsLang.setPlayerLanguage(this.playerRef.getUuid(), langCode);
        } else if ("auto".equals(data.action)) {
            KsLang.clearPlayerLanguage(this.playerRef.getUuid());
        }

        rebuild();
    }

    private void updateDisplay(UICommandBuilder ui) {
        String currentLang = KsLang.getPlayerLanguage(this.playerRef);
        String override = KsLang.getPlayerOverride(this.playerRef.getUuid());
        boolean isAuto = (override == null || override.isEmpty());

        Map<String, String> supported = KsLang.getSupportedLanguages();

        // Highlight current language button, dim others
        for (String code : LANG_CODES) {
            String indId = "#Ind" + langCodeToId(code);
            boolean isActive = code.equals(currentLang);
            ui.set(indId + ".Background.Color", isActive ? "#00bfff" : "#00000000");
        }

        // Show current language info
        String currentDisplay = supported.getOrDefault(currentLang, currentLang);
        if (isAuto) {
            ui.set("#CurrentLangLabel.Text", "Current: " + currentDisplay + " (auto)");
            ui.set("#BtnAutoInd.Background.Color", "#00bfff");
        } else {
            ui.set("#CurrentLangLabel.Text", "Current: " + currentDisplay);
            ui.set("#BtnAutoInd.Background.Color", "#00000000");
        }

        // Registered mods list
        List<Map<String, String>> mods = KsLang.getRegisteredMods();
        String modList = mods.stream()
            .map(m -> m.getOrDefault("name", m.getOrDefault("id", "?")))
            .collect(Collectors.joining(", "));
        if (modList.isEmpty()) modList = "-";
        ui.set("#ModsLabel.Text", "Mods using KsLang: " + modList);
    }

    /** Converts "en-US" → "EnUS", "pt-BR" → "PtBR" (matching CamelCase UI element IDs) */
    private static String langCodeToId(String code) {
        String[] parts = code.split("-");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    public static class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .addField(new KeyedCodec<>("Button", Codec.STRING),
                (data, s) -> data.action = s, data -> data.action)
            .build();

        private String action;
    }
}
