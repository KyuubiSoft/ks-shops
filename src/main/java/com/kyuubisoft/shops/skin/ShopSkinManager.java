package com.kyuubisoft.shops.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Standalone skin manager for KS-Shops NPCs.
 *
 * - Fetches skins by username from PlayerDB (Hytale skin parts)
 * - Cache with configurable TTL (default 30 min)
 * - Disk cache survives server restarts
 * - Applies skins to spawned NPCs (PlayerSkinComponent + ModelComponent)
 *
 * No dependency on the Core mod. Cloned from CitizenSkinManager and simplified
 * to take a bare username + scale rather than a Core-specific CitizenData type.
 */
public class ShopSkinManager {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft ShopSkins");
    private static final String PLAYERDB_URL = "https://playerdb.co/api/player/hytale/";
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30 minutes
    private static final Gson DISK_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SKIN_MAP_TYPE = new TypeToken<Map<String, PlayerSkin>>() {}.getType();

    private final Map<String, CachedSkin> skinCache = new ConcurrentHashMap<>();
    private Path diskCachePath;
    private final HttpClient httpClient;

    public ShopSkinManager() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ==========================================================
    // Skin Fetching (async)
    // ==========================================================

    public CompletableFuture<PlayerSkin> fetchSkin(String username) {
        if (username == null || username.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CachedSkin cached = skinCache.get(username.toLowerCase());
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.skin);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchFromPlayerDB(username);
            } catch (Exception e) {
                LOGGER.warning("Failed to fetch skin for " + username + ": " + e.getMessage());
                if (cached != null) {
                    return cached.skin;
                }
                return null;
            }
        });
    }

    private PlayerSkin fetchFromPlayerDB(String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(PLAYERDB_URL + username))
            .header("User-Agent", "KyuubiSoft-Shops/1.0")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOGGER.warning("PlayerDB returned " + response.statusCode() + " for " + username);
            return null;
        }

        PlayerSkin skin = parseSkinResponse(response.body());
        if (skin != null) {
            skinCache.put(username.toLowerCase(), new CachedSkin(skin));
            LOGGER.info("Cached skin for " + username);
            saveDiskCache();
        }
        return skin;
    }

    private PlayerSkin parseSkinResponse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("data")) return null;
            JsonObject data = root.getAsJsonObject("data");

            if (!data.has("player")) return null;
            JsonObject player = data.getAsJsonObject("player");

            if (!player.has("skin")) return null;
            JsonObject skin = player.getAsJsonObject("skin");

            return new PlayerSkin(
                getStr(skin, "bodyCharacteristic"),
                getStr(skin, "underwear"),
                getStr(skin, "face"),
                getStr(skin, "eyes"),
                getStr(skin, "ears"),
                getStr(skin, "mouth"),
                getStr(skin, "facialHair"),
                getStr(skin, "haircut"),
                getStr(skin, "eyebrows"),
                getStr(skin, "pants"),
                getStr(skin, "overpants"),
                getStr(skin, "undertop"),
                getStr(skin, "overtop"),
                getStr(skin, "shoes"),
                getStr(skin, "headAccessory"),
                getStr(skin, "faceAccessory"),
                getStr(skin, "earAccessory"),
                getStr(skin, "skinFeature"),
                getStr(skin, "gloves"),
                getStr(skin, "cape")
            );
        } catch (Exception e) {
            LOGGER.warning("Failed to parse skin JSON: " + e.getMessage());
            return null;
        }
    }

    private String getStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            String val = obj.get(key).getAsString();
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    // ==========================================================
    // Disk Cache (skins survive server restarts)
    // ==========================================================

    public void loadDiskCache(Path dataFolder) {
        this.diskCachePath = dataFolder.resolve("skin-cache.json");
        if (!Files.exists(diskCachePath)) {
            LOGGER.info("No skin disk cache found, skins will be fetched from API on first spawn");
            return;
        }
        try {
            String content = new String(Files.readAllBytes(diskCachePath), StandardCharsets.UTF_8);
            Map<String, PlayerSkin> diskSkins = DISK_GSON.fromJson(content, SKIN_MAP_TYPE);
            if (diskSkins != null) {
                for (var entry : diskSkins.entrySet()) {
                    if (entry.getValue() != null) {
                        skinCache.put(entry.getKey().toLowerCase(), new CachedSkin(entry.getValue()));
                    }
                }
                LOGGER.info("Loaded " + diskSkins.size() + " skins from disk cache");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load skin disk cache: " + e.getMessage());
        }
    }

    private synchronized void saveDiskCache() {
        if (diskCachePath == null) return;
        try {
            Map<String, PlayerSkin> diskSkins = new HashMap<>();
            for (var entry : skinCache.entrySet()) {
                diskSkins.put(entry.getKey(), entry.getValue().skin);
            }
            String json = DISK_GSON.toJson(diskSkins);
            Files.write(diskCachePath, json.getBytes(StandardCharsets.UTF_8));
            LOGGER.fine("Saved " + diskSkins.size() + " skins to disk cache");
        } catch (Exception e) {
            LOGGER.warning("Failed to save skin disk cache: " + e.getMessage());
        }
    }

    public PlayerSkin getCachedSkin(String username) {
        if (username == null || username.isEmpty()) return null;
        CachedSkin cached = skinCache.get(username.toLowerCase());
        return cached != null ? cached.skin : null;
    }

    public Model createModelFromSkin(PlayerSkin skin, float scale) {
        if (skin == null) return null;
        try {
            CosmeticsModule cosmetics = CosmeticsModule.get();
            if (cosmetics != null) {
                float safeScale = Math.max(0.01f, scale);
                return cosmetics.createModel(skin, safeScale);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to create model from skin: " + e.getMessage());
        }
        return null;
    }

    // ==========================================================
    // Skin Application (on spawned NPC)
    // ==========================================================

    /**
     * Applies a PlayerSkin to a spawned NPC. MUST be called inside world.execute().
     */
    public void applySkin(Ref<EntityStore> entityRef, PlayerSkin skin, float scale) {
        if (entityRef == null || skin == null) return;
        // The async fetch path can race with our orphan sweep / despawn flow.
        // Bail before we touch the ECS if the ref is no longer valid -
        // store.putComponent on a stale ref throws and we'd just log a noisy
        // "Invalid entity reference" warning for a NPC that's already gone.
        if (!entityRef.isValid()) {
            LOGGER.fine("applySkin: entity ref no longer valid, skipping");
            return;
        }

        PlayerSkin safeSkin = ensureNoNullFields(skin);

        try {
            var store = entityRef.getStore();
            float safeScale = Math.max(0.01f, scale);

            CosmeticsModule cosmetics = CosmeticsModule.get();
            if (cosmetics == null) return;

            Model playerModel = cosmetics.createModel(safeSkin, safeScale);
            if (playerModel == null) {
                LOGGER.warning("createModel returned null - skin NOT applied");
                return;
            }

            // Re-check validity right before the actual writes - the async
            // gap between createModel and putComponent is small but still
            // gives the periodic sweep enough room to race in.
            if (!entityRef.isValid()) {
                LOGGER.fine("applySkin: entity ref invalidated before put, skipping");
                return;
            }

            PlayerSkinComponent skinComponent = new PlayerSkinComponent(safeSkin);
            store.putComponent(entityRef, PlayerSkinComponent.getComponentType(), skinComponent);

            ModelComponent modelComponent = new ModelComponent(playerModel);
            store.putComponent(entityRef, ModelComponent.getComponentType(), modelComponent);

            try {
                PersistentModel pm = store.getComponent(entityRef, PersistentModel.getComponentType());
                if (pm != null) {
                    pm.setModelReference(new Model.ModelReference(
                        playerModel.getModelAssetId(),
                        safeScale,
                        playerModel.getRandomAttachmentIds(),
                        playerModel.getAnimationSetMap() == null
                    ));
                }
            } catch (Exception e) {
                LOGGER.fine("Could not update PersistentModel after skin: " + e.getMessage());
            }

            LOGGER.fine("Applied skin to shop NPC (scale=" + safeScale + ")");
        } catch (Exception e) {
            LOGGER.warning("Failed to apply skin: " + e.getMessage());
        }
    }

    /**
     * Sanitizes a PlayerSkin so null / invalid fields are replaced with a
     * randomly-generated valid base. Prevents client ArgumentNullException and
     * server InvalidSkinException crashes on unusual input.
     */
    PlayerSkin ensureNoNullFields(PlayerSkin skin) {
        CosmeticsModule cosmetics = CosmeticsModule.get();
        CosmeticRegistry reg = cosmetics.getRegistry();

        try {
            cosmetics.validateSkin(skin);
            return skin;
        } catch (Exception ignored) {
            // Skin has null or invalid fields - sanitize below
        }

        PlayerSkin base = cosmetics.generateRandomSkin(new Random());
        PlayerSkin result = new PlayerSkin(base);
        if (skin.bodyCharacteristic != null && partIdValid(reg.getBodyCharacteristics(), skin.bodyCharacteristic))
            result.bodyCharacteristic = skin.bodyCharacteristic;
        if (skin.underwear != null && partIdValid(reg.getUnderwear(), skin.underwear))
            result.underwear = skin.underwear;
        if (skin.face != null && reg.getFaces().containsKey(skin.face))
            result.face = skin.face;
        if (skin.eyes != null && partIdValid(reg.getEyes(), skin.eyes))
            result.eyes = skin.eyes;
        if (skin.ears != null && reg.getEars().containsKey(skin.ears))
            result.ears = skin.ears;
        if (skin.mouth != null && reg.getMouths().containsKey(skin.mouth))
            result.mouth = skin.mouth;
        if (skin.facialHair != null && partIdValid(reg.getFacialHairs(), skin.facialHair))
            result.facialHair = skin.facialHair;
        if (skin.haircut != null && partIdValid(reg.getHaircuts(), skin.haircut))
            result.haircut = skin.haircut;
        if (skin.eyebrows != null && partIdValid(reg.getEyebrows(), skin.eyebrows))
            result.eyebrows = skin.eyebrows;
        if (skin.pants != null && partIdValid(reg.getPants(), skin.pants))
            result.pants = skin.pants;
        if (skin.overpants != null && partIdValid(reg.getOverpants(), skin.overpants))
            result.overpants = skin.overpants;
        if (skin.undertop != null && partIdValid(reg.getUndertops(), skin.undertop))
            result.undertop = skin.undertop;
        if (skin.overtop != null && partIdValid(reg.getOvertops(), skin.overtop))
            result.overtop = skin.overtop;
        if (skin.shoes != null && partIdValid(reg.getShoes(), skin.shoes))
            result.shoes = skin.shoes;
        if (skin.headAccessory != null && partIdValid(reg.getHeadAccessories(), skin.headAccessory))
            result.headAccessory = skin.headAccessory;
        if (skin.faceAccessory != null && partIdValid(reg.getFaceAccessories(), skin.faceAccessory))
            result.faceAccessory = skin.faceAccessory;
        if (skin.earAccessory != null && partIdValid(reg.getEarAccessories(), skin.earAccessory))
            result.earAccessory = skin.earAccessory;
        if (skin.skinFeature != null && partIdValid(reg.getSkinFeatures(), skin.skinFeature))
            result.skinFeature = skin.skinFeature;
        if (skin.gloves != null && partIdValid(reg.getGloves(), skin.gloves))
            result.gloves = skin.gloves;
        if (skin.cape != null && partIdValid(reg.getCapes(), skin.cape))
            result.cape = skin.cape;
        LOGGER.info("Sanitized skin: replaced null/invalid fields with random defaults");
        return result;
    }

    private static boolean partIdValid(Map<String, ?> registryMap, String compoundValue) {
        if (compoundValue == null || !compoundValue.contains(".")) return false;
        String baseId = compoundValue.substring(0, compoundValue.indexOf('.'));
        return registryMap.containsKey(baseId);
    }

    /**
     * Async fetch + apply. Fetch runs on a default executor, apply runs on the
     * provided worldExecutor (must be world.execute() for thread safety).
     */
    public CompletableFuture<PlayerSkin> fetchAndApplySkin(
            String username,
            float scale,
            Ref<EntityStore> entityRef,
            Executor worldExecutor) {

        if (username == null || username.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return fetchSkin(username).thenAcceptAsync(skin -> {
            if (skin != null && entityRef != null) {
                applySkin(entityRef, skin, scale);
            }
        }, worldExecutor).thenApply(v -> skinCache.containsKey(username.toLowerCase())
            ? skinCache.get(username.toLowerCase()).skin : null);
    }

    // ==========================================================
    // Cache Management
    // ==========================================================

    public void cleanupCache() {
        for (var entry : skinCache.entrySet()) {
            if (entry.getValue().isExpired()) {
                fetchSkin(entry.getKey());
            }
        }
    }

    public void clearCache() {
        skinCache.clear();
    }

    public int getCacheSize() {
        return skinCache.size();
    }

    // ==========================================================
    // Cache Entry
    // ==========================================================

    private static class CachedSkin {
        final PlayerSkin skin;
        final long fetchedAt;

        CachedSkin(PlayerSkin skin) {
            this.skin = skin;
            this.fetchedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS;
        }
    }
}
