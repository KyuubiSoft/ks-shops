package com.kyuubisoft.shops;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.kyuubisoft.shops.bridge.CoreBridge;
import com.kyuubisoft.shops.bridge.ClaimsBridge;
import com.kyuubisoft.shops.bridge.ShopEconomyBridge;
import com.kyuubisoft.shops.commands.ShopCommand;
import com.kyuubisoft.shops.commands.ShopAdminCommand;
import com.kyuubisoft.shops.config.ShopConfig;
import com.kyuubisoft.shops.data.ShopDatabase;
import com.kyuubisoft.shops.data.PlayerShopData;
import com.kyuubisoft.shops.event.ShopEventBus;
import com.kyuubisoft.shops.i18n.ShopI18n;
import com.kyuubisoft.shops.service.ShopService;
import com.kyuubisoft.shops.service.ShopManager;
import com.kyuubisoft.shops.service.ShopSessionManager;
import com.kyuubisoft.shops.service.DirectoryService;
import com.kyuubisoft.shops.npc.ShopNpcManager;
import com.kyuubisoft.shops.interaction.ShopBlockInteraction;
import com.kyuubisoft.shops.mailbox.MailboxService;
import com.kyuubisoft.shops.rental.RentalExpiryTask;
import com.kyuubisoft.shops.rental.RentalService;
import com.kyuubisoft.common.kslang.KsLang;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * KS-Shops: Advanced Shop System for Hytale.
 *
 * Combines admin shops (NPC-based, server-configured) with player shops
 * (hybrid block+NPC, drag&drop editor, custom pricing).
 * Standalone — optional Core and Claims integration via reflection bridges.
 */
public class ShopPlugin extends JavaPlugin {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static ShopPlugin instance;
    private static volatile boolean permissionsInitialized = false;

    private ShopConfig config;
    private ShopI18n i18n;
    private ShopDatabase database;
    private ShopEconomyBridge economyBridge;
    private ShopService shopService;
    private ShopManager shopManager;
    private ShopSessionManager sessionManager;
    private DirectoryService directoryService;
    private ShopNpcManager npcManager;
    private MailboxService mailboxService;
    private RentalService rentalService;
    private com.kyuubisoft.shops.skin.ShopSkinManager skinManager;

    private final Map<String, PlayerShopData> playerData = new ConcurrentHashMap<>();
    /** Online players for the per-tick shop NPC rotation (look-at-player). */
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public ShopPlugin(JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static ShopPlugin getInstance() {
        return instance;
    }

    // ==================== LIFECYCLE ====================

    @Override
    protected void setup() {
        LOGGER.info("Setting up KS-Shops...");

        try {
            // 0. KsLang (BEFORE i18n)
            KsLang.init(this, "shops", "Shops");

            // 1. Config
            config = new ShopConfig(getDataDirectory());
            config.load();

            // 2. I18n
            i18n = new ShopI18n(getDataDirectory());
            i18n.load();

            // 3. Database (SQLite or MySQL)
            database = new ShopDatabase();
            database.init(getDataDirectory(), config);

            // 3b. Mailbox service (depends on database)
            mailboxService = new MailboxService(database);

            // 3c. Standalone skin manager (PlayerDB + CosmeticsModule, no Core dep)
            skinManager = new com.kyuubisoft.shops.skin.ShopSkinManager();
            skinManager.loadDiskCache(getDataDirectory());
            LOGGER.info("Shop skin manager initialized (cache: " + skinManager.getCacheSize() + " entries)");

            // 4. Economy bridge
            economyBridge = new ShopEconomyBridge();
            economyBridge.detect();

            // 5. Services
            sessionManager = new ShopSessionManager();
            shopManager = new ShopManager(database, config);
            shopManager.loadAll();
            directoryService = new DirectoryService(shopManager);
            shopService = new ShopService(this, shopManager, sessionManager, economyBridge, config, i18n, database);

            // 5b. One-shot legacy balance migration (Phase 3 mailbox refactor).
            //     Converts pre-mailbox shopBalance values into MONEY mails so
            //     owners do not lose uncollected earnings on upgrade. Idempotent
            //     via .legacy_balances_migrated marker in the data folder.
            try {
                shopService.migrateLegacyShopBalances();
            } catch (Exception e) {
                LOGGER.warning("Legacy balance migration threw: " + e.getMessage());
            }

            // 6. NPC Manager
            npcManager = new ShopNpcManager(this, config, shopManager);

            // 6b. Rental Service (rental-station system). Loads its slot
            // cache from DB here so the first scheduler tick has data to
            // scan for expiry.
            rentalService = new RentalService(this, config, shopManager, database, mailboxService);
            rentalService.loadAll();

            // 7. Events
            getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect);
            getEventRegistry().register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);

            // Permission registration on first join
            getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, event -> {
                if (!permissionsInitialized) {
                    try {
                        Player p = event.getHolder().getComponent(Player.getComponentType());
                        if (p != null) {
                            permissionsInitialized = true;
                            for (String node : new String[]{
                                "ks.shop.user.create", "ks.shop.user.edit", "ks.shop.user.delete",
                                "ks.shop.user.browse", "ks.shop.user.buy", "ks.shop.user.sell",
                                "ks.shop.user.rate", "ks.shop.user.collect", "ks.shop.user.transfer"
                            }) {
                                try { p.hasPermission(node, true); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Spawn shop NPCs for this world (lazy, per-world)
                npcManager.onPlayerAddedToWorld(event);
            });

            // 7b. Shop Block/NPC Interaction (F-key handler)
            ShopBlockInteraction shopInteraction = new ShopBlockInteraction(this);
            getEventRegistry().registerGlobal(PlayerInteractEvent.class, shopInteraction::onPlayerInteract);

            // 7c. (LEGACY) Shop_Block placement flow is fully replaced by the NPC-only
            // Shop_NPC_Token flow below. The ShopBlockBlockInteraction class is kept on
            // disk so any legacy Shop_Block still placed in an existing world logs a
            // handled warning instead of crashing — but we no longer register its codec.
            // Any pre-existing block-anchored shops are auto-migrated to standalone NPCs
            // in the one-shot startup migration below.

            // 7d. Custom block interaction codec for mailbox blocks (F-key on mailbox block)
            try {
                getCodecRegistry(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.CODEC)
                    .register("mailbox_block_use",
                        com.kyuubisoft.shops.interaction.MailboxBlockBlockInteraction.class,
                        com.kyuubisoft.shops.interaction.MailboxBlockBlockInteraction.CODEC);
                LOGGER.info("Registered mailbox_block_use interaction codec");
            } catch (Exception e) {
                LOGGER.warning("Failed to register mailbox_block_use interaction: " + e.getMessage());
            }

            // 7e. Shop NPC Token — right-click a "Shop Business License" to spawn a
            // standalone shop NPC at the player's position. This replaces /ksshop
            // create and the Shop_Block placement flow.
            try {
                getCodecRegistry(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.CODEC)
                    .register("shop_npc_token_use",
                        com.kyuubisoft.shops.interaction.ShopNpcTokenInteraction.class,
                        com.kyuubisoft.shops.interaction.ShopNpcTokenInteraction.CODEC);
                LOGGER.info("Registered shop_npc_token_use interaction codec");
            } catch (Exception e) {
                LOGGER.warning("Failed to register shop_npc_token_use interaction: " + e.getMessage());
            }

            // 7g. Standalone shop NPC action type registration (setup phase).
            // The action class MUST be registered BEFORE the role JSON is loaded,
            // because builderManager.loadFile() resolves action types eagerly.
            // Role JSON loading itself happens in start() because assets (Appearance
            // "Trork", block sets, particle systems) are only available post-setup.
            try {
                var npcPlugin = com.hypixel.hytale.server.npc.NPCPlugin.get();
                if (npcPlugin != null) {
                    try {
                        npcPlugin.registerCoreComponentType("ShopNpcInteract",
                            com.kyuubisoft.shops.npc.BuilderActionShopInteract::new);
                        LOGGER.info("Registered ShopNpcInteract NPC action type");
                    } catch (Exception e) {
                        LOGGER.warning("Failed to register ShopNpcInteract action: " + e.getMessage());
                    }
                    // Extract the role JSON to disk now so the file is ready when
                    // start() calls builderManager.loadFile(). Extraction does NOT
                    // trigger asset resolution - that only happens on loadFile().
                    extractShopKeeperRoleFile();
                } else {
                    LOGGER.warning("NPCPlugin not available - shop NPC role not loaded");
                }
            } catch (Exception e) {
                LOGGER.warning("Shop NPC role setup failed: " + e.getMessage());
            }

            // 7f. One-shot migration: auto-convert pre-existing block-anchored shops
            // into standalone NPC shops. Guarded by a marker file in the data folder.
            try {
                shopService.migrateShopBlocksToNpcShops();
            } catch (Exception e) {
                LOGGER.warning("Shop NPC migration threw: " + e.getMessage());
            }

            // 8. Core bridge (optional NPC skins, economy providers)
            if (CoreBridge.isCoreAvailable()) {
                LOGGER.info("Core detected — enabling NPC skin integration.");
            } else {
                LOGGER.info("Core not found — using standalone NPC mode.");
            }

            // 9. Claims bridge (optional placement checks)
            if (ClaimsBridge.isAvailable()) {
                LOGGER.info("Claims detected — enabling placement protection.");
            }

            // 10. Commands
            getCommandRegistry().registerCommand(new ShopCommand(this));
            getCommandRegistry().registerCommand(new ShopAdminCommand(this));

            // 11. Auto-save scheduler (60s)
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ShopAutoSave");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::autoSave, 60, 60, TimeUnit.SECONDS);

            // 11a. Economy retry-detection. The first detect() call in step 4
            // can land before Vault-based providers (Ecotale, VaultUnlocked
            // bridges) register with VaultUnlocked - in which case the bridge
            // stays on NONE for the whole session, with no clear feedback to
            // the admin. We retry a few times during early boot so a slow
            // provider still gets picked up. Logs only on success.
            for (int delaySec : new int[] { 2, 5, 10, 20 }) {
                scheduler.schedule(() -> {
                    try {
                        if (economyBridge != null && economyBridge.retryDetect()) {
                            LOGGER.info("Economy provider attached on retry");
                        }
                    } catch (Exception e) {
                        LOGGER.fine("Economy retry failed: " + e.getMessage());
                    }
                }, delaySec, TimeUnit.SECONDS);
            }

            // 11b. Periodic orphan sweep: removes any shop NPCs that Hytale
            // restored from chunk persistence but are not tracked by us.
            //
            // Hytale's PersistentModel component keeps NPCEntity instances
            // alive inside chunk save data. We cannot remove PersistentModel
            // without crashing (documented pitfall in ShopNpcManager), so the
            // next-best fix is to let the ghosts reload on chunk-restore and
            // purge them shortly after. The sweep only matches Shop_Keeper_Role
            // and skips UUIDs we track ourselves, so other mods' NPCs are
            // safe.
            //
            // 3s initial delay + 5s tick keeps the window between "ghost
            // appears" and "ghost disappears" short enough that players
            // barely notice. Pets uses the same pattern for its own NPC
            // duplicates.
            scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        if (npcManager != null) {
                            npcManager.runPeriodicOrphanSweep();
                        }
                    } catch (Exception e) {
                        LOGGER.fine("Periodic orphan sweep tick failed: " + e.getMessage());
                    }
                },
                3, 5, TimeUnit.SECONDS
            );

            // 11c. Shop NPC look-at-player rotation tick (100ms, same cadence
            // as Core's CitizenRotationManager). Runs entirely off cached data
            // (shop position + NetworkId + player position), sends per-player
            // packets that are thread-safe - no world.execute() needed.
            if (config.getData().npc.lookAtPlayer) {
                scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            if (npcManager != null && !onlinePlayers.isEmpty()) {
                                npcManager.getRotationManager().tick(onlinePlayers.values());
                            }
                        } catch (Exception e) {
                            LOGGER.fine("Shop NPC rotation tick failed: " + e.getMessage());
                        }
                    },
                    1000, 100, TimeUnit.MILLISECONDS
                );
            }

            // 12. Rent collection scheduler (if enabled)
            if (config.getData().rent.enabled) {
                scheduler.scheduleAtFixedRate(
                    () -> shopService.collectRent(),
                    300, 3600, TimeUnit.SECONDS // First after 5min, then every hour
                );
            }

            // 12b. Rental-station expiry tick (if enabled). Scans every 60s
            // for expired rentals and mails items back to the renter.
            if (config.getData().rentalStations.enabled) {
                RentalExpiryTask rentalExpiryTask = new RentalExpiryTask(rentalService);
                scheduler.scheduleAtFixedRate(
                    rentalExpiryTask::tick,
                    60, 60, TimeUnit.SECONDS
                );

                // 12c. Live countdown tick for open RentalBidPage / MyRentalsPage
                // instances. Fires every 2s off the same scheduler so watchers
                // see time-remaining + high-bid updates without polling.
                scheduler.scheduleAtFixedRate(
                    () -> {
                        try { rentalService.tickOpenPages(); }
                        catch (Exception e) {
                            LOGGER.fine("rental tickOpenPages failed: " + e.getMessage());
                        }
                    },
                    2, 2, TimeUnit.SECONDS
                );
            }

            // 13. Shop of the Week scheduler (if enabled)
            if (config.getData().features.shopOfTheWeek) {
                scheduler.scheduleAtFixedRate(
                    () -> shopService.updateShopOfTheWeek(),
                    600, 86400, TimeUnit.SECONDS // First after 10min, then daily
                );
            }

            LOGGER.info("KS-Shops ready. " + shopManager.getShopCount() + " shops loaded.");
        } catch (Exception e) {
            LOGGER.severe("Failed to setup KS-Shops: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void start() {
        // Shop_Keeper_Role JSON loads here (not in setup) because the role
        // references engine assets like Appearance "Trork", block sets, and
        // particle systems that are only registered after setup() completes.
        // This mirrors how CorePlugin loads its own KS_NPC_*_Role files.
        try {
            var npcPlugin = com.hypixel.hytale.server.npc.NPCPlugin.get();
            if (npcPlugin == null) {
                LOGGER.warning("NPCPlugin still not available in start() - shop role not loaded");
                return;
            }
            loadShopKeeperRole(npcPlugin);
        } catch (Exception e) {
            LOGGER.warning("Shop NPC role start-phase load failed: " + e.getMessage());
        }
    }

    @Override
    protected void shutdown() {
        LOGGER.info("Disabling KS-Shops...");

        // 1. Shutdown schedulers
        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        // 2. Clear event bus
        ShopEventBus.getInstance().clear();

        // 3. Despawn all NPCs
        if (npcManager != null) {
            npcManager.despawnAll();
        }

        // 3b. Clear the rotation roster
        onlinePlayers.clear();

        // 4. Save all dirty shop data
        if (shopManager != null) {
            shopManager.saveAll();
        }

        // 4b. Save all dirty rental slots
        if (rentalService != null) {
            rentalService.saveDirty();
        }

        // 5. Save all player data
        for (Map.Entry<String, PlayerShopData> entry : playerData.entrySet()) {
            try {
                PlayerShopData data = entry.getValue();
                if (data != null && data.isDirty()) {
                    database.savePlayerData(data);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to save player data for " + entry.getKey() + ": " + e.getMessage());
            }
        }

        // 6. Close database
        if (database != null) {
            database.shutdown();
        }

        // 7. Close sessions
        if (sessionManager != null) {
            sessionManager.closeAll();
        }
        playerData.clear();

        LOGGER.info("KS-Shops disabled.");
    }

    // ==================== EVENTS ====================

    private void onPlayerConnect(PlayerConnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID uuid = playerRef.getUuid();
        String username = playerRef.getUsername();

        // Track the live Player for the NPC rotation tick (look-at-player).
        try {
            Player p = event.getPlayer();
            if (p != null) {
                onlinePlayers.put(uuid, p);
            }
        } catch (Exception ignored) {
            // If getPlayer() is unavailable the rotation tick simply skips this
            // player - it's a polish feature, not load-bearing.
        }

        CompletableFuture.runAsync(() -> {
            try {
                PlayerShopData data = database.loadPlayerData(uuid);
                if (data == null) {
                    data = new PlayerShopData(uuid.toString(), username);
                }
                if (!username.equals(data.getUsername())) {
                    data.setUsername(username);
                    data.markDirty();
                }
                playerData.put(uuid.toString(), data);

                // Check for offline notifications
                if (config.getData().notifications.enabled && config.getData().notifications.batchOnLogin) {
                    shopService.sendOfflineNotifications(playerRef, data);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to load shop data for " + username + ": " + e.getMessage());
            }
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID uuid = playerRef.getUuid();

        // Drop from the rotation tick roster.
        onlinePlayers.remove(uuid);

        // Close any open editor session
        sessionManager.closeSession(uuid);

        // Save and remove player data
        PlayerShopData data = playerData.remove(uuid.toString());
        if (data != null && data.isDirty()) {
            CompletableFuture.runAsync(() -> {
                try {
                    database.savePlayerData(data);
                } catch (Exception e) {
                    LOGGER.warning("Failed to save player data on disconnect: " + e.getMessage());
                }
            });
        }
    }

    private void autoSave() {
        // Save dirty shop data
        if (shopManager != null) {
            shopManager.saveDirty();
        }

        // Save dirty rental-slot data
        if (rentalService != null) {
            rentalService.saveDirty();
        }

        // Save dirty player data
        for (PlayerShopData data : playerData.values()) {
            if (data != null && data.isDirty()) {
                try {
                    database.savePlayerData(data);
                    data.clearDirty();
                } catch (Exception e) {
                    LOGGER.warning("Auto-save failed for player " + data.getUuid() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Extracts Shop_Keeper_Role.json from the shops JAR to the plugin data
     * directory without touching NPCPlugin. Called from setup() so the file is
     * on disk before start() registers it.
     */
    private void extractShopKeeperRoleFile() {
        try {
            java.nio.file.Path rolesDir = getDataDirectory().resolve("npc-roles");
            java.nio.file.Files.createDirectories(rolesDir);

            java.nio.file.Path targetFile = rolesDir.resolve("Shop_Keeper_Role.json");
            try (java.io.InputStream is = getClass().getResourceAsStream("/citizen-roles/Shop_Keeper_Role.json")) {
                if (is == null) {
                    LOGGER.warning("Shop_Keeper_Role.json not found in JAR");
                    return;
                }
                byte[] content = is.readAllBytes();
                if (content.length >= 3 && (content[0] & 0xFF) == 0xEF
                        && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
                    content = java.util.Arrays.copyOfRange(content, 3, content.length);
                }
                java.nio.file.Files.write(targetFile, content);
            }
        } catch (Exception e) {
            LOGGER.warning("extractShopKeeperRoleFile error: " + e.getMessage());
        }
    }

    /**
     * Registers Shop_Keeper_Role.json with NPCPlugin's BuilderManager. MUST run
     * in start() (not setup), because the role references engine assets
     * (Appearance "Trork", OpaqueBlockSet, Effect_Death particles, Fence block
     * set) that are only registered after all plugin setup() phases finish.
     * Running this in setup() triggers "model does not exist for Appearance"
     * errors and the role fails to load.
     */
    private void loadShopKeeperRole(com.hypixel.hytale.server.npc.NPCPlugin npcPlugin) {
        try {
            java.nio.file.Path targetFile = getDataDirectory().resolve("npc-roles").resolve("Shop_Keeper_Role.json");
            if (!java.nio.file.Files.exists(targetFile)) {
                // Fallback in case setup() did not extract for whatever reason
                extractShopKeeperRoleFile();
                if (!java.nio.file.Files.exists(targetFile)) {
                    LOGGER.warning("Shop_Keeper_Role.json missing on disk at " + targetFile);
                    return;
                }
            }

            var builderManager = npcPlugin.getBuilderManager();
            boolean forceReload = npcPlugin.getIndex("Shop_Keeper_Role") >= 0;
            java.util.List<String> errors = new java.util.ArrayList<>();
            int roleIndex = builderManager.loadFile(targetFile, forceReload, errors);
            if (roleIndex >= 0 && errors.isEmpty()) {
                LOGGER.info("Loaded Shop_Keeper_Role (index=" + roleIndex + ", forceReload=" + forceReload + ")");
            } else {
                LOGGER.warning("FAILED to load Shop_Keeper_Role (index=" + roleIndex + "): " + errors);
            }
        } catch (Exception e) {
            LOGGER.warning("loadShopKeeperRole error: " + e.getMessage());
        }
    }

    // ==================== GETTERS ====================

    public ShopConfig getShopConfig() { return config; }
    public ShopI18n getI18n() { return i18n; }
    public ShopDatabase getDatabase() { return database; }
    public ShopEconomyBridge getEconomyBridge() { return economyBridge; }
    public ShopService getShopService() { return shopService; }
    public ShopManager getShopManager() { return shopManager; }
    public ShopSessionManager getSessionManager() { return sessionManager; }
    public DirectoryService getDirectoryService() { return directoryService; }
    public ShopNpcManager getNpcManager() { return npcManager; }
    public MailboxService getMailboxService() { return mailboxService; }
    public RentalService getRentalService() { return rentalService; }
    public ScheduledExecutorService getScheduler() { return scheduler; }

    /** Returns the tracked {@link Player} for the given UUID, or null if offline. */
    public Player getOnlinePlayer(UUID uuid) { return onlinePlayers.get(uuid); }
    public Map<UUID, Player> getOnlinePlayers() { return onlinePlayers; }
    public com.kyuubisoft.shops.skin.ShopSkinManager getSkinManager() { return skinManager; }
    public Map<String, PlayerShopData> getPlayerDataMap() { return playerData; }

    public PlayerShopData getPlayerData(UUID uuid) {
        return playerData.get(uuid.toString());
    }
}
