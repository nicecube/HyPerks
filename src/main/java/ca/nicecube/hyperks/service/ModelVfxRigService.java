package ca.nicecube.hyperks.service;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ModelVfxRigService {
    private static final String MODEL_EXTENSION = ".json";
    private static final long RIG_RETENTION_MS = 45_000L;
    private static final long DEBUG_RIG_LIFETIME_MS = 20_000L;
    private static final int DEFAULT_MAX_RIGS_PER_PLAYER = 16;
    private static final int DEFAULT_LOD_ULTRA_MAX_PLAYERS = 10;

    private final HytaleLogger logger;
    private final Map<String, String> resolvedModelIds = new ConcurrentHashMap<>();
    private final Set<String> missingModelWarnings = ConcurrentHashMap.newKeySet();
    private final Set<String> failedRigWarnings = ConcurrentHashMap.newKeySet();
    private final Set<UUID> budgetWarningPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<RigKey, RigInstance>> rigsByPlayer = new ConcurrentHashMap<>();
    private volatile int rigBudgetPerPlayer = DEFAULT_MAX_RIGS_PER_PLAYER;
    private volatile int lodUltraMaxPlayers = DEFAULT_LOD_ULTRA_MAX_PLAYERS;

    public ModelVfxRigService(HytaleLogger logger) {
        this.logger = logger;
    }

    public void clearCaches() {
        this.resolvedModelIds.clear();
        this.missingModelWarnings.clear();
        this.failedRigWarnings.clear();
    }

    public void configure(int maxRigsPerPlayer, int lodUltraMaxWorldPlayers) {
        this.rigBudgetPerPlayer = clamp(maxRigsPerPlayer, 1, 64, DEFAULT_MAX_RIGS_PER_PLAYER);
        this.lodUltraMaxPlayers = clamp(lodUltraMaxWorldPlayers, 1, 200, DEFAULT_LOD_ULTRA_MAX_PLAYERS);
    }

    public int getRigBudgetPerPlayer() {
        return this.rigBudgetPerPlayer;
    }

    public int getLodUltraMaxPlayers() {
        return this.lodUltraMaxPlayers;
    }

    public int getActiveRigPlayerCount() {
        return this.rigsByPlayer.size();
    }

    public int getActiveRigCount() {
        int total = 0;
        for (Map<RigKey, RigInstance> rigs : this.rigsByPlayer.values()) {
            if (rigs != null) {
                total += rigs.size();
            }
        }
        return total;
    }

    public List<AuditPart> describeAuditParts(
        String categoryId,
        String cosmeticId,
        String modelAssetId,
        String rigProfile,
        boolean ultraTier
    ) {
        DesiredRig desired = new DesiredRig(categoryId, cosmeticId, modelAssetId, rigProfile);
        QualityTier tier = ultraTier ? QualityTier.ULTRA : QualityTier.BALANCED;
        List<DesiredPart> expanded = expandDesiredParts(List.of(desired), tier);
        List<AuditPart> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (DesiredPart part : expanded) {
            if (part == null || part.modelAssetId == null || part.modelAssetId.isBlank()) {
                continue;
            }

            String requested = normalizeAssetId(part.modelAssetId);
            String dedupeKey = part.partId + "|" + requested;
            if (!seen.add(dedupeKey)) {
                continue;
            }

            String resolved = resolveModelAssetId(requested);
            result.add(new AuditPart(part.partId, requested, resolved, !resolved.isBlank()));
        }

        return result;
    }

    public void syncPlayerRigs(
        UUID playerUuid,
        World world,
        Store<EntityStore> store,
        Vector3d playerPosition,
        Vector3f playerRotation,
        List<DesiredRig> desiredRigs,
        int worldPlayerCount,
        long frame,
        long nowMs
    ) {
        if (playerUuid == null || world == null || store == null || playerPosition == null) {
            return;
        }

        Map<RigKey, RigInstance> playerRigs = this.rigsByPlayer.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>());
        int rigBudget = this.rigBudgetPerPlayer;
        int lodUltraMax = this.lodUltraMaxPlayers;
        QualityTier qualityTier = resolveQualityTier(worldPlayerCount, lodUltraMax);
        List<DesiredPart> parts = expandDesiredParts(desiredRigs, qualityTier);
        Set<RigKey> desiredKeys = new HashSet<>();

        boolean budgetReached = false;
        int accepted = 0;
        for (DesiredPart part : parts) {
            if (accepted >= rigBudget) {
                budgetReached = true;
                break;
            }
            accepted++;

            RigKey key = new RigKey(part.categoryId, part.cosmeticId, part.partId);
            desiredKeys.add(key);

            RigInstance active = playerRigs.get(key);
            if (active != null && (!active.world.equals(world) || !active.requestedModelAssetId.equals(part.modelAssetId))) {
                removePossiblyCrossWorld(store, world, active);
                playerRigs.remove(key);
                active = null;
            }

            if (active == null) {
                RigInstance spawned = spawnRig(store, world, part, playerPosition, playerRotation, frame, nowMs);
                if (spawned != null) {
                    playerRigs.put(key, spawned);
                }
                continue;
            }

            if (!updateRigTransform(store, active, playerPosition, playerRotation, frame)) {
                playerRigs.remove(key);
                continue;
            }
            active.lastSeenMs = nowMs;
        }

        if (budgetReached) {
            if (this.budgetWarningPlayers.add(playerUuid)) {
                this.logger.atWarning().log(
                    "[HyPerks] Model rig budget reached for player %s (%s). Some rig parts were skipped.",
                    playerUuid,
                    rigBudget
                );
            }
        } else {
            this.budgetWarningPlayers.remove(playerUuid);
        }

        List<RigKey> staleKeys = new ArrayList<>();
        for (Map.Entry<RigKey, RigInstance> entry : playerRigs.entrySet()) {
            if (!desiredKeys.contains(entry.getKey())) {
                removePossiblyCrossWorld(store, world, entry.getValue());
                staleKeys.add(entry.getKey());
            }
        }
        for (RigKey key : staleKeys) {
            playerRigs.remove(key);
        }

        if (playerRigs.isEmpty()) {
            this.rigsByPlayer.remove(playerUuid);
        }
    }

    public void clearCategoryRigs(UUID playerUuid, String categoryId) {
        if (playerUuid == null || categoryId == null || categoryId.isBlank()) {
            return;
        }

        Map<RigKey, RigInstance> playerRigs = this.rigsByPlayer.get(playerUuid);
        if (playerRigs == null || playerRigs.isEmpty()) {
            return;
        }

        String normalizedCategory = categoryId.trim().toLowerCase(Locale.ROOT);
        List<RigKey> removed = new ArrayList<>();
        for (Map.Entry<RigKey, RigInstance> entry : playerRigs.entrySet()) {
            if (!entry.getKey().categoryId.equals(normalizedCategory)) {
                continue;
            }
            scheduleRigRemoval(entry.getValue());
            removed.add(entry.getKey());
        }

        for (RigKey key : removed) {
            playerRigs.remove(key);
        }

        if (playerRigs.isEmpty()) {
            this.rigsByPlayer.remove(playerUuid);
        }
    }

    public void clearPlayerRigs(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        this.budgetWarningPlayers.remove(playerUuid);
        Map<RigKey, RigInstance> removed = this.rigsByPlayer.remove(playerUuid);
        if (removed == null) {
            return;
        }

        for (RigInstance rig : removed.values()) {
            scheduleRigRemoval(rig);
        }
    }

    public void clearAllRigs() {
        for (UUID playerUuid : List.copyOf(this.rigsByPlayer.keySet())) {
            clearPlayerRigs(playerUuid);
        }
    }

    public void pruneStaleRigs(long nowMs) {
        for (Map.Entry<UUID, Map<RigKey, RigInstance>> playerEntry : this.rigsByPlayer.entrySet()) {
            UUID playerUuid = playerEntry.getKey();
            Map<RigKey, RigInstance> rigs = playerEntry.getValue();
            if (rigs == null || rigs.isEmpty()) {
                this.rigsByPlayer.remove(playerUuid);
                continue;
            }

            List<RigKey> stale = new ArrayList<>();
            for (Map.Entry<RigKey, RigInstance> entry : rigs.entrySet()) {
                if ((nowMs - entry.getValue().lastSeenMs) <= RIG_RETENTION_MS) {
                    continue;
                }
                scheduleRigRemoval(entry.getValue());
                stale.add(entry.getKey());
            }

            for (RigKey key : stale) {
                rigs.remove(key);
            }

            if (rigs.isEmpty()) {
                this.rigsByPlayer.remove(playerUuid);
                this.budgetWarningPlayers.remove(playerUuid);
            }
        }
    }
    public DebugSpawnResult spawnDebugRig(
        World world,
        Store<EntityStore> store,
        Vector3d playerPosition,
        Vector3f playerRotation,
        String requestedModelAssetId
    ) {
        DesiredPart debug = new DesiredPart("debug", "debug_model", "debug", "main", requestedModelAssetId);
        RigInstance spawned = spawnRig(store, world, debug, playerPosition, playerRotation, 0L, System.currentTimeMillis());
        if (spawned == null) {
            return DebugSpawnResult.failed(normalizeAssetId(requestedModelAssetId));
        }

        HytaleServer.SCHEDULED_EXECUTOR.schedule(
            () -> {
                if (world == null || !world.isAlive()) {
                    return;
                }
                world.execute(() -> {
                    Store<EntityStore> liveStore = world.getEntityStore().getStore();
                    removeRigInternal(liveStore, spawned);
                });
            },
            DEBUG_RIG_LIFETIME_MS,
            TimeUnit.MILLISECONDS
        );

        return DebugSpawnResult.success(spawned.resolvedModelAssetId);
    }

    private List<DesiredPart> expandDesiredParts(List<DesiredRig> desiredRigs, QualityTier qualityTier) {
        List<DesiredPart> expanded = new ArrayList<>();
        List<DesiredRig> safeDesired = desiredRigs == null ? List.of() : desiredRigs;

        for (DesiredRig desired : safeDesired) {
            if (desired == null || desired.modelAssetId().isBlank()) {
                continue;
            }

            String base = normalizeAssetId(desired.modelAssetId());
            String profile = desired.rigProfile();

            switch (profile) {
                case "fire_ice_cone" -> {
                    expanded.add(part(desired, "core", pickPartModel(base, "_Core")));
                    expanded.add(part(desired, "helix_fire", pickPartModel(base, "_HelixFire")));
                    if (qualityTier == QualityTier.ULTRA) {
                        expanded.add(part(desired, "helix_ice", pickPartModel(base, "_HelixIce")));
                    }
                }
                case "storm_clouds" -> {
                    expanded.add(part(desired, "cloud_a", base));
                    expanded.add(part(desired, "cloud_b", pickPartModel(base, "_Core")));
                    if (qualityTier == QualityTier.ULTRA) {
                        expanded.add(part(desired, "cloud_c", base));
                    }
                    expanded.add(part(desired, "sun_cloud", pickPartModel(base, "_Ring")));
                }
                case "wingwang_sigil" -> {
                    expanded.add(part(desired, "inner", pickPartModel(base, "_Inner")));
                    expanded.add(part(desired, "outer", pickPartModel(base, "_Outer")));
                    if (qualityTier == QualityTier.ULTRA) {
                        expanded.add(part(desired, "mid", pickPartModel(base, "_Mid")));
                    }
                }
                case "fireworks_show" -> {
                    expanded.add(part(desired, "launcher", pickPartModel(base, "_Launcher")));
                    if (qualityTier == QualityTier.ULTRA) {
                        expanded.add(part(desired, "burst", pickPartModel(base, "_Burst")));
                    }
                }
                default -> expanded.add(part(desired, "main", base));
            }
        }

        return expanded;
    }

    private QualityTier resolveQualityTier(int worldPlayerCount, int lodUltraMaxPlayers) {
        if (worldPlayerCount <= 0) {
            return QualityTier.ULTRA;
        }
        return worldPlayerCount <= lodUltraMaxPlayers ? QualityTier.ULTRA : QualityTier.BALANCED;
    }

    private DesiredPart part(DesiredRig desired, String partId, String modelAssetId) {
        return new DesiredPart(desired.categoryId(), desired.cosmeticId(), desired.rigProfile(), partId, modelAssetId);
    }

    private String pickPartModel(String baseModelAssetId, String suffix) {
        String candidate = appendSuffix(baseModelAssetId, suffix);
        if (candidate.isBlank()) {
            return baseModelAssetId;
        }

        Map<String, ModelAsset> modelMap = ModelAsset.getAssetMap().getAssetMap();
        if (!findModelKey(modelMap, candidate).isBlank()) {
            return candidate;
        }
        return baseModelAssetId;
    }

    private String appendSuffix(String baseModelAssetId, String suffix) {
        if (baseModelAssetId == null || baseModelAssetId.isBlank()) {
            return "";
        }

        String normalized = normalizeAssetId(baseModelAssetId);
        if (normalized.toLowerCase(Locale.ROOT).endsWith(MODEL_EXTENSION)) {
            return normalized.substring(0, normalized.length() - MODEL_EXTENSION.length()) + suffix + MODEL_EXTENSION;
        }
        return normalized + suffix + MODEL_EXTENSION;
    }
    private RigInstance spawnRig(
        Store<EntityStore> store,
        World world,
        DesiredPart part,
        Vector3d playerPosition,
        Vector3f playerRotation,
        long frame,
        long nowMs
    ) {
        String requestedModelAssetId = normalizeAssetId(part.modelAssetId);
        ModelAsset asset = resolveModelAsset(requestedModelAssetId);
        if (asset == null) {
            return null;
        }

        Model model = Model.createUnitScaleModel(asset);
        Vector3d spawnPosition = computeRigPosition(part.rigProfile, part.partId, playerPosition, playerRotation, frame);
        Vector3f spawnRotation = computeRigRotation(part.rigProfile, part.partId, playerRotation, frame);

        try {
            com.hypixel.hytale.component.Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.putComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(spawnPosition), new Vector3f(spawnRotation)));
            holder.putComponent(ModelComponent.getComponentType(), new ModelComponent(model));
            holder.putComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
            holder.putComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
            holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.putComponent(UUIDComponent.getComponentType(), UUIDComponent.randomUUID());

            String animationId = resolvePreferredAnimation(model, part.rigProfile, part.partId);
            if (!animationId.isBlank()) {
                ActiveAnimationComponent activeAnimation = new ActiveAnimationComponent();
                activeAnimation.setPlayingAnimation(AnimationSlot.Status, animationId);
                holder.putComponent(ActiveAnimationComponent.getComponentType(), activeAnimation);
            }

            Ref<EntityStore> rigRef = store.addEntity(holder, AddReason.SPAWN);
            if (rigRef == null) {
                return null;
            }

            return new RigInstance(rigRef, world, part.rigProfile, part.partId, requestedModelAssetId, resolveModelAssetId(requestedModelAssetId), nowMs);
        } catch (Exception ex) {
            String warningKey = requestedModelAssetId + "#spawn";
            if (this.failedRigWarnings.add(warningKey)) {
                this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to spawn model rig '%s'.", requestedModelAssetId);
            }
            return null;
        }
    }

    private boolean updateRigTransform(Store<EntityStore> store, RigInstance rig, Vector3d playerPosition, Vector3f playerRotation, long frame) {
        try {
            if (rig.entityRef == null || !rig.entityRef.isValid()) {
                return false;
            }

            TransformComponent transform = store.getComponent(rig.entityRef, TransformComponent.getComponentType());
            if (transform == null) {
                return false;
            }

            Vector3d nextPosition = computeRigPosition(rig.rigProfile, rig.partId, playerPosition, playerRotation, frame);
            Vector3f nextRotation = computeRigRotation(rig.rigProfile, rig.partId, playerRotation, frame);
            transform.setPosition(nextPosition);
            transform.setRotation(nextRotation);
            return true;
        } catch (Exception ex) {
            String warningKey = rig.requestedModelAssetId + "#update";
            if (this.failedRigWarnings.add(warningKey)) {
                this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to update model rig '%s'.", rig.requestedModelAssetId);
            }
            return false;
        }
    }

    private void removePossiblyCrossWorld(Store<EntityStore> store, World world, RigInstance rig) {
        if (rig == null) {
            return;
        }

        if (rig.world.equals(world)) {
            removeRigInternal(store, rig);
            return;
        }

        scheduleRigRemoval(rig);
    }

    private void removeRigInternal(Store<EntityStore> store, RigInstance rig) {
        if (store == null || rig == null || rig.entityRef == null || !rig.entityRef.isValid()) {
            return;
        }

        try {
            store.removeEntity(rig.entityRef, RemoveReason.REMOVE);
        } catch (Exception ex) {
            String warningKey = rig.requestedModelAssetId + "#remove";
            if (this.failedRigWarnings.add(warningKey)) {
                this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to remove model rig '%s'.", rig.requestedModelAssetId);
            }
        }
    }

    private void scheduleRigRemoval(RigInstance rig) {
        if (rig == null || rig.world == null || !rig.world.isAlive()) {
            return;
        }

        rig.world.execute(() -> {
            Store<EntityStore> store = rig.world.getEntityStore().getStore();
            removeRigInternal(store, rig);
        });
    }
    private ModelAsset resolveModelAsset(String requestedModelAssetId) {
        String resolvedModelId = resolveModelAssetId(requestedModelAssetId);
        if (resolvedModelId.isBlank()) {
            return null;
        }

        return ModelAsset.getAssetMap().getAssetMap().get(resolvedModelId);
    }

    private String resolveModelAssetId(String configuredModelAssetId) {
        String normalized = normalizeAssetId(configuredModelAssetId);
        if (normalized.isBlank()) {
            return "";
        }

        return this.resolvedModelIds.computeIfAbsent(normalized, this::resolveModelAssetIdFromAssets);
    }

    private String resolveModelAssetIdFromAssets(String candidate) {
        try {
            Map<String, ModelAsset> modelMap = ModelAsset.getAssetMap().getAssetMap();
            String resolved = findModelKey(modelMap, candidate);
            if (!resolved.isBlank()) {
                return resolved;
            }

            String noExt = stripModelExtension(candidate);
            if (!noExt.equalsIgnoreCase(candidate)) {
                resolved = findModelKey(modelMap, noExt);
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }

            String baseName = basename(noExt);
            if (!baseName.isBlank()) {
                resolved = findModelKey(modelMap, baseName);
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }

            if (this.missingModelWarnings.add(candidate)) {
                this.logger.atWarning().log("[HyPerks] Unknown model asset '%s'. Model rig skipped.", candidate);
            }
        } catch (Exception ex) {
            if (this.missingModelWarnings.add(candidate + "#assetLookup")) {
                this.logger.atWarning().withCause(ex).log("[HyPerks] Could not resolve model asset '%s'.", candidate);
            }
        }

        return "";
    }

    private String findModelKey(Map<String, ModelAsset> modelMap, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }

        String normalizedCandidate = normalizeAssetId(candidate);
        if (modelMap.containsKey(normalizedCandidate)) {
            return normalizedCandidate;
        }

        String lowerCandidate = stripServerModelPrefix(stripModelExtension(normalizedCandidate.toLowerCase(Locale.ROOT)));
        for (String key : modelMap.keySet()) {
            if (key == null || key.isBlank()) {
                continue;
            }

            String lowerKey = stripServerModelPrefix(stripModelExtension(normalizeAssetId(key).toLowerCase(Locale.ROOT)));
            if (lowerKey.equals(lowerCandidate) || lowerKey.endsWith("/" + lowerCandidate) || lowerCandidate.endsWith("/" + lowerKey)) {
                return key;
            }
        }

        return "";
    }

    private String resolvePreferredAnimation(Model model, String rigProfile, String partId) {
        if (model == null) {
            return "";
        }

        String profile = rigProfile == null ? "" : rigProfile.trim().toLowerCase(Locale.ROOT);
        String part = partId == null ? "" : partId.trim().toLowerCase(Locale.ROOT);

        return switch (profile) {
            case "fire_ice_cone", "storm_clouds" -> safeAnimation(model.getFirstBoundAnimationId("Idle", "Loop"));
            case "wingwang_sigil" -> safeAnimation(model.getFirstBoundAnimationId("Idle", "Action", "Loop"));
            case "fireworks_show" -> "launcher".equals(part)
                ? safeAnimation(model.getFirstBoundAnimationId("Action", "Idle", "Loop"))
                : safeAnimation(model.getFirstBoundAnimationId("Idle", "Loop", "Action"));
            default -> safeAnimation(model.getFirstBoundAnimationId("Idle", "Loop", "Action"));
        };
    }

    private String safeAnimation(String animationId) {
        return animationId == null ? "" : animationId;
    }

    private Vector3d computeRigPosition(String rigProfile, String partId, Vector3d playerPosition, Vector3f playerRotation, long frame) {
        String profile = rigProfile == null ? "" : rigProfile.trim().toLowerCase(Locale.ROOT);
        String part = partId == null ? "" : partId.trim().toLowerCase(Locale.ROOT);

        double localX = 0.0D;
        double localY = 1.60D;
        double localZ = 0.0D;

        switch (profile) {
            case "fire_ice_cone" -> {
                localY = 1.08D;
                localZ = -0.24D;
                if ("helix_fire".equals(part) || "helix_ice".equals(part)) {
                    double dir = "helix_fire".equals(part) ? 1.0D : -1.0D;
                    double phase = (frame * 0.17D * dir) + ("helix_ice".equals(part) ? Math.PI : 0.0D);
                    localX = Math.cos(phase) * 0.24D;
                    localZ = -0.24D + (Math.sin(phase) * 0.24D);
                    localY = 0.95D + (Math.sin(frame * 0.09D + dir) * 0.06D);
                }
            }
            case "storm_clouds" -> {
                localY = 2.26D + (Math.sin(frame * 0.03D) * 0.03D);
                localZ = -0.20D;

                if ("cloud_a".equals(part) || "core".equals(part)) {
                    double phase = frame * 0.010D;
                    localX = Math.cos(phase) * 0.52D;
                    localZ = -0.20D + (Math.sin(phase) * 0.30D);
                    localY = 2.24D + (Math.sin(frame * 0.035D) * 0.04D);
                } else if ("cloud_b".equals(part) || "ring".equals(part)) {
                    double phase = (frame * 0.009D) + 2.094D;
                    localX = Math.cos(phase) * 0.46D;
                    localZ = -0.14D + (Math.sin(phase) * 0.26D);
                    localY = 2.30D + (Math.sin(frame * 0.030D + 0.9D) * 0.04D);
                } else if ("cloud_c".equals(part) || "bolt".equals(part)) {
                    double phase = (frame * 0.008D) + 4.188D;
                    localX = Math.cos(phase) * 0.58D;
                    localZ = -0.26D + (Math.sin(phase) * 0.32D);
                    localY = 2.20D + (Math.sin(frame * 0.028D + 1.8D) * 0.04D);
                } else if ("sun_cloud".equals(part)) {
                    double phase = frame * 0.006D;
                    localX = Math.cos(phase) * 0.18D;
                    localZ = -0.74D + (Math.sin(phase) * 0.12D);
                    localY = 2.44D + (Math.sin(frame * 0.026D) * 0.03D);
                }
            }
            case "wingwang_sigil" -> {
                localY = 1.56D + (Math.sin(frame * 0.11D) * 0.04D);
                localZ = -0.58D;
                if ("inner".equals(part)) {
                    localX = Math.cos(frame * 0.20D) * 0.07D;
                } else if ("mid".equals(part)) {
                    localX = Math.cos(frame * 0.15D + 0.9D) * 0.13D;
                    localY += 0.03D;
                } else if ("outer".equals(part)) {
                    localX = Math.cos(frame * 0.10D + 1.8D) * 0.20D;
                    localY += 0.06D;
                }
            }
            case "fireworks_show" -> {
                if ("launcher".equals(part)) {
                    localX = Math.cos(frame * 0.08D) * 0.10D;
                    localY = 1.82D;
                    localZ = -0.18D;
                } else {
                    localX = Math.cos(frame * 0.16D) * 0.22D;
                    localY = 2.48D + (Math.sin(frame * 0.13D) * 0.08D);
                    localZ = -0.22D + (Math.sin(frame * 0.16D) * 0.06D);
                }
            }
            default -> {
            }
        }

        double yaw = playerRotation == null ? 0.0D : playerRotation.getYaw();
        double yawRadians = Math.toRadians(-yaw);
        double cos = Math.cos(yawRadians);
        double sin = Math.sin(yawRadians);
        double rotatedX = (localX * cos) - (localZ * sin);
        double rotatedZ = (localX * sin) + (localZ * cos);
        return new Vector3d(playerPosition.x + rotatedX, playerPosition.y + localY, playerPosition.z + rotatedZ);
    }

    private Vector3f computeRigRotation(String rigProfile, String partId, Vector3f playerRotation, long frame) {
        Vector3f base = playerRotation == null ? new Vector3f(0F, 0F, 0F) : new Vector3f(playerRotation);
        String profile = rigProfile == null ? "" : rigProfile.trim().toLowerCase(Locale.ROOT);
        String part = partId == null ? "" : partId.trim().toLowerCase(Locale.ROOT);

        switch (profile) {
            case "fire_ice_cone" -> {
                if ("helix_fire".equals(part)) {
                    base.addYaw((float) ((frame % 360L) * 2.1D));
                } else if ("helix_ice".equals(part)) {
                    base.addYaw((float) (-(frame % 360L) * 2.1D));
                } else {
                    base.addYaw((float) (Math.sin(frame * 0.04D) * 12.0D));
                }
            }
            case "storm_clouds" -> {
                if ("sun_cloud".equals(part)) {
                    base.addYaw(180.0F + (float) (Math.sin(frame * 0.01D) * 8.0D));
                } else if ("cloud_a".equals(part) || "core".equals(part)) {
                    base.addYaw((float) ((frame % 360L) * 0.45D));
                } else if ("cloud_b".equals(part) || "ring".equals(part)) {
                    base.addYaw(120.0F - (float) ((frame % 360L) * 0.36D));
                } else if ("cloud_c".equals(part) || "bolt".equals(part)) {
                    base.addYaw(240.0F + (float) ((frame % 360L) * 0.30D));
                } else {
                    base.addYaw((float) ((frame % 360L) * 0.40D));
                }
            }
            case "wingwang_sigil" -> base.addYaw("mid".equals(part) ? 180.0F - (float) ((frame % 360L) * 1.8D) : 180.0F + (float) ((frame % 360L) * 2.0D));
            case "fireworks_show" -> base.addYaw("launcher".equals(part) ? (float) (Math.sin(frame * 0.05D) * 15.0D) : (float) ((frame % 360L) * 3.0D));
            default -> {
            }
        }

        return base;
    }
    private String normalizeAssetId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace('\\', '/');
    }

    private int clamp(int value, int min, int max, int fallback) {
        int normalized = value;
        if (normalized <= 0) {
            normalized = fallback;
        }
        if (normalized < min) {
            normalized = min;
        }
        if (normalized > max) {
            normalized = max;
        }
        return normalized;
    }

    private String stripModelExtension(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(MODEL_EXTENSION)) {
            return value.substring(0, value.length() - MODEL_EXTENSION.length());
        }
        return value;
    }

    private String stripServerModelPrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("server/models/")) {
            return normalized.substring("server/models/".length());
        }
        return normalized;
    }

    private String basename(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private enum QualityTier {
        ULTRA,
        BALANCED
    }

    public static final class DesiredRig {
        private final String categoryId;
        private final String cosmeticId;
        private final String modelAssetId;
        private final String rigProfile;

        public DesiredRig(String categoryId, String cosmeticId, String modelAssetId, String rigProfile) {
            this.categoryId = normalize(categoryId);
            this.cosmeticId = normalize(cosmeticId);
            this.modelAssetId = modelAssetId == null ? "" : modelAssetId.trim();
            this.rigProfile = normalize(rigProfile);
        }

        public String categoryId() {
            return this.categoryId;
        }

        public String cosmeticId() {
            return this.cosmeticId;
        }

        public String modelAssetId() {
            return this.modelAssetId;
        }

        public String rigProfile() {
            return this.rigProfile;
        }

        private static String normalize(String value) {
            if (value == null) {
                return "";
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static final class DebugSpawnResult {
        private final boolean success;
        private final String resolvedModelAssetId;

        private DebugSpawnResult(boolean success, String resolvedModelAssetId) {
            this.success = success;
            this.resolvedModelAssetId = resolvedModelAssetId == null ? "" : resolvedModelAssetId;
        }

        public static DebugSpawnResult success(String resolvedModelAssetId) {
            return new DebugSpawnResult(true, resolvedModelAssetId);
        }

        public static DebugSpawnResult failed(String resolvedModelAssetId) {
            return new DebugSpawnResult(false, resolvedModelAssetId);
        }

        public boolean isSuccess() {
            return this.success;
        }

        public String getResolvedModelAssetId() {
            return this.resolvedModelAssetId;
        }
    }

    public static final class AuditPart {
        private final String partId;
        private final String requestedModelAssetId;
        private final String resolvedModelAssetId;
        private final boolean resolvable;

        private AuditPart(String partId, String requestedModelAssetId, String resolvedModelAssetId, boolean resolvable) {
            this.partId = partId == null ? "" : partId;
            this.requestedModelAssetId = requestedModelAssetId == null ? "" : requestedModelAssetId;
            this.resolvedModelAssetId = resolvedModelAssetId == null ? "" : resolvedModelAssetId;
            this.resolvable = resolvable;
        }

        public String getPartId() {
            return this.partId;
        }

        public String getRequestedModelAssetId() {
            return this.requestedModelAssetId;
        }

        public String getResolvedModelAssetId() {
            return this.resolvedModelAssetId;
        }

        public boolean isResolvable() {
            return this.resolvable;
        }
    }

    private static final class DesiredPart {
        private final String categoryId;
        private final String cosmeticId;
        private final String rigProfile;
        private final String partId;
        private final String modelAssetId;

        private DesiredPart(String categoryId, String cosmeticId, String rigProfile, String partId, String modelAssetId) {
            this.categoryId = DesiredRig.normalize(categoryId);
            this.cosmeticId = DesiredRig.normalize(cosmeticId);
            this.rigProfile = DesiredRig.normalize(rigProfile);
            this.partId = DesiredRig.normalize(partId);
            this.modelAssetId = modelAssetId == null ? "" : modelAssetId.trim();
        }
    }

    private static final class RigKey {
        private final String categoryId;
        private final String cosmeticId;
        private final String partId;

        private RigKey(String categoryId, String cosmeticId, String partId) {
            this.categoryId = categoryId;
            this.cosmeticId = cosmeticId;
            this.partId = partId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RigKey that)) {
                return false;
            }
            return this.categoryId.equals(that.categoryId)
                && this.cosmeticId.equals(that.cosmeticId)
                && this.partId.equals(that.partId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.categoryId, this.cosmeticId, this.partId);
        }
    }

    private static final class RigInstance {
        private final Ref<EntityStore> entityRef;
        private final World world;
        private final String rigProfile;
        private final String partId;
        private final String requestedModelAssetId;
        private final String resolvedModelAssetId;
        private volatile long lastSeenMs;

        private RigInstance(
            Ref<EntityStore> entityRef,
            World world,
            String rigProfile,
            String partId,
            String requestedModelAssetId,
            String resolvedModelAssetId,
            long lastSeenMs
        ) {
            this.entityRef = entityRef;
            this.world = world;
            this.rigProfile = rigProfile == null ? "" : rigProfile;
            this.partId = partId == null ? "" : partId;
            this.requestedModelAssetId = requestedModelAssetId == null ? "" : requestedModelAssetId;
            this.resolvedModelAssetId = resolvedModelAssetId == null ? "" : resolvedModelAssetId;
            this.lastSeenMs = lastSeenMs;
        }
    }
}
