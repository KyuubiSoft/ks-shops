package com.kyuubisoft.common.kslang;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

/**
 * /kslang — Language selection command.
 * /kslang         → opens the language selection UI page
 * /kslang <code>  → sets language directly (e.g. /kslang de-DE)
 * /kslang auto    → clears override, uses client language
 */
public class KsLangCommand extends AbstractPlayerCommand {
    @Override
    protected boolean canGeneratePermission() { return false; }


    public KsLangCommand() {
        super("kslang", "Select your preferred language");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef playerRef, World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        String input = ctx.getInputString();
        String[] parts = (input != null) ? input.trim().split("\\s+") : new String[0];
        // parts[0] is the command name itself, parts[1] is the first argument
        String arg = parts.length > 1 ? parts[1].trim() : null;

        if (arg == null || arg.isEmpty()) {
            // No args → open language selection page
            KsLangPage page = new KsLangPage(playerRef);
            player.getPageManager().openCustomPage(ref, store, page);
            return;
        }

        if ("auto".equalsIgnoreCase(arg) || "reset".equalsIgnoreCase(arg)) {
            // Clear override
            KsLang.clearPlayerLanguage(playerRef.getUuid());
            String detected = playerRef.getLanguage();
            if (detected == null || detected.isEmpty()) detected = "en-US";
            playerRef.sendMessage(Message.raw(
                "\u00a7a[KsLang] \u00a77Language override removed. Using auto-detected: \u00a7f" + detected));
            return;
        }

        // Try to match a language code
        Map<String, String> supported = KsLang.getSupportedLanguages();
        for (Map.Entry<String, String> entry : supported.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(arg) || entry.getKey().replace("-", "").equalsIgnoreCase(arg)) {
                KsLang.setPlayerLanguage(playerRef.getUuid(), entry.getKey());
                playerRef.sendMessage(Message.raw(
                    "\u00a7a[KsLang] \u00a77Language set to: \u00a7f" + entry.getValue() + " (" + entry.getKey() + ")"));
                return;
            }
        }

        // Unknown code — show help
        StringBuilder sb = new StringBuilder();
        sb.append("\u00a7e[KsLang] \u00a77Unknown language code: \u00a7c").append(arg);
        sb.append("\n\u00a77Available: ");
        boolean first = true;
        for (Map.Entry<String, String> entry : supported.entrySet()) {
            if (!first) sb.append("\u00a77, ");
            first = false;
            sb.append("\u00a7f").append(entry.getKey());
        }
        sb.append("\n\u00a77Use \u00a7f/kslang auto \u00a77to reset to auto-detect.");
        playerRef.sendMessage(Message.raw(sb.toString()));
    }
}
