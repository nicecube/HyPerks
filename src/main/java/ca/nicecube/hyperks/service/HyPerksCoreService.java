package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.config.CosmeticCatalog;
import ca.nicecube.hyperks.config.CosmeticDefinition;
import ca.nicecube.hyperks.config.HyPerksConfig;
import ca.nicecube.hyperks.model.CosmeticCategory;
import ca.nicecube.hyperks.model.PlayerState;
import ca.nicecube.hyperks.ui.HyPerksMenuPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class HyPerksCoreService {
    private static final String PARTICLE_EXTENSION = ".particlesystem";
    private static final String UNRESOLVED_EFFECT_ID = "";
    private static final String RENDER_THREAD_NAME = "HyPerks-Renderer";

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

    private volatile boolean runtimeManaged = false;
    private ScheduledExecutorService runtimeExecutor;
    private ScheduledFuture<?> runtimeTask;

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

        this.logger.atInfo().log(
            "[HyPerks] Reloaded: cosmetics=%s, categories=%s, runtime=%s (%sms), cooldown=%sms, permCacheTtl=%sms, worlds=%s",
            this.catalog.getCosmetics().size(),
            this.byCategory.size(),
            this.config.isRuntimeRenderingEnabled(),
            this.config.getRuntimeRenderIntervalMs(),
            this.config.getCommandCooldownMs(),
            this.config.getPermissionCacheTtlMs(),
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
        send(context, "cmd.menu.line", "active");
        send(context, "cmd.menu.line", "lang <en|fr>");
        send(context, "cmd.menu.line", "refreshperms");
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
                    "- [%s] %s (%s/%s) -> %s | style=%s | fx=%s",
                    lockText,
                    cosmeticName,
                    cosmetic.getCategory(),
                    cosmetic.getId(),
                    cosmetic.getPermission(),
                    cosmetic.getRenderStyle(),
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
        send(context, "cmd.unequip.success", category.getId());
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
            player.sendMessage(Message.raw(tr(player, "cmd.menu.toggled_off", tr(player, cosmetic.getNameKey()))));
            return;
        }

        if (supportsMultiActive(category)) {
            state.addActive(category.getId(), cosmetic.getId());
        } else {
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
        this.runtimeExecutor = Executors.newSingleThreadScheduledExecutor(new RenderThreadFactory());
        this.runtimeTask = this.runtimeExecutor.scheduleAtFixedRate(
            this::tickRuntimeRendererSafe,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS
        );

        this.logger.atInfo().log("[HyPerks] Runtime renderer started (%sms).", intervalMs);
    }

    private synchronized void stopRuntimeRendererInternal() {
        if (this.runtimeTask != null) {
            this.runtimeTask.cancel(false);
            this.runtimeTask = null;
        }

        if (this.runtimeExecutor != null) {
            this.runtimeExecutor.shutdownNow();
            try {
                this.runtimeExecutor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            this.runtimeExecutor = null;
        }

        this.renderTrackers.clear();
    }

    private void tickRuntimeRendererSafe() {
        try {
            tickRuntimeRenderer();
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Runtime render tick failed.");
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

    private void renderWorld(World world, long frame) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        long nowMs = System.currentTimeMillis();

        for (PlayerRef playerRef : world.getPlayerRefs()) {
            renderPlayer(store, playerRef, frame, nowMs);
        }
    }

    private void renderPlayer(Store<EntityStore> store, PlayerRef playerRef, long frame, long nowMs) {
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

        PlayerState state = this.playerStateService.get(playerUuid);
        if (state.getAllActive().isEmpty()) {
            return;
        }

        TransformComponent transform = store.getComponent(playerRef.getReference(), TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return;
        }

        Vector3d position = new Vector3d(transform.getPosition());
        double yawDegrees = 0.0D;
        Vector3f rotation = transform.getRotation();
        if (rotation != null) {
            yawDegrees = rotation.getYaw();
        }
        RenderTracker tracker = this.renderTrackers.computeIfAbsent(playerUuid, ignored -> new RenderTracker());
        tracker.lastSeenMs = nowMs;

        renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.AURAS);
        renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.AURAS_PREMIUM);
        renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.TRAILS);
        renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.FOOTPRINTS);
        renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.FLOATING_BADGES);
        renderCategory(player, state, store, position, yawDegrees, tracker, nowMs, frame, CosmeticCategory.TROPHY_BADGES);
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

            String effectId = resolveEffectId(cosmetic.getEffectId());
            if (effectId.isBlank()) {
                continue;
            }

            switch (category) {
                case AURAS -> renderAura(effectId, cosmetic, position, yawDegrees, store, frame);
                case AURAS_PREMIUM -> renderPremiumAura(effectId, cosmetic, position, store, frame);
                case TRAILS -> renderTrail(effectId, cosmetic, position, store, frame);
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
        long frame
    ) {
        String style = cosmetic.getRenderStyle();
        String cosmeticId = cosmetic.getId();
        double baseY = position.y + 0.24D;

        if ("comet".equals(style)) {
            if ((frame % 2L) != 0L) {
                return;
            }
            double phase = frame * 0.12D;
            double headX = position.x + Math.cos(phase) * 0.16D;
            double headZ = position.z + Math.sin(phase) * 0.16D;
            spawnParticle(effectId, headX, baseY + 0.04D, headZ, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 0.8D) * 0.13D, baseY + 0.03D, position.z + Math.sin(phase + 0.8D) * 0.13D, store);
            for (int tail = 1; tail <= 3; tail++) {
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
            if ((frame % 2L) != 0L) {
                return;
            }
            double sway = Math.sin(frame * 0.14D) * 0.12D;
            spawnParticle(effectId, position.x + sway, baseY + 0.03D, position.z, store);
            spawnParticle(effectId, position.x - sway, baseY + 0.04D, position.z, store);
            spawnParticle(effectId, position.x, baseY + 0.05D, position.z + sway, store);
            spawnParticle(effectId, position.x, baseY + 0.03D, position.z - sway, store);
            return;
        }

        if ("spiral".equals(style)) {
            if ((frame % 2L) != 0L) {
                return;
            }
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
            return;
        }

        if ("supreme".equals(style)) {
            if ((frame % 1L) != 0L) {
                return;
            }
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
            return;
        }

        if ("laser".equals(style)) {
            if ((frame % 2L) != 0L) {
                return;
            }
            double phase = frame * 0.10D;
            for (int i = 0; i < 5; i++) {
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
            if ((frame % 2L) != 0L) {
                return;
            }
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
                return;
            }

            spawnParticle(effectId, position.x, baseY + 0.05D, position.z, store);
            spawnParticle(effectId, position.x + Math.cos(phase) * 0.14D, baseY + 0.02D, position.z + Math.sin(phase) * 0.14D, store);
            spawnParticle(effectId, position.x + Math.cos(phase + 2.2D) * 0.18D, baseY + 0.06D, position.z + Math.sin(phase + 2.2D) * 0.18D, store);
            spawnParticle(effectId, position.x - Math.cos(phase) * 0.12D, baseY + 0.04D, position.z - Math.sin(phase) * 0.12D, store);
            return;
        }

        double phase = frame * 0.14D;
        spawnParticle(effectId, position.x + Math.cos(phase) * 0.1D, baseY, position.z + Math.sin(phase) * 0.1D, store);
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
        if ((frame % 4L) != 0L) {
            return;
        }

        double baseAngle = (Math.PI * 2D * slot / Math.max(1, totalSlots));
        double phase = (frame * 0.040D) + baseAngle;
        double radius = 0.38D + (Math.max(0, Math.min(4, totalSlots - 1)) * 0.04D);
        double y = position.y + 2.18D;
        double x = position.x + Math.cos(phase) * radius;
        double z = position.z + Math.sin(phase) * radius;
        spawnParticle(effectId, x, y, z, store);
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
        if ((frame % 4L) != 0L) {
            return;
        }

        double baseAngle = (Math.PI * 2D * slot / Math.max(1, totalSlots));
        double phase = (frame * 0.037D) + baseAngle;
        double baseRadius = "crown".equals(cosmetic.getRenderStyle()) ? 0.40D : 0.38D;
        double radius = baseRadius + (Math.max(0, Math.min(4, totalSlots - 1)) * 0.04D);
        double y = position.y + 2.28D;
        spawnParticle(effectId, position.x + Math.cos(phase) * radius, y, position.z + Math.sin(phase) * radius, store);
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

    private String normalizeId(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
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

    private static final class RenderThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, RENDER_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
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
        private long lastFootstepAtMs;
        private long lastSeenMs;
        private boolean nextFootRight = true;
    }
}
