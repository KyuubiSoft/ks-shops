package com.kyuubisoft.shops.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kyuubisoft.common.kslang.KsLang;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * I18n system for the Shop mod.
 * Loads localization files from the data folder with JAR fallback.
 * Supports 9 languages. Uses KsLang for per-player language resolution.
 */
public class ShopI18n {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final Gson GSON = new Gson();
    private static final String VERSION_KEY = "_localization_version";

    /**
     * Localization version history:
     * - v1: Initial release — all shop messages, commands, UI
     * - v2: Priority 1 bug fixes — 30+ keys for create/edit/rename/myshops/status/visit/notifications/error/help
     * - v3: Priority 2 bug fixes — create flow disabled/economy/claim keys, edit labels, unsaved warning
     * - v4: Priority 2 bug fixes — getting started help block, search/delete/rate command implementations
     * - v5: Priority 3 polish — collect earnings per-shop breakdown + economy failure message
     * - v6: Priority 3 polish — /ksshop stats and /ksshop transfer subcommands
     * - v7: Phase 3 mailbox refactor — purchase routes through mailbox, /ksshop collect redirects,
     *       buyback pool label, legacy balance migration string
     * - v8: Phase 2 mailbox UI — page strings, claim labels, item display name
     * - v9: Directory / browse / rating / notification polish — adds all missing UI keys
     *       referenced by ShopBrowsePage, ShopDirectoryPage, ShopRatingPage, ShopNotificationsPage
     *       and the new filter/tab label + search placeholder keys used by ShopDirectoryPage
     * - v12: NPC-only shop creation refactor — Shop_NPC_Token interaction keys,
     *        NPC skin picker labels, shop block -> NPC migration log string
     * - v13: NPC name tag toggle labels (shop.edit.name_tag.on/off)
     * - v14: deposit invalid-amount error key
     * - v15: shop pickup/replant feature — shop.pickup.* keys, shop.edit.pickup_button,
     *        shop.token.replanted for the Shop_NPC_Token reactivation path
     * - v16: directory item-search mode — shop.directory.mode.*,
     *        shop.directory.search.placeholder_items, shop.directory.item_count_stock,
     *        shop.directory.no_item_results
     * - v17: mailbox two-line row layout — shop.mailbox.row.item_title/money_title,
     *        shop.mailbox.row.from_item/from_money
     * - v18: typed purchase failure reasons — shop.buy.fail.* (own_shop, no_funds,
     *        out_of_stock, shop_closed, etc.)
     * - v19: item-search grid tooltip keys — shop.directory.tooltip.*
     *        (shop/owner/price/stock/unlimited/click_hint) for the native
     *        ItemGridSlot setName/setDescription override in the items tab
     * - v20: in-place buy confirm overlay for the directory's items tab —
     *        shop.directory.buy.* keys (title/price_each/stock/total/
     *        confirm/visit/cancel) for the single-click fast-buy dialog
     * - v21: browse card grid — shop.browse.stock_count / stock_unlimited
     *        for the always-visible stock label below each browse card
     * - v22: shop.directory.disabled — admin kill-switch error message
     *        when features.directory is set to false in the server config
     * - v23: directory listing purchase flow — shop.list.* keys for
     *        /ksshop list <days> (usage, no_shop, disabled, not_enough_
     *        funds, success, success_permanent) + shop.error.not_owner
     */
    private static final int CURRENT_LOCALIZATION_VERSION = 23;

    private final Path dataFolder;
    private final Map<String, Map<String, String>> languages = new HashMap<>();
    private String defaultLanguage = "en-US";

    public ShopI18n(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    // ==================== LOADING ====================

    public void load() {
        String[] langFiles = {
            "en-US.json", "de-DE.json", "fr-FR.json", "es-ES.json",
            "pt-BR.json", "ru-RU.json",
            "pl-PL.json", "tr-TR.json", "it-IT.json"
        };

        for (String fileName : langFiles) {
            String langName = fileName.replace(".json", "");
            Path langFile = dataFolder.resolve("localization").resolve(fileName);
            String resourcePath = "defaults/localization/" + fileName;

            if (!Files.exists(langFile)) {
                copyDefaultResource(resourcePath, langFile);
            } else {
                int existingVersion = getFileVersion(langFile);
                if (existingVersion < CURRENT_LOCALIZATION_VERSION) {
                    Path backupPath = langFile.getParent().resolve(langName + ".json.backup-v" + existingVersion);
                    try {
                        Files.copy(langFile, backupPath, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("[Shops] Created backup: " + backupPath.getFileName());
                        Files.delete(langFile);
                        copyDefaultResource(resourcePath, langFile);
                        LOGGER.info("[Shops] Updated " + langName + ".json from v" + existingVersion
                            + " to v" + CURRENT_LOCALIZATION_VERSION);
                    } catch (Exception e) {
                        LOGGER.warning("[Shops] Failed to update " + langName + ".json: " + e.getMessage());
                    }
                }
            }

            if (Files.exists(langFile)) {
                try (Reader reader = Files.newBufferedReader(langFile, StandardCharsets.UTF_8)) {
                    Map<String, String> messages = GSON.fromJson(reader,
                        new TypeToken<Map<String, String>>(){}.getType());
                    if (messages != null) {
                        languages.put(langName, messages);
                    }
                } catch (Exception e) {
                    LOGGER.warning("[Shops] Failed to load lang " + fileName + ": " + e.getMessage());
                }
            }
        }

        LOGGER.info("[Shops] Loaded " + languages.size() + " language(s)");
    }

    public void reload() {
        languages.clear();
        load();
    }

    public void setDefaultLanguage(String language) {
        this.defaultLanguage = language;
    }

    // ==================== THREAD-LOCAL RESOLUTION ====================

    /**
     * Get a message using the current thread's language (KsLang ThreadLocal).
     */
    public String get(String key) {
        String lang = resolveLanguage();
        return get(lang, key);
    }

    /**
     * Get a message with placeholders using the current thread's language.
     */
    public String get(String key, Object... params) {
        String lang = resolveLanguage();
        return get(lang, key, params);
    }

    // ==================== PER-PLAYER RESOLUTION ====================

    /**
     * Get a message for a specific player using KsLang language selection.
     */
    public String get(PlayerRef ref, String key) {
        String lang = KsLang.getPlayerLanguage(ref);
        return get(lang, key);
    }

    /**
     * Get a message with placeholders for a specific player.
     */
    public String get(PlayerRef ref, String key, Object... params) {
        String lang = KsLang.getPlayerLanguage(ref);
        return get(lang, key, params);
    }

    // ==================== INTERNAL ====================

    /**
     * Get a message for a specific language code, with en-US fallback.
     */
    public String get(String language, String key) {
        Map<String, String> lang = languages.get(language);
        if (lang != null) {
            String value = lang.get(key);
            if (value != null) return value;
        }
        if (!language.equals(defaultLanguage)) {
            Map<String, String> fallback = languages.get(defaultLanguage);
            if (fallback != null) {
                String value = fallback.get(key);
                if (value != null) return value;
            }
        }
        return key;
    }

    /**
     * Get a message with placeholder replacement for a specific language.
     */
    public String get(String language, String key, Object... params) {
        String template = get(language, key);
        for (int i = 0; i < params.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(params[i]));
        }
        return template;
    }

    /**
     * Resolves the current language: KsLang ThreadLocal -> default.
     */
    private String resolveLanguage() {
        String lang = KsLang.getCurrentLanguage();
        return lang != null ? lang : defaultLanguage;
    }

    private int getFileVersion(Path filePath) {
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            Map<String, String> data = GSON.fromJson(reader,
                new TypeToken<Map<String, String>>(){}.getType());
            if (data != null && data.containsKey(VERSION_KEY)) {
                return Integer.parseInt(data.get(VERSION_KEY));
            }
        } catch (Exception e) {
            LOGGER.warning("[Shops] Failed to read version from " + filePath.getFileName() + ": " + e.getMessage());
        }
        return 0;
    }

    private void copyDefaultResource(String resourcePath, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is != null) {
                    Files.copy(is, target);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("[Shops] Failed to copy default " + resourcePath + ": " + e.getMessage());
        }
    }
}
