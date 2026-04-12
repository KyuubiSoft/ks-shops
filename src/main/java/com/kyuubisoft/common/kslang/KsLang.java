package com.kyuubisoft.common.kslang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Shared language selection system for all KyuubiSoft mods.
 * Coordination happens via shared files on disk (plugins/kslang/),
 * NOT via cross-classloader static state.
 *
 * Each mod embeds its own copy of this class. The first mod to start
 * registers the /kslang command and claims ownership via a system property.
 */
public final class KsLang {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft KsLang");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String OWNER_PROPERTY = "kyuubisoft.kslang.owner";

    /** Shared directory: plugins/kslang/ (sibling to each mod's data dir) */
    private static Path sharedDir;

    /** In-memory cache: player UUID -> language code override */
    private static final Map<String, String> prefsCache = new ConcurrentHashMap<>();

    /** Listeners called when a player's language changes (per-classloader) */
    private static final List<java.util.function.BiConsumer<UUID, String>> changeListeners =
        Collections.synchronizedList(new ArrayList<>());

    /** Whether this classloader instance has been initialized */
    private static boolean initialized = false;

    /** Reference to the plugin that called init() in this classloader */
    private static JavaPlugin owningPlugin;

    /** Known display names for common language codes (fallback when no file-based discovery) */
    private static final Map<String, String> KNOWN_LANGUAGE_NAMES = new LinkedHashMap<>();
    static {
        KNOWN_LANGUAGE_NAMES.put("en-US", "English");
        KNOWN_LANGUAGE_NAMES.put("de-DE", "Deutsch");
        KNOWN_LANGUAGE_NAMES.put("fr-FR", "Francais");
        KNOWN_LANGUAGE_NAMES.put("es-ES", "Espanol");
        KNOWN_LANGUAGE_NAMES.put("pt-BR", "Portugues (BR)");
        KNOWN_LANGUAGE_NAMES.put("ru-RU", "Russian");
        KNOWN_LANGUAGE_NAMES.put("pl-PL", "Polski");
        KNOWN_LANGUAGE_NAMES.put("tr-TR", "Turkish");
        KNOWN_LANGUAGE_NAMES.put("it-IT", "Italiano");
    }

    /** Discovered languages from all registered mods' localization folders */
    private static final Map<String, String> discoveredLanguages = new LinkedHashMap<>();

    private KsLang() {}

    /**
     * Initialize KsLang for this mod. Call in your plugin's setup() method.
     *
     * @param plugin         The JavaPlugin instance
     * @param modId          Unique mod identifier (e.g. "itemcontrol")
     * @param modDisplayName Human-readable name (e.g. "Item Control")
     */
    public static void init(JavaPlugin plugin, String modId, String modDisplayName) {
        if (initialized) {
            // Already initialized by this classloader — just discover extra languages
            discoverLanguages(plugin.getDataDirectory().resolve("localization"));
            return;
        }
        initialized = true;
        owningPlugin = plugin;

        // Shared dir is plugins/kslang/ — parent of the plugin's data dir holds all plugin dirs
        sharedDir = plugin.getDataDirectory().getParent().resolve("kslang");
        try {
            Files.createDirectories(sharedDir);
        } catch (IOException e) {
            LOGGER.warning("[KsLang] Failed to create shared directory: " + e.getMessage());
        }

        // Register this mod in mods.json
        registerMod(modId, modDisplayName);

        // Load player prefs into memory cache
        loadPrefs();

        // Discover languages from this mod's localization folder
        discoverLanguages(plugin.getDataDirectory().resolve("localization"));

        // If no other mod has claimed the /kslang command, claim it
        String currentOwner = System.getProperty(OWNER_PROPERTY);
        if (currentOwner == null || currentOwner.isEmpty()) {
            System.setProperty(OWNER_PROPERTY, modId);
            try {
                plugin.getCommandRegistry().registerCommand(new KsLangCommand());
                LOGGER.info("[KsLang] Registered /kslang command (owner: " + modId + ")");
            } catch (Exception e) {
                LOGGER.warning("[KsLang] Failed to register /kslang command: " + e.getMessage());
            }
        } else {
            LOGGER.info("[KsLang] /kslang command already owned by " + currentOwner + ", skipping registration");
        }

        LOGGER.info("[KsLang] Initialized for mod: " + modDisplayName + " (" + modId + "), "
            + discoveredLanguages.size() + " languages discovered");
    }

    /** Timestamp of last prefs.json read — used to detect changes from other mods' classloaders */
    private static volatile long lastPrefsLoad = 0;
    private static final long PREFS_RELOAD_INTERVAL_MS = 5000;

    /**
     * Returns the effective language for a player.
     * Priority: 1) Override from prefs.json, 2) PlayerRef.getLanguage(), 3) "en-US"
     *
     * Automatically reloads prefs from disk every few seconds to pick up
     * changes made by other mods (which have their own classloader/cache).
     */
    public static String getPlayerLanguage(PlayerRef ref) {
        if (ref != null) {
            // Periodically reload prefs from disk (other mods may have changed them)
            long now = System.currentTimeMillis();
            if (now - lastPrefsLoad > PREFS_RELOAD_INTERVAL_MS) {
                loadPrefs();
                lastPrefsLoad = now;
            }

            // Check override first
            String uuid = ref.getUuid().toString();
            String override = prefsCache.get(uuid);
            if (override != null && !override.isEmpty()) {
                return override;
            }
            // Fall back to client language
            String clientLang = ref.getLanguage();
            if (clientLang != null && !clientLang.isEmpty()) {
                return clientLang;
            }
        }
        return "en-US";
    }

    // ==================== THREAD-LOCAL CONTEXT ====================

    /** ThreadLocal language context — set by run(), read by getCurrentLanguage(). */
    private static final ThreadLocal<String> THREAD_LANG = new ThreadLocal<>();

    /**
     * Runs an action with the player's resolved language set in the ThreadLocal.
     * Use this in UI build/event methods so that I18n.get() can resolve the player's language.
     */
    public static void run(PlayerRef ref, Runnable action) {
        THREAD_LANG.set(getPlayerLanguage(ref));
        try {
            action.run();
        } finally {
            THREAD_LANG.remove();
        }
    }

    /**
     * Returns the language set by run() for the current thread, or null if not in a run() context.
     */
    public static String getCurrentLanguage() {
        return THREAD_LANG.get();
    }

    /**
     * Sets a language override for a player. Persists to prefs.json.
     */
    public static void setPlayerLanguage(UUID playerId, String langCode) {
        prefsCache.put(playerId.toString(), langCode);
        savePrefs();
        lastPrefsLoad = System.currentTimeMillis();
        notifyListeners(playerId, langCode);
    }

    /**
     * Clears the language override for a player (reverts to auto-detect).
     */
    public static void clearPlayerLanguage(UUID playerId) {
        prefsCache.remove(playerId.toString());
        savePrefs();
        lastPrefsLoad = System.currentTimeMillis();
        notifyListeners(playerId, null);
    }

    /**
     * Registers a listener that fires when a player's language is changed via this classloader's KsLang.
     * The BiConsumer receives (playerUUID, newLangCode) — newLangCode is null for "auto".
     * Use this to refresh HUDs or other persistent UI when the language changes.
     */
    public static void addChangeListener(java.util.function.BiConsumer<UUID, String> listener) {
        changeListeners.add(listener);
    }

    private static void notifyListeners(UUID playerId, String langCode) {
        for (var listener : changeListeners) {
            try {
                listener.accept(playerId, langCode);
            } catch (Exception e) {
                LOGGER.fine("[KsLang] Listener error: " + e.getMessage());
            }
        }
    }

    /**
     * Returns the override language for a player, or null if none set.
     */
    public static String getPlayerOverride(UUID playerId) {
        return prefsCache.get(playerId.toString());
    }

    /**
     * Returns the map of discovered language codes to display names.
     * Languages are auto-discovered from localization folders of registered mods.
     */
    public static Map<String, String> getSupportedLanguages() {
        if (discoveredLanguages.isEmpty()) {
            // Fallback: return known languages if nothing discovered yet
            return Collections.unmodifiableMap(KNOWN_LANGUAGE_NAMES);
        }
        return Collections.unmodifiableMap(discoveredLanguages);
    }

    /**
     * Returns the list of registered mods from mods.json.
     * Each entry is a map with "id" and "name" keys.
     */
    public static List<Map<String, String>> getRegisteredMods() {
        Path modsFile = getModsFile();
        if (!Files.exists(modsFile)) return List.of();
        try {
            String content = Files.readString(modsFile, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<Map<String, String>>>() {}.getType();
            List<Map<String, String>> mods = GSON.fromJson(content, listType);
            return mods != null ? mods : List.of();
        } catch (Exception e) {
            LOGGER.warning("[KsLang] Failed to read mods.json: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Scans a localization folder for .json files and adds discovered languages.
     * File names like "de-DE.json" become language entries.
     * Custom files (custom_*.json), backups, and examples are ignored.
     */
    public static void discoverLanguages(Path localizationFolder) {
        if (localizationFolder == null || !Files.exists(localizationFolder) || !Files.isDirectory(localizationFolder)) return;
        try (var stream = Files.list(localizationFolder)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".json")
                    && !name.startsWith("custom_")
                    && !name.contains(".backup")
                    && !name.contains(".example");
            }).forEach(p -> {
                String name = p.getFileName().toString();
                String code = name.substring(0, name.length() - 5); // strip .json
                if (!discoveredLanguages.containsKey(code)) {
                    String displayName = KNOWN_LANGUAGE_NAMES.getOrDefault(code, code);
                    discoveredLanguages.put(code, displayName);
                }
            });
        } catch (IOException e) {
            LOGGER.fine("[KsLang] Could not scan localization folder: " + e.getMessage());
        }
    }

    // ---- Internal ----

    private static void registerMod(String modId, String modDisplayName) {
        Path modsFile = getModsFile();
        List<Map<String, String>> mods = new ArrayList<>();

        if (Files.exists(modsFile)) {
            try {
                String content = Files.readString(modsFile, StandardCharsets.UTF_8);
                Type listType = new TypeToken<List<Map<String, String>>>() {}.getType();
                List<Map<String, String>> existing = GSON.fromJson(content, listType);
                if (existing != null) mods.addAll(existing);
            } catch (Exception e) {
                LOGGER.warning("[KsLang] Failed to read existing mods.json: " + e.getMessage());
            }
        }

        // Check if already registered
        boolean found = false;
        for (Map<String, String> entry : mods) {
            if (modId.equals(entry.get("id"))) {
                entry.put("name", modDisplayName); // update name if changed
                found = true;
                break;
            }
        }
        if (!found) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", modId);
            entry.put("name", modDisplayName);
            mods.add(entry);
        }

        try {
            Files.writeString(modsFile, GSON.toJson(mods), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warning("[KsLang] Failed to write mods.json: " + e.getMessage());
        }
    }

    private static void loadPrefs() {
        Path prefsFile = getPrefsFile();
        if (!Files.exists(prefsFile)) {
            prefsCache.clear();
            return;
        }
        try {
            String content = Files.readString(prefsFile, StandardCharsets.UTF_8);
            Type mapType = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loaded = GSON.fromJson(content, mapType);
            if (loaded != null) {
                prefsCache.putAll(loaded);
                prefsCache.keySet().retainAll(loaded.keySet());
            }
        } catch (Exception e) {
            LOGGER.warning("[KsLang] Failed to load prefs.json: " + e.getMessage());
        }
    }

    private static void savePrefs() {
        Path prefsFile = getPrefsFile();
        try {
            Files.writeString(prefsFile, GSON.toJson(prefsCache), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warning("[KsLang] Failed to save prefs.json: " + e.getMessage());
        }
    }

    private static Path getPrefsFile() {
        return sharedDir.resolve("prefs.json");
    }

    private static Path getModsFile() {
        return sharedDir.resolve("mods.json");
    }
}
