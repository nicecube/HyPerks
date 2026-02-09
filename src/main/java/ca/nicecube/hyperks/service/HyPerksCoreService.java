package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.config.CosmeticCatalog;
import ca.nicecube.hyperks.config.CosmeticDefinition;
import ca.nicecube.hyperks.config.HyPerksConfig;
import ca.nicecube.hyperks.model.CosmeticCategory;
import ca.nicecube.hyperks.model.PlayerState;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.pages.CommandListPage;
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

    private static final long FOOTPRINT_MIN_INTERVAL_MS = 180L;
    private static final double FOOTPRINT_MIN_MOVE_SQUARED = 0.16D;
    private static final long TRACKER_RETENTION_MS = 300_000L;
    private static final long COMMAND_TRACKER_RETENTION_MS = 600_000L;
    private static final long PERMISSION_CACHE_RETENTION_MS = 180_000L;

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
        boolean guiOpened = false;
        if (context.isPlayer() && this.config.getMenu().isGuiEnabled()) {
            guiOpened = tryOpenMenuGui(context.senderAs(Player.class));
            if (guiOpened) {
                send(context, "cmd.menu.gui_opened");
            } else if (!this.config.getMenu().isGuiFallbackToChat()) {
                send(context, "cmd.menu.gui_failed");
                return;
            }
        }

        send(context, "cmd.menu.title");
        send(context, "cmd.menu.line", "menu");
        send(context, "cmd.menu.line", "list [category]");
        send(context, "cmd.menu.line", "equip <category> <cosmeticId>");
        send(context, "cmd.menu.line", "unequip <category>");
        send(context, "cmd.menu.line", "active");
        send(context, "cmd.menu.line", "lang <en|fr>");
        send(context, "cmd.menu.line", "refreshperms");
        send(context, "cmd.menu.line", "status");
        send(context, "cmd.menu.line", "reload");
        if (guiOpened) {
            send(context, "cmd.menu.gui_hint");
        }

        for (CosmeticCategory category : CosmeticCategory.values()) {
            int loaded = this.byCategory.getOrDefault(category, Map.of()).size();
            send(context, "cmd.menu.category_line", category.getId(), loaded);
        }
        send(context, "cmd.menu.categories", categorySummary());
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
        state.setActive(category.getId(), cosmetic.getId());
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
        Map<String, String> active = new LinkedHashMap<>(state.getAllActive());

        send(context, "cmd.active.title");
        if (active.isEmpty()) {
            send(context, "cmd.active.none");
            return;
        }

        for (CosmeticCategory category : CosmeticCategory.values()) {
            String selected = active.get(category.getId());
            if (selected == null) {
                continue;
            }
            CosmeticDefinition cosmetic = this.byCategory.getOrDefault(category, Map.of()).get(selected);
            String cosmeticName = cosmetic == null ? selected : tr(player, cosmetic.getNameKey());
            sendRaw(context, "- " + category.getId() + ": " + cosmeticName);
        }
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

        UUID playerUuid = resolvePlayerUuid(event, player);
        if (playerUuid == null) {
            return;
        }

        invalidatePermissionCache(playerUuid);
        this.playerStateService.invalidate(playerUuid);
        PlayerState state = this.playerStateService.refresh(playerUuid);

        if (this.config.isAutoShowMenuHintOnJoin()) {
            player.sendMessage(Message.raw(tr(player, "join.hint")));
        }

        if (!state.getAllActive().isEmpty()) {
            player.sendMessage(Message.raw(tr(player, "join.active_loaded", state.getAllActive().size())));
        }

        if (this.config.isDebugMode()) {
            this.logger.atInfo().log(
                "[HyPerks] Player ready: %s (active cosmetics=%s)",
                playerUuid,
                state.getAllActive().size()
            );
        }
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
        RenderTracker tracker = this.renderTrackers.computeIfAbsent(playerUuid, ignored -> new RenderTracker());
        tracker.lastSeenMs = nowMs;

        renderCategory(player, state, store, position, tracker, nowMs, frame, CosmeticCategory.AURAS);
        renderCategory(player, state, store, position, tracker, nowMs, frame, CosmeticCategory.AURAS_PREMIUM);
        renderCategory(player, state, store, position, tracker, nowMs, frame, CosmeticCategory.TRAILS);
        renderCategory(player, state, store, position, tracker, nowMs, frame, CosmeticCategory.FOOTPRINTS);
        renderCategory(player, state, store, position, tracker, nowMs, frame, CosmeticCategory.FLOATING_BADGES);
        renderCategory(player, state, store, position, tracker, nowMs, frame, CosmeticCategory.TROPHY_BADGES);
    }

    private void renderCategory(
        Player player,
        PlayerState state,
        Store<EntityStore> store,
        Vector3d position,
        RenderTracker tracker,
        long nowMs,
        long frame,
        CosmeticCategory category
    ) {
        CosmeticDefinition cosmetic = resolveActiveCosmetic(state, category);
        if (cosmetic == null) {
            return;
        }

        if (!hasCosmeticPermission(player, cosmetic)) {
            return;
        }

        String effectId = resolveEffectId(cosmetic.getEffectId());
        if (effectId.isBlank()) {
            return;
        }

        switch (category) {
            case AURAS -> renderAura(effectId, cosmetic, position, store, frame);
            case AURAS_PREMIUM -> renderPremiumAura(effectId, cosmetic, position, store, frame);
            case TRAILS -> renderTrail(effectId, cosmetic, position, store, frame);
            case FOOTPRINTS -> renderFootprints(effectId, position, store, tracker, nowMs);
            case FLOATING_BADGES -> renderFloatingBadge(effectId, cosmetic, position, store, frame);
            case TROPHY_BADGES -> renderTrophyBadge(effectId, cosmetic, position, store, frame);
        }
    }

    private void renderAura(String effectId, CosmeticDefinition cosmetic, Vector3d position, Store<EntityStore> store, long frame) {
        String style = cosmetic.getRenderStyle();
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
        double phase = frame * 0.17D;
        double yBase = position.y + 2.05D;

        if ("crown".equals(style)) {
            double radius = 0.42D;
            for (int i = 0; i < 4; i++) {
                double angle = phase + (Math.PI * 2D * i / 4D);
                spawnParticle(
                    effectId,
                    position.x + Math.cos(angle) * radius,
                    yBase + Math.sin(phase + i) * 0.08D,
                    position.z + Math.sin(angle) * radius,
                    store
                );
            }
            if ((frame % 2L) == 0L) {
                spawnParticle(effectId, position.x, yBase + 0.35D, position.z, store);
            }
            return;
        }

        if ("pillar".equals(style)) {
            for (int i = 0; i < 3; i++) {
                double y = position.y + 0.35D + (i * 0.65D) + Math.sin((frame + i) * 0.15D) * 0.06D;
                spawnParticle(effectId, position.x, y, position.z, store);
            }
            return;
        }

        double radius = 0.62D;
        for (int i = 0; i < 5; i++) {
            double angle = phase + (Math.PI * 2D * i / 5D);
            double y = yBase + Math.sin(phase + i) * 0.12D;
            spawnParticle(effectId, position.x + Math.cos(angle) * radius, y, position.z + Math.sin(angle) * radius, store);
        }
    }

    private void renderTrail(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        long frame
    ) {
        String style = cosmetic.getRenderStyle();
        double sway = Math.sin(frame * 0.25D) * 0.18D;
        double baseY = position.y + 0.08D;

        if ("spiral".equals(style)) {
            double phase = frame * 0.32D;
            double radius = 0.22D;
            spawnParticle(effectId, position.x + Math.cos(phase) * radius, baseY, position.z + Math.sin(phase) * radius, store);
            spawnParticle(
                effectId,
                position.x + Math.cos(phase + Math.PI) * radius,
                baseY + 0.04D,
                position.z + Math.sin(phase + Math.PI) * radius,
                store
            );
            return;
        }

        if ("spark".equals(style)) {
            spawnParticle(effectId, position.x + sway, baseY, position.z, store);
            if ((frame % 2L) == 0L) {
                spawnParticle(effectId, position.x - sway, baseY + 0.05D, position.z, store);
            }
            return;
        }

        double phase = frame * 0.20D;
        spawnParticle(effectId, position.x + Math.cos(phase) * 0.12D, baseY, position.z + Math.sin(phase) * 0.12D, store);
        spawnParticle(effectId, position.x - Math.cos(phase) * 0.12D, baseY + 0.03D, position.z - Math.sin(phase) * 0.12D, store);
    }

    private void renderFootprints(
        String effectId,
        Vector3d position,
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

        spawnParticle(effectId, position.x, position.y + 0.05D, position.z, store);
        tracker.lastFootstepPosition = new Vector3d(position);
        tracker.lastFootstepAtMs = nowMs;
    }

    private void renderFloatingBadge(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        long frame
    ) {
        if ("rank_stream".equals(cosmetic.getRenderStyle())) {
            double phase = frame * 0.34D;
            double radius = 0.20D;
            double x = position.x + Math.cos(phase) * radius;
            double z = position.z + Math.sin(phase) * radius;
            spawnParticle(effectId, x, position.y + 0.08D, z, store);
            return;
        }

        double side = Math.sin(frame * 0.12D) * 0.18D;
        double bob = Math.sin(frame * 0.07D) * 0.10D;
        double y = position.y + 2.35D + bob;
        spawnParticle(effectId, position.x + side, y, position.z, store);

        if (!"badge".equals(cosmetic.getRenderStyle())) {
            spawnParticle(effectId, position.x - side, y, position.z, store);
        }
    }

    private void renderTrophyBadge(
        String effectId,
        CosmeticDefinition cosmetic,
        Vector3d position,
        Store<EntityStore> store,
        long frame
    ) {
        if (!"crown".equals(cosmetic.getRenderStyle())) {
            renderFloatingBadge(effectId, cosmetic, position, store, frame);
            return;
        }

        double phase = frame * 0.20D;
        double radius = 0.32D;
        double y = position.y + 2.65D;

        spawnParticle(effectId, position.x + Math.cos(phase) * radius, y, position.z + Math.sin(phase) * radius, store);
        spawnParticle(
            effectId,
            position.x + Math.cos(phase + Math.PI) * radius,
            y,
            position.z + Math.sin(phase + Math.PI) * radius,
            store
        );

        if ((frame % 2L) == 0L) {
            spawnParticle(effectId, position.x, y + 0.22D, position.z, store);
        }
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

            if (candidate.endsWith(PARTICLE_EXTENSION)) {
                String noExtension = candidate.substring(0, candidate.length() - PARTICLE_EXTENSION.length());
                resolved = findParticleKey(particleMap, noExtension);
            } else {
                resolved = findParticleKey(particleMap, candidate + PARTICLE_EXTENSION);
            }

            if (!resolved.isBlank()) {
                return resolved;
            }

            if (this.missingEffectWarnings.add(candidate)) {
                this.logger.atWarning().log(
                    "[HyPerks] Unknown particle system '%s'. Update cosmetics.json effectId values.",
                    candidate
                );
            }
        } catch (Exception ex) {
            if (this.missingEffectWarnings.add(candidate + "#assetLookup")) {
                this.logger.atWarning().withCause(ex).log(
                    "[HyPerks] Could not validate particle system id '%s'.",
                    candidate
                );
            }
        }

        return UNRESOLVED_EFFECT_ID;
    }

    private String findParticleKey(Map<String, ParticleSystem> particleMap, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }

        if (particleMap.containsKey(candidate)) {
            return candidate;
        }

        String candidateNormalized = normalizeEffectId(candidate).toLowerCase(Locale.ROOT);

        for (String key : particleMap.keySet()) {
            if (key.equalsIgnoreCase(candidate)) {
                return key;
            }

            String normalizedKey = normalizeEffectId(key).toLowerCase(Locale.ROOT);
            if (normalizedKey.equals(candidateNormalized)) {
                return key;
            }

            if (normalizedKey.endsWith("/" + candidateNormalized)) {
                return key;
            }
        }

        if (!candidate.startsWith("Server/")) {
            String withServerPrefix = "Server/" + candidate;
            if (particleMap.containsKey(withServerPrefix)) {
                return withServerPrefix;
            }
        }

        return "";
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

    private String tr(CommandSender sender, String key, Object... args) {
        String locale = this.config.getDefaultLanguage();
        if (sender != null) {
            UUID senderUuid = sender.getUuid();
            if (senderUuid != null) {
                PlayerState state = this.playerStateService.get(senderUuid);
                if (state.getLocale() != null && !state.getLocale().isBlank()) {
                    locale = state.getLocale();
                }
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

    private boolean tryOpenMenuGui(Player player) {
        if (player == null || player.getWorld() == null || player.getReference() == null) {
            return false;
        }

        World world = player.getWorld();

        try {
            world.execute(() -> {
                try {
                    if (player.getPageManager() == null || player.getReference() == null) {
                        return;
                    }

                    Store<EntityStore> store = world.getEntityStore().getStore();
                    PlayerRef playerRef = store.getComponent(player.getReference(), PlayerRef.getComponentType());
                    if (playerRef == null || !playerRef.isValid() || playerRef.getReference() == null) {
                        return;
                    }

                    player.getPageManager().openCustomPage(
                        playerRef.getReference(),
                        store,
                        new CommandListPage(playerRef, this.config.getMenu().getGuiCommandSeed())
                    );
                } catch (Exception ex) {
                    this.logger.atWarning().withCause(ex).log("[HyPerks] Could not open GUI menu for a player.");
                }
            });
            return true;
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to schedule GUI menu opening.");
            return false;
        }
    }

    private UUID resolvePlayerUuid(PlayerReadyEvent event, Player player) {
        if (player.getWorld() == null) {
            return null;
        }

        PlayerRef playerRef = player.getWorld()
            .getEntityStore()
            .getStore()
            .getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
        if (playerRef != null) {
            return playerRef.getUuid();
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
    }
}
