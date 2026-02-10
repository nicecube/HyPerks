package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.config.CosmeticCatalog;
import ca.nicecube.hyperks.config.CosmeticDefinition;
import ca.nicecube.hyperks.config.HyPerksConfig;
import ca.nicecube.hyperks.model.CosmeticCategory;
import ca.nicecube.hyperks.model.PlayerState;
import ca.nicecube.hyperks.ui.HyPerksMenuPage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class HyPerksCoreService {
    private static final String PARTICLE_EXTENSION = ".particlesystem";
    private static final String UNRESOLVED_EFFECT_ID = "";
    private static final String DEFAULT_DEBUG_MODEL = "Server/Models/HyPerksVFX/FireIceCone_Rig.json";

    private static final Set<CosmeticCategory> MULTI_ACTIVE_CATEGORIES = Set.of(
        CosmeticCategory.FLOATING_BADGES,
        CosmeticCategory.TROPHY_BADGES
    );

    private static final long FOOTPRINT_MIN_INTERVAL_MS = 120L;
    private static final double FOOTPRINT_MIN_MOVE_SQUARED = 0.0015D;
    private static final long TRACKER_RETENTION_MS = 300_000L;
    private static final long COMMAND_TRACKER_RETENTION_MS = 600_000L;
    private static final long PERMISSION_CACHE_RETENTION_MS = 180_000L;
    private static final Map<CosmeticCategory, Map<String, Integer>> COSMETIC_ORDER = createCosmeticOrder();

    private final HytaleLogger logger;
    private final HyPerksPaths paths;
    private final LocalizationService localizationService;
    private final JsonConfigStore configStore;
    private final PlayerStateService playerStateService;
    private final ModelVfxRigService modelVfxRigService;

    private volatile HyPerksConfig config = HyPerksConfig.defaults();
    private volatile CosmeticCatalog catalog = CosmeticCatalog.defaults();
    private volatile Map<CosmeticCategory, Map<String, CosmeticDefinition>> byCategory = emptyLookup();

    private final Map<UUID, RenderTracker> renderTrackers = new ConcurrentHashMap<>();
    private final Map<String, String> resolvedEffectIds = new ConcurrentHashMap<>();
    private final Set<String> missingEffectWarnings = ConcurrentHashMap.newKeySet();
    private final Set<String> failedSpawnWarnings = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> commandUsageTracker = new ConcurrentHashMap<>();
    private final Map<PermissionCacheKey, PermissionCacheValue> permissionCache = new ConcurrentHashMap<>();
    private final AtomicLong renderFrame = new AtomicLong(0L);
    private final AtomicLong modelRenderFrame = new AtomicLong(0L);

    private volatile boolean runtimeManaged = false;
    private ScheduledFuture<?> runtimeTask;
    private ScheduledFuture<?> modelRuntimeTask;

    public HyPerksCoreService(
        HytaleLogger logger,
        HyPerksPaths paths,
        LocalizationService localizationService,
        JsonConfigStore configStore,
        PlayerStateService playerStateService
    ) {
        this.logger = logger;
        this.paths = paths;
        this.localizationService = localizationService;
        this.configStore = configStore;
        this.playerStateService = playerStateService;
        this.modelVfxRigService = new ModelVfxRigService(logger);
    }

    public synchronized void reload() {
        this.localizationService.loadOrCreateDefaults();
        this.config = this.configStore.loadOrCreate(
            this.paths.getConfigPath(),
            HyPerksConfig.class,
            HyPerksConfig::defaults,
            HyPerksConfig::normalize
        );
        this.playerStateService.configure(this.config.getPersistence());
        this.catalog = this.configStore.loadOrCreate(
            this.paths.getCosmeticsPath(),
            CosmeticCatalog.class,
            CosmeticCatalog::defaults,
            CosmeticCatalog::normalize
        );

        rebuildLookup();
        this.resolvedEffectIds.clear();
        this.missingEffectWarnings.clear();
        this.failedSpawnWarnings.clear();
        this.commandUsageTracker.clear();
        this.permissionCache.clear();
        this.modelVfxRigService.clearAllRigs();
        this.modelVfxRigService.clearCaches();
        this.modelVfxRigService.configure(
            this.config.getModelVfx().getMaxRigsPerPlayer(),
            this.config.getModelVfx().getLodUltraMaxWorldPlayers()
        );

        this.logger.atInfo().log(
            "[HyPerks] Reloaded: cosmetics=%s, categories=%s, runtime=%s (%sms), modelRuntime=%sms, modelLodRadius=%s, cooldown=%sms, permCacheTtl=%sms, modelRigBudget=%s, lodUltraMaxPlayers=%s, worlds=%s",
            this.catalog.getCosmetics().size(),
            this.byCategory.size(),
            this.config.isRuntimeRenderingEnabled(),
            this.config.getRuntimeRenderIntervalMs(),
            this.config.getModelVfx().getUpdateIntervalMs(),
            this.config.getModelVfx().getLodNearbyRadius(),
            this.config.getCommandCooldownMs(),
            this.config.getPermissionCacheTtlMs(),
            this.modelVfxRigService.getRigBudgetPerPlayer(),
            this.modelVfxRigService.getLodUltraMaxPlayers(),
            this.config.isAllowInAllWorlds() ? "*" : this.config.getWorldWhitelist()
        );
        this.logger.atInfo().log("[HyPerks] Persistence: %s", this.playerStateService.getStoreDescription());

        if (this.runtimeManaged) {
            restartRuntimeRenderer();
        }
    }

    public void flush() {
        this.playerStateService.flush();
    }

    public void close() {
        this.playerStateService.close();
    }

    public synchronized void startRuntime() {
        this.runtimeManaged = true;
        restartRuntimeRenderer();
    }

    public synchronized void stopRuntime() {
        this.runtimeManaged = false;
        stopRuntimeRendererInternal();
    }

    public boolean isRuntimeRunning() {
        ScheduledFuture<?> task = this.runtimeTask;
        return task != null && !task.isCancelled() && !task.isDone();
    }

    public boolean isModelRuntimeRunning() {
        ScheduledFuture<?> task = this.modelRuntimeTask;
        return task != null && !task.isCancelled() && !task.isDone();
    }

    public void showMenu(CommandContext context) {
        if (this.config.getMenu().isGuiEnabled() && context.isPlayer()) {
            Player player = context.senderAs(Player.class);
            if (openGuiMenu(player)) {
                send(context, "cmd.menu.gui_opened");
                return;
            }

            send(context, "cmd.menu.gui_failed");
            if (!this.config.getMenu().isGuiFallbackToChat()) {
                return;
            }
        }

        sendChatMenu(context);
    }

    private void sendChatMenu(CommandContext context) {
        send(context, "cmd.menu.title");
        send(context, "cmd.menu.chat_mode");
        send(context, "cmd.menu.line", "menu");
        send(context, "cmd.menu.line", "list [category]");
        send(context, "cmd.menu.line", "equip <category> <cosmeticId>");
        send(context, "cmd.menu.line", "unequip <category>");
        send(context, "cmd.menu.line", "clear");
        send(context, "cmd.menu.line", "active");
        send(context, "cmd.menu.line", "lang <en|fr>");
        send(context, "cmd.menu.line", "refreshperms");
        send(context, "cmd.menu.line", "debugmodel [modelAssetId]");
        send(context, "cmd.menu.line", "debugmodels [search]");
        send(context, "cmd.menu.line", "modelvfx <show|budget|lodultra|radius|interval|audit|density> [value]");
        send(context, "cmd.menu.line", "status");
        send(context, "cmd.menu.line", "reload");
        send(context, "cmd.menu.example");

        for (CosmeticCategory category : CosmeticCategory.values()) {
            int loaded = this.byCategory.getOrDefault(category, Map.of()).size();
            send(context, "cmd.menu.category_line", category.getId(), loaded);
        }
        send(context, "cmd.menu.categories", categorySummary());
    }

    private boolean openGuiMenu(Player player) {
        if (player == null || player.wasRemoved()) {
            return false;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return false;
        }

        Ref<EntityStore> playerReference = player.getReference();
        if (playerReference == null) {
            return false;
        }

        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                Player livePlayer = store.getComponent(playerReference, Player.getComponentType());
                PlayerRef livePlayerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
                if (livePlayer == null || livePlayer.wasRemoved()) {
                    return;
                }
                if (livePlayerRef == null) {
                    return;
                }

                livePlayer
                    .getPageManager()
                    .openCustomPage(
                        playerReference,
                        store,
                        new HyPerksMenuPage(livePlayerRef, this)
                    );
            } catch (Exception ex) {
                this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to open GUI menu.");
            }
        });

        return true;
    }

    public void showStatus(CommandContext context) {
        send(context, "cmd.status.title");
        send(context, "cmd.status.runtime_enabled", this.config.isRuntimeRenderingEnabled());
        send(context, "cmd.status.runtime_interval", this.config.getRuntimeRenderIntervalMs());
        send(context, "cmd.status.runtime_active", this.isRuntimeRunning());
        send(context, "cmd.status.command_cooldown", this.config.getCommandCooldownMs());
        send(context, "cmd.status.permission_cache_ttl", this.config.getPermissionCacheTtlMs());
        send(context, "cmd.status.permission_cache_entries", this.permissionCache.size());
        send(context, "cmd.status.player_cache_entries", this.playerStateService.getCachedProfileCount());
        send(context, "cmd.status.cosmetics_loaded", this.catalog.getCosmetics().size());
        send(context, "cmd.status.model_rig_players", this.modelVfxRigService.getActiveRigPlayerCount());
        send(context, "cmd.status.model_rigs", this.modelVfxRigService.getActiveRigCount());
        send(context, "cmd.status.model_rig_budget", this.modelVfxRigService.getRigBudgetPerPlayer());
        send(context, "cmd.status.model_lod_ultra_max_players", this.modelVfxRigService.getLodUltraMaxPlayers());
        send(context, "cmd.status.model_lod_radius", this.config.getModelVfx().getLodNearbyRadius());
        send(context, "cmd.status.model_update_interval", this.config.getModelVfx().getUpdateIntervalMs());
        send(context, "cmd.status.model_runtime_active", this.isModelRuntimeRunning());
        send(context, "cmd.status.persistence", this.playerStateService.getStoreDescription());
        send(
            context,
            "cmd.status.worlds",
            this.config.isAllowInAllWorlds() ? "*" : String.join(", ", new LinkedHashSet<>(this.config.getWorldWhitelist()))
        );

        if (context.isPlayer()) {
            Player player = context.senderAs(Player.class);
            String worldName = player.getWorld() == null ? "unknown" : player.getWorld().getName();
            send(context, "cmd.status.current_world_allowed", worldName, this.config.isWorldAllowed(worldName));
        }
    }

    public void listCosmetics(CommandContext context, String categoryId) {
        CosmeticCategory category = categoryId == null ? null : CosmeticCategory.fromId(categoryId);
        if (categoryId != null && category == null) {
            send(context, "error.category_not_found", categoryId);
            return;
        }

        List<CosmeticDefinition> result = new ArrayList<>();
        if (category == null) {
            for (Map<String, CosmeticDefinition> map : this.byCategory.values()) {
                result.addAll(map.values());
            }
        } else {
            result.addAll(this.byCategory.getOrDefault(category, Map.of()).values());
        }

        result.sort(Comparator.comparing(CosmeticDefinition::getCategory).thenComparing(CosmeticDefinition::getId));
        send(context, "cmd.list.title", category == null ? "all" : category.getId(), result.size());

        if (result.isEmpty()) {
            send(context, "cmd.list.empty");
            return;
        }

        CommandSender sender = context.sender();
        for (CosmeticDefinition cosmetic : result) {
            String lockText = hasCosmeticPermission(sender, cosmetic)
                ? tr(sender, "status.unlocked")
                : tr(sender, "status.locked");
            String cosmeticName = tr(sender, cosmetic.getNameKey());
            sendRaw(
                context,
                String.format(
                    Locale.ROOT,
                    "- [%s] %s (%s/%s) -> %s | backend=%s | style=%s | model=%s | profile=%s | fx=%s",
                    lockText,
                    cosmeticName,
                    cosmetic.getCategory(),
                    cosmetic.getId(),
                    cosmetic.getPermission(),
                    cosmetic.getRenderBackend(),
                    cosmetic.getRenderStyle(),
                    cosmetic.getModelAssetId(),
                    cosmetic.getRigProfile(),
                    cosmetic.getEffectId()
                )
            );

            if (this.config.isDetailedCosmeticDescriptions()) {
                String descriptionKey = cosmetic.getNameKey().replace(".name", ".desc");
                String description = tr(sender, descriptionKey);
                if (!descriptionKey.equals(description)) {
                    sendRaw(context, "  > " + description);
                }
            }
        }
    }

    public void equip(CommandContext context, String categoryId, String cosmeticId) {
        if (!context.isPlayer()) {
            send(context, "error.player_only");
            return;
        }

        CosmeticCategory category = CosmeticCategory.fromId(categoryId);
        if (category == null) {
            send(context, "error.category_not_found", categoryId);
            return;
        }

        CosmeticDefinition cosmetic = this.byCategory
            .getOrDefault(category, Map.of())
            .get(normalizeId(cosmeticId));

        if (cosmetic == null || !cosmetic.isEnabled()) {
            send(context, "error.cosmetic_not_found", category.getId(), cosmeticId);
            return;
        }

        Player player = context.senderAs(Player.class);
        if (!isWorldAllowed(player)) {
            send(context, "error.world_not_allowed");
            return;
        }

        if (!hasCosmeticPermission(player, cosmetic)) {
            send(context, "error.no_permission");
            sendRaw(context, cosmetic.getPermission());
            return;
        }

        UUID playerUuid = context.sender().getUuid();
        PlayerState state = this.playerStateService.get(playerUuid);
        if (supportsMultiActive(category)) {
            state.addActive(category.getId(), cosmetic.getId());
        } else {
            this.modelVfxRigService.clearCategoryRigs(playerUuid, category.getId());
            state.setActive(category.getId(), cosmetic.getId());
        }
        this.playerStateService.save(playerUuid);
        send(context, "cmd.equip.success", tr(player, cosmetic.getNameKey()));
    }

    public void unequip(CommandContext context, String categoryId) {
        if (!context.isPlayer()) {
            send(context, "error.player_only");
            return;
        }

        CosmeticCategory category = CosmeticCategory.fromId(categoryId);
        if (category == null) {
            send(context, "error.category_not_found", categoryId);
            return;
        }

        UUID playerUuid = context.sender().getUuid();
        PlayerState state = this.playerStateService.get(playerUuid);
        state.removeActive(category.getId());
        this.playerStateService.save(playerUuid);
        this.modelVfxRigService.clearCategoryRigs(playerUuid, category.getId());
        send(context, "cmd.unequip.success", category.getId());
    }

    public void clearAll(CommandContext context) {
        if (!context.isPlayer()) {
            send(context, "error.player_only");
            return;
        }

        UUID playerUuid = context.sender().getUuid();
        PlayerState state = this.playerStateService.get(playerUuid);
        clearAllActiveCosmetics(state);
        this.playerStateService.save(playerUuid);
        this.modelVfxRigService.clearPlayerRigs(playerUuid);
        send(context, "cmd.clearall.success");
    }

    public void clearAllFromMenu(Player player) {
        if (player == null || player.wasRemoved()) {
            return;
        }

        UUID playerUuid = resolvePlayerUuid(player);
        if (playerUuid == null) {
            return;
        }

        PlayerState state = this.playerStateService.get(playerUuid);
        clearAllActiveCosmetics(state);
        this.playerStateService.save(playerUuid);
        this.modelVfxRigService.clearPlayerRigs(playerUuid);
        player.sendMessage(Message.raw(tr(player, "cmd.clearall.success")));
    }

    public void showActive(CommandContext context) {
        if (!context.isPlayer()) {
            send(context, "error.player_only");
            return;
        }

        Player player = context.senderAs(Player.class);
        PlayerState state = this.playerStateService.get(context.sender().getUuid());
        Map<String, List<String>> active = new LinkedHashMap<>(state.getAllActiveMulti());

        send(context, "cmd.active.title");
        if (active.isEmpty()) {
            send(context, "cmd.active.none");
            return;
        }

        for (CosmeticCategory category : CosmeticCategory.values()) {
            List<String> selected = active.get(category.getId());
            if (selected == null || selected.isEmpty()) {
                continue;
            }

            List<String> labels = new ArrayList<>();
            for (String selectedId : selected) {
                CosmeticDefinition cosmetic = this.byCategory.getOrDefault(category, Map.of()).get(selectedId);
                labels.add(cosmetic == null ? selectedId : tr(player, cosmetic.getNameKey()));
            }
            sendRaw(context, "- " + category.getId() + ": " + String.join(", ", labels));
        }
    }

    public List<MenuEntry> getMenuEntries(Player player, String searchQuery) {
        return getMenuEntries(player, searchQuery, null);
    }

    public List<MenuEntry> getMenuEntries(Player player, String searchQuery, String categoryFilterId) {
        if (player == null) {
            return List.of();
        }

        CosmeticCategory categoryFilter = null;
        if (categoryFilterId != null && !categoryFilterId.isBlank()) {
            categoryFilter = CosmeticCategory.fromId(categoryFilterId);
        }

        String normalizedSearch = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        UUID playerUuid = resolvePlayerUuid(player);
        PlayerState playerState = playerUuid == null ? null : this.playerStateService.get(playerUuid);

        List<MenuEntry> entries = new ArrayList<>();
        for (CosmeticCategory category : CosmeticCategory.values()) {
            if (categoryFilter != null && category != categoryFilter) {
                continue;
            }
            List<CosmeticDefinition> cosmetics = new ArrayList<>(this.byCategory.getOrDefault(category, Map.of()).values());
            sortCosmeticsForCategory(category, cosmetics);

            for (CosmeticDefinition cosmetic : cosmetics) {
                String localizedName = tr(player, cosmetic.getNameKey());
                boolean unlocked = hasCosmeticPermission(player, cosmetic);
                boolean active = playerState != null
                    && playerState.isActive(category.getId(), cosmetic.getId());

                String searchable = (
                    category.getId() + " " + cosmetic.getId() + " " + localizedName
                ).toLowerCase(Locale.ROOT);
                if (!normalizedSearch.isBlank() && !searchable.contains(normalizedSearch)) {
                    continue;
                }

                String statusLabel = unlocked ? tr(player, "status.unlocked") : tr(player, "status.locked");
                String detailLine = category.getId() + " / " + cosmetic.getId() + " / " + statusLabel;
                String displayName = active ? "[*] " + localizedName : localizedName;

                entries.add(new MenuEntry(category.getId(), cosmetic.getId(), displayName, detailLine, active, unlocked));
            }
        }

        return entries;
    }

    public void equipFromMenu(Player player, String categoryId, String cosmeticId) {
        setFromMenu(player, categoryId, cosmeticId, false);
    }

    public void toggleFromMenu(Player player, String categoryId, String cosmeticId) {
        setFromMenu(player, categoryId, cosmeticId, true);
    }

    public String getCategoryDisplayName(Player player, String categoryId) {
        CosmeticCategory category = CosmeticCategory.fromId(categoryId);
        if (category == null) {
            return categoryId == null ? "" : categoryId;
        }
        return tr(player, "menu.category." + category.getId());
    }

    public String getActiveCosmeticDisplayName(Player player, String categoryId) {
        if (player == null) {
            return "";
        }

        CosmeticCategory category = CosmeticCategory.fromId(categoryId);
        if (category == null) {
            return tr(player, "menu.active.none");
        }

        UUID playerUuid = resolvePlayerUuid(player);
        if (playerUuid == null) {
            return tr(player, "menu.active.none");
        }

        PlayerState state = this.playerStateService.get(playerUuid);
        List<String> activeIds = state.getActiveList(category.getId());
        if (activeIds.isEmpty()) {
            return tr(player, "menu.active.none");
        }

        List<String> labels = new ArrayList<>();
        for (String activeId : activeIds) {
            CosmeticDefinition cosmetic = this.byCategory.getOrDefault(category, Map.of()).get(activeId);
            labels.add(cosmetic == null ? activeId : tr(player, cosmetic.getNameKey()));
        }
        return String.join(", ", labels);
    }

    private void setFromMenu(Player player, String categoryId, String cosmeticId, boolean toggleMode) {
        if (player == null || player.wasRemoved()) {
            return;
        }

        CosmeticCategory category = CosmeticCategory.fromId(categoryId);
        if (category == null) {
            player.sendMessage(Message.raw(tr(player, "error.category_not_found", categoryId)));
            return;
        }

        CosmeticDefinition cosmetic = this.byCategory
            .getOrDefault(category, Map.of())
            .get(normalizeId(cosmeticId));

        if (cosmetic == null || !cosmetic.isEnabled()) {
            player.sendMessage(Message.raw(tr(player, "error.cosmetic_not_found", category.getId(), cosmeticId)));
            return;
        }

        if (!isWorldAllowed(player)) {
            player.sendMessage(Message.raw(tr(player, "error.world_not_allowed")));
            return;
        }

        if (!hasCosmeticPermission(player, cosmetic)) {
            player.sendMessage(Message.raw(tr(player, "error.no_permission")));
            return;
        }

        UUID playerUuid = resolvePlayerUuid(player);
        if (playerUuid == null) {
            return;
        }

        PlayerState state = this.playerStateService.get(playerUuid);
        boolean currentlyActive = state.isActive(category.getId(), cosmetic.getId());
        if (toggleMode && currentlyActive) {
            if (supportsMultiActive(category)) {
                state.removeActive(category.getId(), cosmetic.getId());
            } else {
                state.removeActive(category.getId());
            }
            this.playerStateService.save(playerUuid);
            this.modelVfxRigService.clearCategoryRigs(playerUuid, category.getId());
            player.sendMessage(Message.raw(tr(player, "cmd.menu.toggled_off", tr(player, cosmetic.getNameKey()))));
            return;
        }

        if (supportsMultiActive(category)) {
            state.addActive(category.getId(), cosmetic.getId());
        } else {
            this.modelVfxRigService.clearCategoryRigs(playerUuid, category.getId());
            state.setActive(category.getId(), cosmetic.getId());
        }
        this.playerStateService.save(playerUuid);
        player.sendMessage(Message.raw(tr(player, "cmd.equip.success", tr(player, cosmetic.getNameKey()))));
    }

    public void setPlayerLanguage(CommandContext context, String localeCandidate) {
        if (!context.isPlayer()) {
            send(context, "error.player_only");
            return;
        }

        String locale = localeCandidate == null ? "" : localeCandidate.trim().toLowerCase(Locale.ROOT);
        if (!locale.equals("en") && !locale.equals("fr")) {
            send(context, "error.locale_unsupported", localeCandidate);
            return;
        }

        UUID playerUuid = context.sender().getUuid();
        PlayerState state = this.playerStateService.get(playerUuid);
        state.setLocale(locale);
        this.playerStateService.save(playerUuid);
        send(context, "cmd.lang.success", locale);
    }

    public boolean checkCommandCooldown(CommandContext context) {
        if (!context.isPlayer()) {
            return true;
        }

        int cooldownMs = this.config.getCommandCooldownMs();
        if (cooldownMs <= 0) {
            return true;
        }

        CommandSender sender = context.sender();
        if (sender.hasPermission("hyperks.admin.cooldown.bypass")) {
            return true;
        }

        UUID playerUuid = sender.getUuid();
        if (playerUuid == null) {
            return true;
        }

        long now = System.currentTimeMillis();
        long[] remaining = new long[1];

        this.commandUsageTracker.compute(playerUuid, (ignored, lastAt) -> {
            if (lastAt == null) {
                return now;
            }

            long elapsed = now - lastAt;
            if (elapsed >= cooldownMs) {
                return now;
            }

            remaining[0] = cooldownMs - elapsed;
            return lastAt;
        });

        if (remaining[0] <= 0L) {
            return true;
        }

        double seconds = remaining[0] / 1000.0D;
        send(context, "error.cooldown", String.format(Locale.ROOT, "%.1f", seconds));
        return false;
    }

    public void refreshPermissionCache(CommandContext context) {
        if (!context.isPlayer()) {
            if (!context.sender().hasPermission("hyperks.admin.permission.refresh")) {
                send(context, "error.no_permission");
                return;
            }
            this.permissionCache.clear();
            send(context, "cmd.permission_refresh.global");
            return;
        }

        UUID playerUuid = context.sender().getUuid();
        if (playerUuid != null) {
            invalidatePermissionCache(playerUuid);
        }
        send(context, "cmd.permission_refresh.self");
    }

    public void debugModel(CommandContext context, String modelAssetIdCandidate) {
        if (!context.isPlayer()) {
            send(context, "error.player_only");
            return;
        }

        if (!context.sender().hasPermission("hyperks.admin.debugmodel")) {
            send(context, "error.no_permission");
            return;
        }

        Player player = context.senderAs(Player.class);
        if (player == null || player.wasRemoved()) {
            send(context, "cmd.debugmodel.unavailable");
            return;
        }

        World world = player.getWorld();
        Ref<EntityStore> playerRef = player.getReference();
        if (world == null || !world.isAlive() || playerRef == null) {
            send(context, "cmd.debugmodel.unavailable");
            return;
        }

        String requestedModelAssetId = normalizeEffectId(
            modelAssetIdCandidate == null || modelAssetIdCandidate.isBlank()
                ? DEFAULT_DEBUG_MODEL
                : modelAssetIdCandidate
        );

        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                player.sendMessage(Message.raw(tr(player, "cmd.debugmodel.unavailable")));
                return;
            }

            ModelVfxRigService.DebugSpawnResult result = this.modelVfxRigService.spawnDebugRig(
                world,
                store,
                new Vector3d(transform.getPosition()),
                transform.getRotation() == null ? new Vector3f(0F, 0F, 0F) : new Vector3f(transform.getRotation()),
                requestedModelAssetId
            );

            if (result.isSuccess()) {
                player.sendMessage(Message.raw(tr(player, "cmd.debugmodel.spawned", result.getResolvedModelAssetId())));
            } else {
                player.sendMessage(Message.raw(tr(player, "cmd.debugmodel.failed", requestedModelAssetId)));
            }
        });
    }

    public void debugModels(CommandContext context, String searchQuery) {
        if (!context.sender().hasPermission("hyperks.admin.debugmodel")) {
            send(context, "error.no_permission");
            return;
        }

        String filter = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>(ModelAsset.getAssetMap().getAssetMap().keySet());
        ids.sort(String::compareTo);

        int shown = 0;
        int total = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }

            String normalized = normalizeEffectId(id);
            if (!filter.isBlank() && !normalized.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }

            total++;
            if (shown < 40) {
                sendRaw(context, "- " + normalized);
                shown++;
            }
        }

        send(context, "cmd.debugmodels.result", total, shown, filter.isBlank() ? "*" : filter);
    }

    public void modelVfx(CommandContext context, String option, String value) {
        if (!context.sender().hasPermission("hyperks.admin.modelvfx") && !canReload(context.sender())) {
            send(context, "error.no_permission");
            return;
        }

        HyPerksConfig.ModelVfxConfig modelVfxConfig = this.config.getModelVfx();
        if (option == null || option.isBlank() || "show".equalsIgnoreCase(option) || "status".equalsIgnoreCase(option)) {
            send(
                context,
                "cmd.modelvfx.current",
                modelVfxConfig.getMaxRigsPerPlayer(),
                modelVfxConfig.getLodUltraMaxWorldPlayers(),
                modelVfxConfig.getLodNearbyRadius(),
                modelVfxConfig.getUpdateIntervalMs()
            );
            send(context, "cmd.modelvfx.usage");
            return;
        }

        String normalizedOption = option.trim().toLowerCase(Locale.ROOT);
        if ("audit".equals(normalizedOption) || "check".equals(normalizedOption) || "validate".equals(normalizedOption)) {
            auditModelVfx(context);
            return;
        }
        if ("density".equals(normalizedOption) || "nearby".equals(normalizedOption)) {
            modelVfxDensity(context);
            return;
        }

        if (value == null || value.isBlank()) {
            send(context, "cmd.modelvfx.usage");
            return;
        }

        int parsedValue;
        try {
            parsedValue = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            send(context, "cmd.modelvfx.invalid", value);
            send(context, "cmd.modelvfx.usage");
            return;
        }

        boolean updated = false;
        String updatedKey = "";
        if ("budget".equals(normalizedOption) || "maxrigs".equals(normalizedOption) || "max_rigs".equals(normalizedOption)) {
            modelVfxConfig.setMaxRigsPerPlayer(parsedValue);
            updated = true;
            updatedKey = "maxRigsPerPlayer";
        } else if ("lodultra".equals(normalizedOption) || "lod_ultra".equals(normalizedOption) || "lod".equals(normalizedOption)) {
            modelVfxConfig.setLodUltraMaxWorldPlayers(parsedValue);
            updated = true;
            updatedKey = "lodUltraMaxWorldPlayers";
        } else if ("radius".equals(normalizedOption) || "lodradius".equals(normalizedOption) || "lod_radius".equals(normalizedOption)) {
            modelVfxConfig.setLodNearbyRadius(parsedValue);
            updated = true;
            updatedKey = "lodNearbyRadius";
        } else if ("interval".equals(normalizedOption) || "tick".equals(normalizedOption) || "updateinterval".equals(normalizedOption)) {
            modelVfxConfig.setUpdateIntervalMs(parsedValue);
            updated = true;
            updatedKey = "updateIntervalMs";
        }

        if (!updated) {
            send(context, "cmd.modelvfx.usage");
            return;
        }

        modelVfxConfig.normalize();
        this.modelVfxRigService.configure(
            modelVfxConfig.getMaxRigsPerPlayer(),
            modelVfxConfig.getLodUltraMaxWorldPlayers()
        );
        this.configStore.save(this.paths.getConfigPath(), this.config);
        if (this.runtimeManaged) {
            restartRuntimeRenderer();
        }

        if ("maxRigsPerPlayer".equals(updatedKey)) {
            send(context, "cmd.modelvfx.updated", updatedKey, modelVfxConfig.getMaxRigsPerPlayer());
        } else if ("lodUltraMaxWorldPlayers".equals(updatedKey)) {
            send(context, "cmd.modelvfx.updated", updatedKey, modelVfxConfig.getLodUltraMaxWorldPlayers());
        } else if ("lodNearbyRadius".equals(updatedKey)) {
            send(context, "cmd.modelvfx.updated", updatedKey, modelVfxConfig.getLodNearbyRadius());
        } else {
            send(context, "cmd.modelvfx.updated", updatedKey, modelVfxConfig.getUpdateIntervalMs());
        }
        send(
            context,
            "cmd.modelvfx.current",
            modelVfxConfig.getMaxRigsPerPlayer(),
            modelVfxConfig.getLodUltraMaxWorldPlayers(),
            modelVfxConfig.getLodNearbyRadius(),
            modelVfxConfig.getUpdateIntervalMs()
        );
    }

    private void auditModelVfx(CommandContext context) {
        List<CosmeticDefinition> cosmetics = this.catalog.getCosmetics();
        if (cosmetics == null || cosmetics.isEmpty()) {
            send(context, "cmd.modelvfx.audit.empty");
            return;
        }

        int modelCosmetics = 0;
        int partChecks = 0;
        int resolved = 0;
        int missing = 0;
        int shown = 0;
        int maxLines = 80;
        send(context, "cmd.modelvfx.audit.header");

        for (CosmeticDefinition cosmetic : cosmetics) {
            if (cosmetic == null || !cosmetic.isEnabled() || !cosmetic.isModel3dBackend()) {
                continue;
            }

            modelCosmetics++;
            for (boolean ultraTier : new boolean[] { false, true }) {
                String tierLabel = ultraTier ? "ULTRA" : "BALANCED";
                List<ModelVfxRigService.AuditPart> parts = this.modelVfxRigService.describeAuditParts(
                    cosmetic.getCategory(),
                    cosmetic.getId(),
                    cosmetic.getModelAssetId(),
                    cosmetic.getRigProfile(),
                    ultraTier
                );

                if (parts.isEmpty()) {
                    partChecks++;
                    missing++;
                    if (shown < maxLines) {
                        sendRaw(
                            context,
                            "- " + cosmetic.getCategory()
                                + "/" + cosmetic.getId()
                                + " [" + tierLabel + ":main] "
                                + normalizeEffectId(cosmetic.getModelAssetId())
                                + " -> MISSING"
                        );
                        shown++;
                    }
                    continue;
                }

                for (ModelVfxRigService.AuditPart part : parts) {
                    partChecks++;
                    if (part.isResolvable()) {
                        resolved++;
                    } else {
                        missing++;
                    }

                    if (shown < maxLines) {
                        String resolvedValue = part.isResolvable() ? normalizeEffectId(part.getResolvedModelAssetId()) : "MISSING";
                        sendRaw(
                            context,
                            "- " + cosmetic.getCategory()
                                + "/" + cosmetic.getId()
                                + " [" + tierLabel + ":" + part.getPartId() + "] "
                                + normalizeEffectId(part.getRequestedModelAssetId())
                                + " -> " + resolvedValue
                        );
                        shown++;
                    }
                }
            }
        }

        if (modelCosmetics <= 0) {
            send(context, "cmd.modelvfx.audit.empty");
            return;
        }

        send(context, "cmd.modelvfx.audit.summary", modelCosmetics, partChecks, resolved, missing, shown);
        if (partChecks > shown) {
            send(context, "cmd.modelvfx.audit.truncated", partChecks - shown);
        }
    }

    private void modelVfxDensity(CommandContext context) {
        if (context == null || !context.isPlayer()) {
            send(context, "cmd.modelvfx.density.player_only");
            return;
        }

        Player player = context.senderAs(Player.class);
        if (player == null || player.wasRemoved() || player.getWorld() == null || !player.getWorld().isAlive() || player.getReference() == null) {
            send(context, "cmd.modelvfx.density.unavailable");
            return;
        }

        World world = player.getWorld();
        Ref<EntityStore> playerReference = player.getReference();
        int radius = this.config.getModelVfx().getLodNearbyRadius();
        double radiusSquared = radius * (double) radius;

        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                TransformComponent sourceTransform = store.getComponent(playerReference, TransformComponent.getComponentType());
                if (sourceTransform == null || sourceTransform.getPosition() == null) {
                    player.sendMessage(Message.raw(tr(player, "cmd.modelvfx.density.unavailable")));
                    return;
                }

                Vector3d sourcePosition = new Vector3d(sourceTransform.getPosition());
                int nearby = 0;
                int total = 0;
                for (PlayerRef candidateRef : world.getPlayerRefs()) {
                    if (candidateRef == null || !candidateRef.isValid() || candidateRef.getReference() == null) {
                        continue;
                    }

                    Player candidate = store.getComponent(candidateRef.getReference(), Player.getComponentType());
                    if (candidate == null || candidate.wasRemoved()) {
                        continue;
                    }

                    TransformComponent candidateTransform = store.getComponent(candidateRef.getReference(), TransformComponent.getComponentType());
                    if (candidateTransform == null || candidateTransform.getPosition() == null) {
                        continue;
                    }

                    total++;
                    Vector3d candidatePosition = candidateTransform.getPosition();
                    double dx = sourcePosition.x - candidatePosition.x;
                    double dy = sourcePosition.y - candidatePosition.y;
                    double dz = sourcePosition.z - candidatePosition.z;
                    if ((dx * dx) + (dy * dy) + (dz * dz) <= radiusSquared) {
                        nearby++;
                    }
                }

                player.sendMessage(Message.raw(tr(player, "cmd.modelvfx.density", nearby, total, radius)));
            } catch (Exception ex) {
                if (this.config.isDebugMode()) {
                    this.logger.atFine().withCause(ex).log("[HyPerks] modelvfx density failed.");
                }
                player.sendMessage(Message.raw(tr(player, "cmd.modelvfx.density.unavailable")));
            }
        });
    }

    public void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        World world = player.getWorld();
        Ref<EntityStore> playerReference = event.getPlayerRef();
        if (world == null || !world.isAlive() || playerReference == null) {
            return;
        }

        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Player livePlayer = store.getComponent(playerReference, Player.getComponentType());
            PlayerRef livePlayerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
            if (livePlayer == null || livePlayer.wasRemoved() || livePlayerRef == null) {
                return;
            }

            UUID playerUuid = livePlayerRef.getUuid();
            if (playerUuid == null) {
                return;
            }

            this.modelVfxRigService.clearPlayerRigs(playerUuid);
            invalidatePermissionCache(playerUuid);
            this.playerStateService.invalidate(playerUuid);
            PlayerState state = this.playerStateService.refresh(playerUuid);

            if (this.config.isAutoShowMenuHintOnJoin()) {
                livePlayer.sendMessage(Message.raw(tr(playerUuid, "join.hint")));
            }

            int activeCount = state.getAllActiveMulti().values().stream().mapToInt(List::size).sum();
            if (activeCount > 0) {
                livePlayer.sendMessage(Message.raw(tr(playerUuid, "join.active_loaded", activeCount)));
            }

            if (this.config.isDebugMode()) {
                this.logger.atInfo().log(
                    "[HyPerks] Player ready: %s (active cosmetics=%s)",
                    playerUuid,
                    activeCount
                );
            }
        });
    }

    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }

        UUID playerUuid = event.getPlayerRef().getUuid();
        if (playerUuid == null) {
            return;
        }

        this.modelVfxRigService.clearPlayerRigs(playerUuid);
        this.renderTrackers.remove(playerUuid);
        this.commandUsageTracker.remove(playerUuid);
        invalidatePermissionCache(playerUuid);
    }

    public void onDrainPlayerFromWorld(DrainPlayerFromWorldEvent event) {
        UUID playerUuid = resolvePlayerUuidFromHolder(event == null ? null : event.getHolder());
        if (playerUuid == null) {
            return;
        }

        this.modelVfxRigService.clearPlayerRigs(playerUuid);
        this.renderTrackers.remove(playerUuid);
    }

    public void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
        UUID playerUuid = resolvePlayerUuidFromHolder(event == null ? null : event.getHolder());
        if (playerUuid == null) {
            return;
        }

        // Reset previous world rig refs; next render tick respawns in target world.
        this.modelVfxRigService.clearPlayerRigs(playerUuid);
    }

    public boolean canReload(CommandSender sender) {
        return sender.hasPermission("hyperks.admin.reload");
    }

    public void send(CommandContext context, String key, Object... args) {
        context.sendMessage(Message.raw(tr(context.sender(), key, args)));
    }

    public void sendRaw(CommandContext context, String message) {
        context.sendMessage(Message.raw(message));
    }

    public String trForPlayer(Player player, String key, Object... args) {
        return tr(player, key, args);
    }

    private synchronized void restartRuntimeRenderer() {
        stopRuntimeRendererInternal();

        if (!this.config.isRuntimeRenderingEnabled()) {
            this.logger.atInfo().log("[HyPerks] Runtime renderer disabled in config.");
            return;
        }

        int intervalMs = this.config.getRuntimeRenderIntervalMs();
        this.runtimeTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
            this::tickRuntimeRendererSafe,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS
        );

        int modelIntervalMs = this.config.getModelVfx().getUpdateIntervalMs();
        this.modelRuntimeTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
            this::tickModelRuntimeRendererSafe,
            modelIntervalMs,
            modelIntervalMs,
            TimeUnit.MILLISECONDS
        );

        this.logger.atInfo().log("[HyPerks] Runtime renderer started: particles=%sms, models=%sms.", intervalMs, modelIntervalMs);
    }

    private synchronized void stopRuntimeRendererInternal() {
        if (this.runtimeTask != null) {
            this.runtimeTask.cancel(false);
            this.runtimeTask = null;
        }
        if (this.modelRuntimeTask != null) {
            this.modelRuntimeTask.cancel(false);
            this.modelRuntimeTask = null;
        }

        this.renderTrackers.clear();
        this.modelVfxRigService.clearAllRigs();
    }

    private void tickRuntimeRendererSafe() {
        try {
            tickRuntimeRenderer();
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Runtime render tick failed.");
        }
    }

    private void tickModelRuntimeRendererSafe() {
        try {
            tickModelRuntimeRenderer();
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Model runtime tick failed.");
        }
    }

    private void tickRuntimeRenderer() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        long frame = this.renderFrame.incrementAndGet();
        if (frame % 40L == 0L) {
            long nowMs = System.currentTimeMillis();
            pruneOldTrackers(nowMs);
            pruneOldCommandTrackers(nowMs);
            prunePermissionCache(nowMs);
            this.modelVfxRigService.pruneStaleRigs(nowMs);
        }

        Collection<World> worlds = new ArrayList<>(universe.getWorlds().values());
        for (World world : worlds) {
            if (world == null || !world.isAlive() || world.getPlayerCount() <= 0) {
                continue;
            }

            if (!this.config.isWorldAllowed(world.getName())) {
                continue;
            }

            world.execute(() -> renderWorld(world, frame));
        }
    }

    private void tickModelRuntimeRenderer() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        long frame = this.modelRenderFrame.incrementAndGet();
        if (frame % 200L == 0L) {
            this.modelVfxRigService.pruneStaleRigs(System.currentTimeMillis());
        }

        Collection<World> worlds = new ArrayList<>(universe.getWorlds().values());
        for (World world : worlds) {
            if (world == null || !world.isAlive() || world.getPlayerCount() <= 0) {
                continue;
            }

            if (!this.config.isWorldAllowed(world.getName())) {
                continue;
            }

            world.execute(() -> renderWorldModels(world, frame));
        }
    }

    private void renderWorld(World world, long frame) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        long nowMs = System.currentTimeMillis();

        for (PlayerRef playerRef : world.getPlayerRefs()) {
            renderPlayer(world, store, playerRef, frame, nowMs);
        }
    }

    private void renderWorldModels(World world, long frame) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        long nowMs = System.currentTimeMillis();

        List<ModelRenderContext> contexts = collectModelRenderContexts(store, world.getPlayerRefs());
        if (contexts.isEmpty()) {
            return;
        }

        double lodRadius = this.config.getModelVfx().getLodNearbyRadius();
        double lodRadiusSquared = lodRadius * lodRadius;
        Map<ModelLodCellKey, List<Integer>> spatialIndex = buildModelLodSpatialIndex(contexts, lodRadius);
        for (int index = 0; index < contexts.size(); index++) {
            ModelRenderContext context = contexts.get(index);
            int nearbyPlayers = resolveNearbyPlayers(contexts, spatialIndex, index, lodRadius, lodRadiusSquared);
            renderPlayerModels(world, store, context, nearbyPlayers, frame, nowMs);
        }
    }

    private List<ModelRenderContext> collectModelRenderContexts(Store<EntityStore> store, Collection<PlayerRef> playerRefs) {
        if (store == null || playerRefs == null || playerRefs.isEmpty()) {
            return List.of();
        }

        List<ModelRenderContext> contexts = new ArrayList<>();
        for (PlayerRef playerRef : playerRefs) {
            if (playerRef == null || !playerRef.isValid() || playerRef.getReference() == null) {
                continue;
            }

            Player player = store.getComponent(playerRef.getReference(), Player.getComponentType());
            if (player == null || player.wasRemoved()) {
                continue;
            }

            UUID playerUuid = playerRef.getUuid();
            if (playerUuid == null) {
                continue;
            }

            TransformComponent transform = store.getComponent(playerRef.getReference(), TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                continue;
            }

            Vector3d position = new Vector3d(transform.getPosition());
            Vector3f rotation = transform.getRotation() == null ? new Vector3f(0F, 0F, 0F) : new Vector3f(transform.getRotation());
            contexts.add(new ModelRenderContext(playerUuid, player, position, rotation));
        }

        return contexts;
    }

    private Map<ModelLodCellKey, List<Integer>> buildModelLodSpatialIndex(List<ModelRenderContext> contexts, double cellSize) {
        if (contexts == null || contexts.isEmpty()) {
            return Map.of();
        }

        double normalizedCellSize = cellSize <= 0.0D ? 1.0D : cellSize;
        Map<ModelLodCellKey, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < contexts.size(); i++) {
            ModelRenderContext context = contexts.get(i);
            if (context == null || context.position == null) {
                continue;
            }

            ModelLodCellKey key = new ModelLodCellKey(
                toModelLodCell(context.position.x, normalizedCellSize),
                toModelLodCell(context.position.y, normalizedCellSize),
                toModelLodCell(context.position.z, normalizedCellSize)
            );
            index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }

        return index;
    }

    private int resolveNearbyPlayers(
        List<ModelRenderContext> contexts,
        Map<ModelLodCellKey, List<Integer>> spatialIndex,
        int sourceIndex,
        double cellSize,
        double radiusSquared
    ) {
        if (contexts == null || contexts.isEmpty() || sourceIndex < 0 || sourceIndex >= contexts.size()) {
            return 0;
        }

        double normalizedCellSize = cellSize <= 0.0D ? 1.0D : cellSize;
        ModelRenderContext source = contexts.get(sourceIndex);
        int sourceCellX = toModelLodCell(source.position.x, normalizedCellSize);
        int sourceCellY = toModelLodCell(source.position.y, normalizedCellSize);
        int sourceCellZ = toModelLodCell(source.position.z, normalizedCellSize);
        int nearby = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ModelLodCellKey cell = new ModelLodCellKey(sourceCellX + dx, sourceCellY + dy, sourceCellZ + dz);
                    List<Integer> candidates = spatialIndex.get(cell);
                    if (candidates == null || candidates.isEmpty()) {
                        continue;
                    }

                    for (Integer candidateIndex : candidates) {
                        if (candidateIndex == null || candidateIndex < 0 || candidateIndex >= contexts.size()) {
                            continue;
                        }

                        ModelRenderContext candidate = contexts.get(candidateIndex);
                        if (candidate == null || candidate.position == null) {
                            continue;
                        }

                        double distX = source.position.x - candidate.position.x;
                        double distY = source.position.y - candidate.position.y;
                        double distZ = source.position.z - candidate.position.z;
                        if ((distX * distX) + (distY * distY) + (distZ * distZ) <= radiusSquared) {
                            nearby++;
                        }
                    }
                }
            }
        }
        return nearby;
    }

    private int toModelLodCell(double value, double cellSize) {
        return (int) Math.floor(value / cellSize);
    }

    private void renderPlayer(World world, Store<EntityStore> store, PlayerRef playerRef, long frame, long nowMs) {
        if (playerRef == null || !playerRef.isValid() || playerRef.getReference() == null) {
            return;
        }

        Player player = store.getComponent(playerRef.getReference(), Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        TransformComponent transform = store.getComponent(playerRef.getReference(), TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return;
        }

        PlayerState state = this.playerStateService.get(playerUuid);
        Vector3d position = new Vector3d(transform.getPosition());
        Vector3f rotation = transform.getRotation() == null ? new Vector3f(0F, 0F, 0F) : new Vector3f(transform.getRotation());
        double yawDegrees = rotation.getYaw();
        RenderTracker tracker = this.renderTrackers.computeIfAbsent(playerUuid, ignored -> new RenderTracker());
        tracker.lastSeenMs = nowMs;

        if (!state.getAllActive().isEmpty()) {
            renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.AURAS);
            renderCategory(
                player,
                state,
                store,
                position,
                yawDegrees,
                tracker,
                nowMs,
                frame,
                CosmeticCategory.AURAS_PREMIUM
            );
            renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.TRAILS);
            renderCategory(
                player,
                state,
                store,
                position,
                yawDegrees,
                tracker,
                nowMs,
                frame,
                CosmeticCategory.FOOTPRINTS
            );
            renderCategory(
                player,
                state,
                store,
                position,
                yawDegrees,
                tracker,
                nowMs,
                frame,
                CosmeticCategory.FLOATING_BADGES
            );
            renderCategory(
                player,
                state,
                store,
                position,
                yawDegrees,
                tracker,
                nowMs,
                frame,
                CosmeticCategory.TROPHY_BADGES
            );
        }
    }

    private void renderPlayerModels(
        World world,
        Store<EntityStore> store,
        ModelRenderContext context,
        int nearbyPlayers,
        long frame,
        long nowMs
    ) {
        if (context == null || context.player == null || context.player.wasRemoved() || context.playerUuid == null) {
            return;
        }

        PlayerState state = this.playerStateService.get(context.playerUuid);
        List<ModelVfxRigService.DesiredRig> desiredModelRigs = collectDesiredModelRigs(context.player, state);
        this.modelVfxRigService.syncPlayerRigs(
            context.playerUuid,
            world,
            store,
            context.position,
            context.rotation,
            desiredModelRigs,
            nearbyPlayers,
            frame,
            nowMs
        );
    }

    private List<ModelVfxRigService.DesiredRig> collectDesiredModelRigs(Player player, PlayerState state) {
        if (player == null || state == null || state.getAllActive().isEmpty()) {
            return List.of();
        }

        List<ModelVfxRigService.DesiredRig> desired = new ArrayList<>();
        collectDesiredModelRigsForCategory(player, state, CosmeticCategory.AURAS, desired);
        collectDesiredModelRigsForCategory(player, state, CosmeticCategory.AURAS_PREMIUM, desired);
        collectDesiredModelRigsForCategory(player, state, CosmeticCategory.TRAILS, desired);
        collectDesiredModelRigsForCategory(player, state, CosmeticCategory.FOOTPRINTS, desired);
        collectDesiredModelRigsForCategory(player, state, CosmeticCategory.FLOATING_BADGES, desired);
        collectDesiredModelRigsForCategory(player, state, CosmeticCategory.TROPHY_BADGES, desired);
        return desired;
    }

    private void collectDesiredModelRigsForCategory(
        Player player,
        PlayerState state,
        CosmeticCategory category,
        List<ModelVfxRigService.DesiredRig> desired
    ) {
        for (CosmeticDefinition cosmetic : resolveActiveCosmetics(state, category)) {
            if (cosmetic == null || !cosmetic.isModel3dBackend() || !hasCosmeticPermission(player, cosmetic)) {
                continue;
            }
            desired.add(new ModelVfxRigService.DesiredRig(category.getId(), cosmetic.getId(), cosmetic.getModelAssetId(), cosmetic.getRigProfile()));
        }
    }

    private void renderCategory(
        Player player,
        PlayerState state,
        Store<EntityStore> store,
        Vector3d position,
        double yawDegrees,
        RenderTracker tracker,
        long nowMs,
        long frame,
        CosmeticCategory category
    ) {
        List<CosmeticDefinition> activeCosmetics = resolveActiveCosmetics(state, category);
        if (activeCosmetics.isEmpty()) {
            return;
        }

        int totalSlots = Math.max(1, activeCosmetics.size());
        int slot = 0;
        for (CosmeticDefinition cosmetic : activeCosmetics) {
            if (cosmetic == null || !hasCosmeticPermission(player, cosmetic)) {
                continue;
            }

            if (cosmetic.isModel3dBackend()) {
                slot++;
                continue;
            }

            String effectId = resolveEffectId(cosmetic.getEffectId());
            if (effectId.isBlank()) {
                continue;
            }

            switch (category) {
                case AURAS -> renderAura(effectId, cosmetic, position, yawDegrees, store, frame);
                case AURAS_PREMIUM -> renderPremiumAura(effectId, cosmetic, position, store, frame);
                case TRAILS -> renderTrail(effectId, cosmetic, position, store, tracker, frame);
                case FOOTPRINTS -> renderFootprints(effectId, position, yawDegrees, store, tracker, nowMs);
                case FLOATING_BADGES -> renderFloatingBadge(effectId, cosmetic, position, store, frame, slot, totalSlots);
                case TROPHY_BADGES -> renderTrophyBadge(effectId, cosmetic, position, store, frame, slot, totalSlots);
            }

            slot++;
        }
    }

    private void renderAura(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        double yawDegrees,
        Store<EntityStore> store,
        long frame
    ) {
        String style = cosmetic.getRenderStyle();
        String cosmeticId = cosmetic.getId();
        if ("angel_wings".equals(cosmeticId)) {
            if ((frame % 2L) != 0L) {
                return;
            }

            double flap = Math.sin(frame * 0.11D) * 0.10D;
            double baseY = position.y + 1.46D;
            double yawRadians = Math.toRadians(yawDegrees);
            double forwardX = Math.cos(yawRadians);
            double forwardZ = Math.sin(yawRadians);
            double rightX = Math.cos(yawRadians + (Math.PI / 2.0D));
            double rightZ = Math.sin(yawRadians + (Math.PI / 2.0D));
            double scale = 2.0D;
            double[][] wingCurve = {
                {0.16D, 0.30D, 0.04D},
                {0.30D, 0.24D, 0.08D},
                {0.44D, 0.16D, 0.12D},
                {0.54D, 0.06D, 0.17D},
                {0.50D, -0.04D, 0.22D},
                {0.34D, -0.12D, 0.24D}
            };

            for (double[] point : wingCurve) {
                double wingY = baseY + (point[1] * scale) + (flap * (0.3D + point[0]));
                double depth = -(0.20D + (point[2] * scale));
                for (int direction = -1; direction <= 1; direction += 2) {
                    double lateral = point[0] * scale * direction;
                    double x = position.x + (rightX * lateral) + (forwardX * depth);
                    double z = position.z + (rightZ * lateral) + (forwardZ * depth);
                    spawnParticle(effectId, x, wingY, z, store);
                }
            }

            if ((frame % 5L) == 0L) {
                for (int direction = -1; direction <= 1; direction += 2) {
                    double lateral = 0.10D * scale * direction;
                    double x = position.x + (rightX * lateral) - (forwardX * 0.26D);
                    double z = position.z + (rightZ * lateral) - (forwardZ * 0.26D);
                    spawnParticle(effectId, x, position.y + 1.56D + (flap * 0.25D), z, store);
                    spawnParticle(effectId, x, position.y + 1.34D + (flap * 0.15D), z, store);
                }
            }
            return;
        }

        if ("ember_halo".equals(cosmeticId)) {
            if ((frame % 3L) != 0L) {
                return;
            }

            double phase = frame * 0.08D;
            double centerY = position.y + 1.48D;
            double equatorRadius = 0.52D;
            double midRadius = 0.40D;

            for (int i = 0; i < 8; i++) {
                double angle = phase + (Math.PI * 2D * i / 8D);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * equatorRadius,
                    centerY,
                    position.z + Math.sin(angle) * equatorRadius,
                    store
                );
            }

            for (int i = 0; i < 6; i++) {
                double angle = -phase + (Math.PI * 2D * i / 6D);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * midRadius,
                    centerY + 0.28D,
                    position.z + Math.sin(angle) * midRadius,
                    store
                );
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle + (Math.PI / 6.0D)) * midRadius,
                    centerY - 0.28D,
                    position.z + Math.sin(angle + (Math.PI / 6.0D)) * midRadius,
                    store
                );
            }

            if ((frame % 9L) == 0L) {
                spawnParticle(effectId, position.x, centerY + 0.48D, position.z, store);
                spawnParticle(effectId, position.x, centerY - 0.48D, position.z, store);
            }
            return;
        }

        if ("void_orbit".equals(cosmeticId)) {
            if ((frame % 2L) != 0L) {
                return;
            }

            double phaseOuter = frame * 0.16D;
            double phaseInner = -frame * 0.11D;
            double yOuter = position.y + 0.14D;

            for (int i = 0; i < 7; i++) {
                double angle = phaseOuter + (Math.PI * 2D * i / 7D);
                double radius = 0.42D + (Math.sin(phaseOuter + i) * 0.06D);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * radius,
                    yOuter + ((i % 2 == 0) ? 0.02D : 0.0D),
                    position.z + Math.sin(angle) * radius,
                    store
                );
            }

            for (int i = 0; i < 3; i++) {
                double angle = phaseInner + (Math.PI * 2D * i / 3D);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * 0.25D,
                    yOuter + 0.04D,
                    position.z + Math.sin(angle) * 0.25D,
                    store
                );
            }
            return;
        }

        if ("heart_bloom".equals(cosmeticId)) {
            if ((frame % 2L) != 0L) {
                return;
            }

            double phase = frame * 0.15D;
            double baseY = position.y + 0.22D;
            double height = 1.95D;
            int points = 9;
            for (int i = 0; i < points; i++) {
                double t = i / (double) (points - 1);
                double angle = phase + (t * Math.PI * 2D * 2.2D);
                double radius = 0.30D + (Math.sin(phase + (t * 4.0D)) * 0.04D);
                double y = baseY + (t * height);
                spawnParticle(effectId, position.x + Math.cos(angle) * radius, y, position.z + Math.sin(angle) * radius, store);
                spawnParticle(effectId, position.x + Math.cos(angle + Math.PI) * radius, y, position.z + Math.sin(angle + Math.PI) * radius, store);
            }
            spawnParticle(effectId, position.x, position.y + 2.20D, position.z, store);
            return;
        }

        if ("fire_ice_cone".equals(cosmeticId) || "cone".equals(style)) {
            if ((frame % 2L) != 0L) {
                return;
            }

            double phase = frame * 0.18D;
            double height = 1.65D;
            int layers = 6;
            for (int i = 0; i < layers; i++) {
                double t = i / (double) (layers - 1);
                double radius = 0.12D + (0.34D * t);
                double localY = 0.28D + (height * t);
                double swirl = phase + (t * 1.6D);
                spawnRotatedParticle(
                    effectId,
                    position,
                    Math.cos(swirl) * radius,
                    localY,
                    (-0.18D) + (Math.sin(swirl) * radius),
                    yawDegrees,
                    store
                );
                spawnRotatedParticle(
                    effectId,
                    position,
                    Math.cos(swirl + Math.PI) * radius,
                    localY,
                    (-0.18D) + (Math.sin(swirl + Math.PI) * radius),
                    yawDegrees,
                    store
                );
            }

            if ((frame % 8L) == 0L) {
                spawnRotatedParticle(effectId, position, 0.0D, 2.03D, -0.20D, yawDegrees, store);
            }
            return;
        }

        if ("storm_clouds".equals(cosmeticId) || "storm".equals(style)) {
            if ((frame % 2L) != 0L) {
                return;
            }

            double phase = frame * 0.13D;
            double cloudY = position.y + 2.24D + (Math.sin(frame * 0.06D) * 0.03D);
            for (int i = 0; i < 6; i++) {
                double angle = phase + (Math.PI * 2D * i / 6D);
                double radius = 0.22D + ((i % 2 == 0) ? 0.05D : 0.02D);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * radius,
                    cloudY + Math.sin(phase + i) * 0.03D,
                    position.z + Math.sin(angle) * radius,
                    store
                );
            }

            for (int i = 0; i < 3; i++) {
                double n = phase + (i * 2.15D);
                double dropX = position.x + Math.sin(n) * 0.22D;
                double dropZ = position.z + Math.cos(n * 1.13D) * 0.22D;
                double dropY = position.y + 1.88D - (((frame + (i * 2L)) % 5L) * 0.15D);
                spawnParticle(effectId, dropX, dropY, dropZ, store);
            }

            // Periodic bolt line under the cloud cap.
            if ((frame % 10L) == 0L) {
                double strikeX = position.x + Math.sin(phase * 1.7D) * 0.09D;
                double strikeZ = position.z + Math.cos(phase * 1.7D) * 0.09D;
                for (int step = 0; step < 4; step++) {
                    spawnParticle(effectId, strikeX, position.y + 2.10D - (step * 0.30D), strikeZ, store);
                }
            }
            return;
        }

        if ("wingwang_sigil".equals(cosmeticId) || "sigil".equals(style)) {
            if ((frame % 2L) != 0L) {
                return;
            }

            double phase = frame * 0.17D;
            for (int i = 0; i < 3; i++) {
                double angle = phase + (Math.PI * 2D * i / 3D);
                spawnRotatedParticle(
                    effectId,
                    position,
                    Math.cos(angle) * 0.30D,
                    1.50D + (Math.sin(phase + i) * 0.05D),
                    -0.18D + (Math.sin(angle) * 0.10D),
                    yawDegrees,
                    store
                );
            }
            spawnRotatedParticle(
                effectId,
                position,
                0.0D,
                1.56D + (Math.sin(phase * 1.5D) * 0.06D),
                -0.22D,
                yawDegrees,
                store
            );
            if ((frame % 6L) == 0L) {
                spawnRotatedParticle(effectId, position, 0.0D, 1.30D, -0.18D, yawDegrees, store);
            }
            return;
        }

        if ("fireworks_show".equals(cosmeticId) || "fireworks".equals(style)) {
            if ((frame % 3L) != 0L) {
                return;
            }

            double phase = frame * 0.11D;
            double launchX = position.x + Math.cos(phase * 0.7D) * 0.18D;
            double launchZ = position.z + Math.sin(phase * 0.7D) * 0.18D;
            spawnParticle(effectId, launchX, position.y + 1.96D, launchZ, store);

            if ((frame % 12L) == 0L) {
                double burstY = position.y + 2.58D + (Math.sin(phase * 0.9D) * 0.10D);
                int points = 8;
                for (int i = 0; i < points; i++) {
                    double angle = phase + (Math.PI * 2D * i / points);
                    double radius = 0.28D + ((i % 2 == 0) ? 0.10D : 0.04D);
                    spawnParticle(
                        effectId,
                        position.x + Math.cos(angle) * radius,
                        burstY + (Math.sin(angle * 2.0D) * 0.10D),
                        position.z + Math.sin(angle) * radius,
                        store
                    );
                }
                spawnParticle(effectId, position.x, burstY + 0.16D, position.z, store);
            }
            return;
        }

        if ("wings".equals(style)) {
            double flap = Math.sin(frame * 0.35D) * 0.18D;
            spawnParticle(effectId, position.x - 0.55D, position.y + 1.55D + flap, position.z - 0.2D, store);
            spawnParticle(effectId, position.x + 0.55D, position.y + 1.55D + flap, position.z - 0.2D, store);
            spawnParticle(effectId, position.x - 0.35D, position.y + 1.2D - flap, position.z - 0.05D, store);
            spawnParticle(effectId, position.x + 0.35D, position.y + 1.2D - flap, position.z - 0.05D, store);
            return;
        }

        if ("hearts".equals(style)) {
            double bob = Math.sin(frame * 0.20D) * 0.12D;
            spawnParticle(effectId, position.x - 0.22D, position.y + 2.0D + bob, position.z, store);
            spawnParticle(effectId, position.x + 0.22D, position.y + 2.0D + bob, position.z, store);
            spawnParticle(effectId, position.x, position.y + 2.2D + bob, position.z, store);
            return;
        }

        double phase = frame * 0.22D;
        double y = position.y + 1.8D;
        double radius = 0.75D;

        for (int i = 0; i < 3; i++) {
            double angle = phase + (Math.PI * 2D * i / 3D);
            double x = position.x + Math.cos(angle) * radius;
            double z = position.z + Math.sin(angle) * radius;
            spawnParticle(effectId, x, y, z, store);
        }
    }

    private void renderPremiumAura(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        long frame
    ) {
        String style = cosmetic.getRenderStyle();
        String cosmeticId = cosmetic.getId();
        if ("crown".equals(style) || "vip_aura".equals(cosmeticId) || "vip_plus_aura".equals(cosmeticId)
            || "mvp_aura".equals(cosmeticId) || "mvp_plus_aura".equals(cosmeticId)) {
            long frameGate = 6L;
            int crownPoints = 4;
            double radius = 0.24D;
            double wobble = 0.012D;
            long centerSpawnEvery = 18L;
            double phaseSpeed = 0.040D;

            if ("vip_plus_aura".equals(cosmeticId)) {
                frameGate = 5L;
                crownPoints = 5;
                radius = 0.26D;
                centerSpawnEvery = 16L;
                phaseSpeed = 0.045D;
            } else if ("mvp_aura".equals(cosmeticId)) {
                frameGate = 4L;
                crownPoints = 6;
                radius = 0.28D;
                centerSpawnEvery = 14L;
                phaseSpeed = 0.050D;
            } else if ("mvp_plus_aura".equals(cosmeticId)) {
                frameGate = 3L;
                crownPoints = 7;
                radius = 0.30D;
                wobble = 0.014D;
                centerSpawnEvery = 12L;
                phaseSpeed = 0.055D;
            }

            if ((frame % frameGate) != 0L) {
                return;
            }

            double phase = frame * phaseSpeed;
            double yBase = position.y + 2.02D;
            for (int i = 0; i < crownPoints; i++) {
                double angle = phase + (Math.PI * 2D * i / crownPoints);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * radius,
                    yBase + Math.sin(phase + i) * wobble,
                    position.z + Math.sin(angle) * radius,
                    store
                );
            }

            if ((frame % centerSpawnEvery) == 0L) {
                spawnParticle(effectId, position.x, yBase + 0.12D, position.z, store);
            }
            return;
        }

        double phase = frame * 0.06D;
        spawnParticle(effectId, position.x + Math.cos(phase) * 0.34D, position.y + 2.0D, position.z + Math.sin(phase) * 0.34D, store);
    }

    private void renderTrail(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        RenderTracker tracker,
        long frame
    ) {
        if (tracker != null && tracker.lastTrailPosition != null) {
            double deltaSquared = tracker.lastTrailPosition.distanceSquaredTo(position);
            if (deltaSquared < 0.0004D) {
                return;
            }
        }

        int steps = 2;
        Vector3d previous = tracker == null ? null : tracker.lastTrailPosition;
        if (previous != null) {
            double distance = Math.sqrt(previous.distanceSquaredTo(position));
            steps = Math.max(2, Math.min(5, (int) Math.ceil(distance / 0.14D)));
        }

        for (int step = 1; step <= steps; step++) {
            double t = step / (double) steps;
            Vector3d sample = previous == null
                ? new Vector3d(position)
                : new Vector3d(
                    lerp(previous.x, position.x, t),
                    lerp(previous.y, position.y, t),
                    lerp(previous.z, position.z, t)
                );
            renderTrailSample(effectId, cosmetic, sample, store, frame + step);
        }

        if (tracker != null) {
            tracker.lastTrailPosition = new Vector3d(position);
        }
    }

    private void renderTrailSample(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        long frame
    ) {
        String style = cosmetic.getRenderStyle();
        String cosmeticId = cosmetic.getId();
        double baseY = position.y + 0.24D;

        if ("comet".equals(style)) {
            double phase = frame * 0.12D;
            double headX = position.x + Math.cos(phase) * 0.16D;
            double headZ = position.z + Math.sin(phase) * 0.16D;
            spawnParticle(effectId, headX, baseY + 0.04D, headZ, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 0.8D) * 0.13D, baseY + 0.03D, position.z + Math.sin(phase + 0.8D) * 0.13D, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 1.4D) * 0.11D, baseY + 0.02D, position.z + Math.sin(phase + 1.4D) * 0.11D, store);
            for (int tail = 1; tail <= 4; tail++) {
                double trailOffset = 0.11D * tail;
                spawnParticle(
                    effectId,
                    position.x - Math.cos(phase) * trailOffset,
                    baseY + 0.04D - (tail * 0.015D),
                    position.z - Math.sin(phase) * trailOffset,
                    store
                );
            }
            return;
        }

        if ("spark".equals(style)) {
            double sway = Math.sin(frame * 0.14D) * 0.12D;
            spawnParticle(effectId, position.x + sway, baseY + 0.03D, position.z, store);
            spawnParticle(effectId, position.x - sway, baseY + 0.04D, position.z, store);
            spawnParticle(effectId, position.x, baseY + 0.05D, position.z + sway, store);
            spawnParticle(effectId, position.x, baseY + 0.03D, position.z - sway, store);
            spawnParticle(effectId, position.x + (sway * 0.6D), baseY + 0.07D, position.z + (sway * 0.4D), store);
            spawnParticle(effectId, position.x - (sway * 0.6D), baseY + 0.02D, position.z - (sway * 0.4D), store);
            return;
        }

        if ("spiral".equals(style)) {
            double phase = frame * 0.19D;
            double radius = 0.20D;
            spawnParticle(effectId, position.x + Math.cos(phase) * radius, baseY, position.z + Math.sin(phase) * radius, store);
            spawnParticle(
                effectId,
                position.x + Math.cos(phase + Math.PI) * radius,
                baseY + 0.05D,
                position.z + Math.sin(phase + Math.PI) * radius,
                store
            );
            spawnParticle(effectId, position.x + Math.cos(phase + (Math.PI / 2.0D)) * (radius * 0.75D), baseY + 0.03D, position.z + Math.sin(phase + (Math.PI / 2.0D)) * (radius * 0.75D), store);
            spawnParticle(effectId, position.x + Math.cos(phase + (Math.PI / 3.0D)) * (radius * 0.82D), baseY + 0.06D, position.z + Math.sin(phase + (Math.PI / 3.0D)) * (radius * 0.82D), store);
            spawnParticle(effectId, position.x + Math.cos(phase + (Math.PI * 1.3D)) * (radius * 0.90D), baseY + 0.01D, position.z + Math.sin(phase + (Math.PI * 1.3D)) * (radius * 0.90D), store);
            return;
        }

        if ("supreme".equals(style)) {
            double phase = frame * 0.21D;
            double radius = 0.24D;
            for (int i = 0; i < 4; i++) {
                double angle = phase + (Math.PI * 2D * i / 4D);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * radius,
                    baseY + (i * 0.03D),
                    position.z + Math.sin(angle) * radius,
                    store
                );
            }
            spawnParticle(effectId, position.x, baseY + 0.08D, position.z, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 1.0D) * 0.16D, baseY + 0.12D, position.z + Math.sin(phase + 1.0D) * 0.16D, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 2.7D) * 0.16D, baseY + 0.10D, position.z + Math.sin(phase + 2.7D) * 0.16D, store);
            return;
        }

        if ("laser".equals(style)) {
            double phase = frame * 0.10D;
            for (int i = 0; i < 7; i++) {
                double offset = 0.03D + (i * 0.014D);
                spawnParticle(
                    effectId,
                    position.x + (Math.cos(phase + i) * offset),
                    baseY + (i * 0.085D),
                    position.z + (Math.sin(phase + i) * offset),
                    store
                );
            }
            spawnParticle(effectId, position.x, baseY + 0.30D, position.z, store);
            return;
        }

        if ("icon".equals(style)) {
            double phase = frame * 0.11D;

            if ("star_trail".equals(cosmeticId) || "money_trail".equals(cosmeticId) || "death_trail".equals(cosmeticId)) {
                spawnParticle(effectId, position.x, baseY + 0.05D, position.z, store);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(phase) * 0.16D,
                    baseY + 0.03D,
                    position.z + Math.sin(phase) * 0.16D,
                    store
                );
                spawnParticle(
                    effectId,
                    position.x + Math.cos(phase + 2.1D) * 0.22D,
                    baseY + 0.08D,
                    position.z + Math.sin(phase + 2.1D) * 0.22D,
                    store
                );
                spawnParticle(
                    effectId,
                    position.x + Math.cos(phase + 4.0D) * 0.18D,
                    baseY + 0.06D,
                    position.z + Math.sin(phase + 4.0D) * 0.18D,
                    store
                );
                spawnParticle(effectId, position.x + Math.cos(phase + 5.0D) * 0.14D, baseY + 0.04D, position.z + Math.sin(phase + 5.0D) * 0.14D, store);
                spawnParticle(effectId, position.x + Math.cos(phase + 1.2D) * 0.12D, baseY + 0.09D, position.z + Math.sin(phase + 1.2D) * 0.12D, store);
                return;
            }

            spawnParticle(effectId, position.x, baseY + 0.05D, position.z, store);
            spawnParticle(effectId, position.x + Math.cos(phase) * 0.14D, baseY + 0.02D, position.z + Math.sin(phase) * 0.14D, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 2.2D) * 0.18D, baseY + 0.06D, position.z + Math.sin(phase + 2.2D) * 0.18D, store);
            spawnParticle(effectId, position.x - Math.cos(phase) * 0.12D, baseY + 0.04D, position.z - Math.sin(phase) * 0.12D, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 4.1D) * 0.10D, baseY + 0.03D, position.z + Math.sin(phase + 4.1D) * 0.10D, store);
            return;
        }

        double phase = frame * 0.14D;
        spawnParticle(effectId, position.x + Math.cos(phase) * 0.1D, baseY, position.z + Math.sin(phase) * 0.1D, store);
        spawnParticle(effectId, position.x + Math.cos(phase + Math.PI) * 0.08D, baseY + 0.02D, position.z + Math.sin(phase + Math.PI) * 0.08D, store);
        spawnParticle(effectId, position.x, baseY + 0.04D, position.z, store);
    }

    private void renderFootprints(
        String effectId,
        Vector3d position,
        double yawDegrees,
        Store<EntityStore> store,
        RenderTracker tracker,
        long nowMs
    ) {
        if (tracker.lastFootstepPosition != null) {
            if (tracker.lastFootstepPosition.distanceSquaredTo(position) < FOOTPRINT_MIN_MOVE_SQUARED) {
                return;
            }
            if ((nowMs - tracker.lastFootstepAtMs) < FOOTPRINT_MIN_INTERVAL_MS) {
                return;
            }
        }

        double side = tracker.nextFootRight ? 0.17D : -0.17D;
        tracker.nextFootRight = !tracker.nextFootRight;

        double yawRadians = Math.toRadians(yawDegrees + 90.0D);
        double offsetX = Math.cos(yawRadians) * side;
        double offsetZ = Math.sin(yawRadians) * side;
        double forwardX = Math.cos(Math.toRadians(yawDegrees)) * 0.10D;
        double forwardZ = Math.sin(Math.toRadians(yawDegrees)) * 0.10D;

        spawnParticle(effectId, position.x + offsetX + forwardX, position.y + 0.08D, position.z + offsetZ + forwardZ, store);
        tracker.lastFootstepPosition = new Vector3d(position);
        tracker.lastFootstepAtMs = nowMs;
    }

    private void renderFloatingBadge(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        long frame,
        int slot,
        int totalSlots
    ) {
        double baseAngle = (Math.PI * 2D * slot / Math.max(1, totalSlots));
        double phase = (frame * 0.020D) + baseAngle;
        double radius = 0.40D + (Math.max(0, Math.min(4, totalSlots - 1)) * 0.05D);
        double y = position.y + 2.18D + (Math.sin((frame * 0.02D) + slot) * 0.01D);
        spawnBadgeCarousel(effectId, position, store, phase, radius, y, 3, 0.20D);
    }

    private void renderTrophyBadge(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        long frame,
        int slot,
        int totalSlots
    ) {
        double baseAngle = (Math.PI * 2D * slot / Math.max(1, totalSlots));
        double phase = (frame * 0.018D) + baseAngle;
        double baseRadius = "crown".equals(cosmetic.getRenderStyle()) ? 0.44D : 0.40D;
        double radius = baseRadius + (Math.max(0, Math.min(4, totalSlots - 1)) * 0.05D);
        double y = position.y + 2.30D + (Math.cos((frame * 0.018D) + slot) * 0.01D);
        spawnBadgeCarousel(effectId, position, store, phase, radius, y, 4, 0.18D);
        spawnParticle(effectId, position.x, y + 0.04D, position.z, store);
    }

    private void spawnBadgeCarousel(
        String effectId,
        Vector3d position,
        Store<EntityStore> store,
        double phase,
        double radius,
        double y,
        int samples,
        double trailPhaseStep
    ) {
        int count = Math.max(1, samples);
        for (int i = 0; i < count; i++) {
            double samplePhase = phase - (trailPhaseStep * i);
            double sampleRadius = radius - (i * 0.015D);
            spawnParticle(
                effectId,
                position.x + Math.cos(samplePhase) * sampleRadius,
                y - (i * 0.005D),
                position.z + Math.sin(samplePhase) * sampleRadius,
                store
            );
        }
    }

    private double lerp(double start, double end, double t) {
        return start + ((end - start) * t);
    }

    private void spawnRotatedParticle(
        String effectId,
        Vector3d origin,
        double localX,
        double localY,
        double localZ,
        double yawDegrees,
        Store<EntityStore> store
    ) {
        double yawRadians = Math.toRadians(-yawDegrees);
        double cos = Math.cos(yawRadians);
        double sin = Math.sin(yawRadians);
        double rotatedX = (localX * cos) - (localZ * sin);
        double rotatedZ = (localX * sin) + (localZ * cos);
        spawnParticle(effectId, origin.x + rotatedX, origin.y + localY, origin.z + rotatedZ, store);
    }

    private void spawnParticle(String effectId, double x, double y, double z, Store<EntityStore> store) {
        try {
            ParticleUtil.spawnParticleEffect(effectId, new Vector3d(x, y, z), store);
        } catch (Exception ex) {
            if (this.failedSpawnWarnings.add(effectId)) {
                this.logger.atWarning().withCause(ex).log(
                    "[HyPerks] Failed to spawn particle '%s'. Check cosmetics.json effect IDs.",
                    effectId
                );
            }
        }
    }

    private CosmeticDefinition resolveActiveCosmetic(PlayerState state, CosmeticCategory category) {
        String selectedId = state.getActive(category.getId());
        if (selectedId == null || selectedId.isBlank()) {
            return null;
        }

        return this.byCategory
            .getOrDefault(category, Map.of())
            .get(normalizeId(selectedId));
    }

    private List<CosmeticDefinition> resolveActiveCosmetics(PlayerState state, CosmeticCategory category) {
        Map<String, CosmeticDefinition> categoryMap = this.byCategory.getOrDefault(category, Map.of());
        if (categoryMap.isEmpty()) {
            return List.of();
        }

        if (!supportsMultiActive(category)) {
            CosmeticDefinition active = resolveActiveCosmetic(state, category);
            return active == null ? List.of() : List.of(active);
        }

        List<String> selectedIds = state.getActiveList(category.getId());
        if (selectedIds.isEmpty()) {
            return List.of();
        }

        List<CosmeticDefinition> result = new ArrayList<>();
        for (String selectedId : selectedIds) {
            CosmeticDefinition cosmetic = categoryMap.get(normalizeId(selectedId));
            if (cosmetic != null) {
                result.add(cosmetic);
            }
        }
        return result;
    }

    private String resolveEffectId(String configuredEffectId) {
        String normalized = normalizeEffectId(configuredEffectId);
        if (normalized.isBlank()) {
            return UNRESOLVED_EFFECT_ID;
        }

        return this.resolvedEffectIds.computeIfAbsent(normalized, this::resolveEffectIdFromAssets);
    }

    private String resolveEffectIdFromAssets(String candidate) {
        try {
            Map<String, ParticleSystem> particleMap = ParticleSystem.getAssetMap().getAssetMap();
            String resolved = findParticleKey(particleMap, candidate);
            if (!resolved.isBlank()) {
                return resolved;
            }

            String normalizedCandidate = normalizeEffectId(candidate);
            String noExtension = stripParticleExtension(normalizedCandidate);
            if (!noExtension.equalsIgnoreCase(normalizedCandidate)) {
                resolved = findParticleKey(particleMap, noExtension);
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }

            String baseName = basenameOfPath(noExtension);
            if (!baseName.isBlank()) {
                resolved = findParticleKey(particleMap, baseName);
                if (!resolved.isBlank()) {
                    return resolved;
                }
                resolved = findParticleKey(particleMap, baseName + PARTICLE_EXTENSION);
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }

            if (this.missingEffectWarnings.add(candidate)) {
                this.logger.atWarning().log(
                    "[HyPerks] Unknown particle system '%s'. Using raw effect id as fallback.",
                    candidate
                );
            }
        } catch (Exception ex) {
            if (this.missingEffectWarnings.add(candidate + "#assetLookup")) {
                this.logger.atWarning().withCause(ex).log(
                    "[HyPerks] Could not validate particle system id '%s'. Using raw fallback.",
                    candidate
                );
            }
        }

        // Do not block runtime rendering when lookup heuristics fail.
        // Some environments expose particle keys in non-path formats.
        return normalizeEffectId(candidate);
    }

    private String findParticleKey(Map<String, ParticleSystem> particleMap, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }

        String candidateNormalized = normalizeEffectId(candidate);
        if (particleMap.containsKey(candidateNormalized)) {
            return candidateNormalized;
        }

        String candidateLower = candidateNormalized.toLowerCase(Locale.ROOT);
        String candidateNoExtension = stripParticleExtension(candidateLower);
        String candidateBaseName = basenameOfPath(candidateNoExtension);

        String bestKey = "";
        int bestScore = -1;

        for (String key : particleMap.keySet()) {
            if (key == null || key.isBlank()) {
                continue;
            }

            String keyNormalized = normalizeEffectId(key);
            String keyLower = keyNormalized.toLowerCase(Locale.ROOT);
            String keyNoExtension = stripParticleExtension(keyLower);
            String keyBaseName = basenameOfPath(keyNoExtension);

            int score = 0;
            if (keyLower.equals(candidateLower)) {
                score = 100;
            } else if (keyNoExtension.equals(candidateNoExtension)) {
                score = 95;
            } else if (keyBaseName.equals(candidateBaseName)) {
                score = 90;
            } else if (
                keyNoExtension.endsWith("/" + candidateNoExtension)
                    || candidateNoExtension.endsWith("/" + keyNoExtension)
            ) {
                score = 85;
            } else if (
                keyNoExtension.endsWith("/" + candidateBaseName)
                    || candidateNoExtension.endsWith("/" + keyBaseName)
            ) {
                score = 80;
            } else if (
                keyNoExtension.contains(candidateNoExtension)
                    || candidateNoExtension.contains(keyNoExtension)
            ) {
                score = 70;
            } else if (
                keyNoExtension.contains(candidateBaseName)
                    || candidateNoExtension.contains(keyBaseName)
            ) {
                score = 65;
            }

            if (score > bestScore) {
                bestScore = score;
                bestKey = key;
            }
        }

        if (bestScore >= 0) {
            return bestKey;
        }

        return "";
    }

    private String stripParticleExtension(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        if (value.toLowerCase(Locale.ROOT).endsWith(PARTICLE_EXTENSION)) {
            return value.substring(0, value.length() - PARTICLE_EXTENSION.length());
        }

        return value;
    }

    private String basenameOfPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private void pruneOldTrackers(long nowMs) {
        this.renderTrackers.entrySet().removeIf(entry -> (nowMs - entry.getValue().lastSeenMs) > TRACKER_RETENTION_MS);
    }

    private void rebuildLookup() {
        EnumMap<CosmeticCategory, Map<String, CosmeticDefinition>> rebuilt = new EnumMap<>(CosmeticCategory.class);
        for (CosmeticCategory category : CosmeticCategory.values()) {
            rebuilt.put(category, new LinkedHashMap<>());
        }

        for (CosmeticDefinition cosmetic : this.catalog.getCosmetics()) {
            if (!cosmetic.isEnabled()) {
                continue;
            }

            CosmeticCategory category = CosmeticCategory.fromId(cosmetic.getCategory());
            if (category == null) {
                continue;
            }

            rebuilt.get(category).put(cosmetic.getId(), cosmetic);
        }

        EnumMap<CosmeticCategory, Map<String, CosmeticDefinition>> immutable = new EnumMap<>(CosmeticCategory.class);
        for (Map.Entry<CosmeticCategory, Map<String, CosmeticDefinition>> entry : rebuilt.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }

        this.byCategory = Collections.unmodifiableMap(immutable);
    }

    private String categorySummary() {
        StringBuilder summary = new StringBuilder();
        for (CosmeticCategory category : CosmeticCategory.values()) {
            int size = this.byCategory.getOrDefault(category, Map.of()).size();
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(category.getId()).append(": ").append(size);
        }
        return summary.toString();
    }

    private boolean supportsMultiActive(CosmeticCategory category) {
        return MULTI_ACTIVE_CATEGORIES.contains(category);
    }

    private void sortCosmeticsForCategory(CosmeticCategory category, List<CosmeticDefinition> cosmetics) {
        Map<String, Integer> order = COSMETIC_ORDER.getOrDefault(category, Map.of());
        cosmetics.sort(
            Comparator
                .comparingInt((CosmeticDefinition cosmetic) -> order.getOrDefault(cosmetic.getId(), Integer.MAX_VALUE))
                .thenComparing(CosmeticDefinition::getId)
        );
    }

    private static Map<CosmeticCategory, Map<String, Integer>> createCosmeticOrder() {
        EnumMap<CosmeticCategory, Map<String, Integer>> order = new EnumMap<>(CosmeticCategory.class);
        order.put(
            CosmeticCategory.AURAS,
            createIdOrder(
                "ember_halo",
                "void_orbit",
                "angel_wings",
                "heart_bloom",
                "fire_ice_cone",
                "storm_clouds",
                "wingwang_sigil",
                "fireworks_show"
            )
        );
        order.put(
            CosmeticCategory.AURAS_PREMIUM,
            createIdOrder("vip_aura", "vip_plus_aura", "mvp_aura", "mvp_plus_aura")
        );
        order.put(
            CosmeticCategory.TRAILS,
            createIdOrder(
                "vip_trail",
                "vip_plus_trail",
                "mvp_trail",
                "mvp_plus_trail",
                "laser_trail",
                "star_trail",
                "money_trail",
                "death_trail",
                "music_trail",
                "flame_trail",
                "lightning_trail",
                "clover_trail",
                "sword_trail",
                "crown_trail",
                "dynamite_trail",
                "bomb_trail",
                "c4_trail",
                "radioactive_trail",
                "heart_fire_trail",
                "heart_broken_trail",
                "exclamation_trail",
                "question_trail",
                "spade_trail",
                "club_trail",
                "diamond_trail"
            )
        );
        order.put(
            CosmeticCategory.FLOATING_BADGES,
            createIdOrder(
                "vip",
                "vip_plus",
                "mvp",
                "mvp_plus",
                "mcqc_legacy_fleur",
                "mcqc_legacy_fleur_b",
                "mcqc_legacy_fleur_c"
            )
        );
        return Collections.unmodifiableMap(order);
    }

    private static Map<String, Integer> createIdOrder(String... ids) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            order.put(ids[i], i);
        }
        return Collections.unmodifiableMap(order);
    }

    private String tr(CommandSender sender, String key, Object... args) {
        UUID senderUuid = null;
        if (sender != null) {
            try {
                senderUuid = sender.getUuid();
            } catch (Exception ex) {
                if (this.config.isDebugMode()) {
                    this.logger.atFine().withCause(ex).log("[HyPerks] Could not read sender UUID on this thread.");
                }
            }
        }
        return tr(senderUuid, key, args);
    }

    private String tr(UUID senderUuid, String key, Object... args) {
        String locale = this.config.getDefaultLanguage();
        if (senderUuid != null) {
            PlayerState state = this.playerStateService.get(senderUuid);
            if (state.getLocale() != null && !state.getLocale().isBlank()) {
                locale = state.getLocale();
            }
        }
        return this.localizationService.translate(locale, key, args);
    }

    private boolean isWorldAllowed(Player player) {
        if (player == null || player.getWorld() == null) {
            return false;
        }
        return this.config.isWorldAllowed(player.getWorld().getName());
    }

    private boolean hasCosmeticPermission(CommandSender sender, CosmeticDefinition cosmetic) {
        if (hasPermissionCached(sender, cosmetic.getPermission())) {
            return true;
        }
        if (hasPermissionCached(sender, "hyperks.cosmetic.*")) {
            return true;
        }
        return hasPermissionCached(sender, "hyperks.cosmetic." + cosmetic.getCategory() + ".*");
    }

    private boolean hasPermissionCached(CommandSender sender, String permissionNode) {
        int ttlMs = this.config.getPermissionCacheTtlMs();
        if (ttlMs <= 0) {
            return sender.hasPermission(permissionNode);
        }

        UUID senderUuid = sender.getUuid();
        if (senderUuid == null) {
            return sender.hasPermission(permissionNode);
        }

        long nowMs = System.currentTimeMillis();
        PermissionCacheKey key = new PermissionCacheKey(senderUuid, permissionNode);
        PermissionCacheValue cached = this.permissionCache.get(key);
        if (cached != null && cached.expiresAtMs >= nowMs) {
            return cached.allowed;
        }

        boolean allowed = sender.hasPermission(permissionNode);
        this.permissionCache.put(key, new PermissionCacheValue(allowed, nowMs + ttlMs, nowMs));
        return allowed;
    }

    private void invalidatePermissionCache(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        this.permissionCache.entrySet().removeIf(entry -> entry.getKey().playerUuid.equals(playerUuid));
    }

    private void pruneOldCommandTrackers(long nowMs) {
        this.commandUsageTracker.entrySet().removeIf(entry -> (nowMs - entry.getValue()) > COMMAND_TRACKER_RETENTION_MS);
    }

    private void prunePermissionCache(long nowMs) {
        this.permissionCache.entrySet().removeIf(entry -> {
            PermissionCacheValue value = entry.getValue();
            if (value.expiresAtMs < nowMs) {
                return true;
            }
            return (nowMs - value.cachedAtMs) > PERMISSION_CACHE_RETENTION_MS;
        });
    }

    private UUID resolvePlayerUuid(Player player) {
        if (player == null) {
            return null;
        }
        World world = player.getWorld();
        Ref<EntityStore> playerReference = player.getReference();
        if (world == null || playerReference == null) {
            return null;
        }

        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            PlayerRef playerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
            if (playerRef != null) {
                return playerRef.getUuid();
            }
        } catch (Exception ex) {
            if (this.config.isDebugMode()) {
                this.logger.atFine().withCause(ex).log("[HyPerks] Could not resolve player UUID on this thread.");
            }
        }
        return null;
    }

    private UUID resolvePlayerUuidFromHolder(Holder<EntityStore> holder) {
        if (holder == null) {
            return null;
        }

        try {
            PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
            return playerRef == null ? null : playerRef.getUuid();
        } catch (Exception ex) {
            if (this.config.isDebugMode()) {
                this.logger.atFine().withCause(ex).log("[HyPerks] Could not resolve player UUID from holder.");
            }
            return null;
        }
    }

    private String normalizeId(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private void clearAllActiveCosmetics(PlayerState state) {
        if (state == null) {
            return;
        }
        for (CosmeticCategory category : CosmeticCategory.values()) {
            state.removeActive(category.getId());
        }
    }

    private String normalizeEffectId(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().replace('\\', '/');
    }

    private static Map<CosmeticCategory, Map<String, CosmeticDefinition>> emptyLookup() {
        EnumMap<CosmeticCategory, Map<String, CosmeticDefinition>> empty = new EnumMap<>(CosmeticCategory.class);
        for (CosmeticCategory category : CosmeticCategory.values()) {
            empty.put(category, Map.of());
        }
        return Collections.unmodifiableMap(empty);
    }

    public static final class MenuEntry {
        private final String categoryId;
        private final String cosmeticId;
        private final String displayName;
        private final String detailLine;
        private final boolean active;
        private final boolean unlocked;

        public MenuEntry(
            String categoryId,
            String cosmeticId,
            String displayName,
            String detailLine,
            boolean active,
            boolean unlocked
        ) {
            this.categoryId = categoryId;
            this.cosmeticId = cosmeticId;
            this.displayName = displayName;
            this.detailLine = detailLine;
            this.active = active;
            this.unlocked = unlocked;
        }

        public String getCategoryId() {
            return categoryId;
        }

        public String getCosmeticId() {
            return cosmeticId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDetailLine() {
            return detailLine;
        }

        public boolean isActive() {
            return active;
        }

        public boolean isUnlocked() {
            return unlocked;
        }
    }

    private static final class PermissionCacheKey {
        private final UUID playerUuid;
        private final String permissionNode;

        private PermissionCacheKey(UUID playerUuid, String permissionNode) {
            this.playerUuid = playerUuid;
            this.permissionNode = permissionNode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PermissionCacheKey that)) {
                return false;
            }
            return this.playerUuid.equals(that.playerUuid) && this.permissionNode.equals(that.permissionNode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.playerUuid, this.permissionNode);
        }
    }

    private static final class PermissionCacheValue {
        private final boolean allowed;
        private final long expiresAtMs;
        private final long cachedAtMs;

        private PermissionCacheValue(boolean allowed, long expiresAtMs, long cachedAtMs) {
            this.allowed = allowed;
            this.expiresAtMs = expiresAtMs;
            this.cachedAtMs = cachedAtMs;
        }
    }

    private static final class RenderTracker {
        private Vector3d lastFootstepPosition;
        private Vector3d lastTrailPosition;
        private long lastFootstepAtMs;
        private long lastSeenMs;
        private boolean nextFootRight = true;
    }

    private static final class ModelRenderContext {
        private final UUID playerUuid;
        private final Player player;
        private final Vector3d position;
        private final Vector3f rotation;

        private ModelRenderContext(UUID playerUuid, Player player, Vector3d position, Vector3f rotation) {
            this.playerUuid = playerUuid;
            this.player = player;
            this.position = position;
            this.rotation = rotation;
        }
    }

    private static final class ModelLodCellKey {
        private final int x;
        private final int y;
        private final int z;

        private ModelLodCellKey(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ModelLodCellKey that)) {
                return false;
            }
            return this.x == that.x && this.y == that.y && this.z == that.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.x, this.y, this.z);
        }
    }
}
