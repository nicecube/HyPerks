package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.config.HyPerksConfig;
import ca.nicecube.hyperks.model.PlayerState;
import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateService {
    private final HytaleLogger logger;
    private final Path dataRoot;
    private final Path playersDirectory;
    private final JsonConfigStore configStore;
    private final Map<UUID, PlayerState> cache = new ConcurrentHashMap<>();
    private volatile PlayerStateStore stateStore;

    public PlayerStateService(HytaleLogger logger, Path dataRoot, Path playersDirectory, JsonConfigStore configStore) {
        this.logger = logger;
        this.dataRoot = dataRoot;
        this.playersDirectory = playersDirectory;
        this.configStore = configStore;
        this.stateStore = new JsonPlayerStateStore(this.playersDirectory, this.configStore);
    }

    public synchronized void configure(HyPerksConfig.PersistenceConfig persistenceConfig) {
        PlayerStateStore nextStore = buildStore(persistenceConfig);
        PlayerStateStore previous = this.stateStore;
        this.stateStore = nextStore;
        this.cache.clear();
        closeQuietly(previous);
        this.logger.atInfo().log("[HyPerks] Player persistence backend: %s", this.stateStore.describe());
    }

    public PlayerState get(UUID playerUuid) {
        return this.cache.computeIfAbsent(playerUuid, this::loadPlayerState);
    }

    public PlayerState refresh(UUID playerUuid) {
        PlayerState refreshed = loadPlayerState(playerUuid);
        this.cache.put(playerUuid, refreshed);
        return refreshed;
    }

    public void invalidate(UUID playerUuid) {
        this.cache.remove(playerUuid);
    }

    public void save(UUID playerUuid) {
        PlayerState state = this.cache.get(playerUuid);
        if (state == null) {
            return;
        }

        try {
            state.normalize();
            this.stateStore.save(playerUuid, state);
        } catch (Exception ex) {
            switchToJsonFallback("save", ex);
            this.stateStore.save(playerUuid, state);
        }
    }

    public void flush() {
        for (UUID uuid : this.cache.keySet()) {
            save(uuid);
        }
        this.stateStore.flush();
        this.logger.atInfo().log("[HyPerks] Saved %s player profile(s).", this.cache.size());
    }

    public synchronized void close() {
        closeQuietly(this.stateStore);
    }

    public int getCachedProfileCount() {
        return this.cache.size();
    }

    public String getStoreDescription() {
        return this.stateStore.describe();
    }

    private PlayerState loadPlayerState(UUID playerUuid) {
        try {
            return this.stateStore.load(playerUuid);
        } catch (Exception ex) {
            switchToJsonFallback("load", ex);
            return this.stateStore.load(playerUuid);
        }
    }

    private synchronized void switchToJsonFallback(String operation, Exception cause) {
        if (this.stateStore instanceof JsonPlayerStateStore) {
            return;
        }

        this.logger.atWarning().withCause(cause).log(
            "[HyPerks] SQL persistence %s failed. Falling back to JSON store.",
            operation
        );
        closeQuietly(this.stateStore);
        this.stateStore = new JsonPlayerStateStore(this.playersDirectory, this.configStore);
        this.cache.clear();
    }

    private PlayerStateStore buildStore(HyPerksConfig.PersistenceConfig persistenceConfig) {
        if (persistenceConfig == null || "json".equals(persistenceConfig.getMode())) {
            return new JsonPlayerStateStore(this.playersDirectory, this.configStore);
        }

        try {
            return SqlPlayerStateStore.create(this.logger, this.dataRoot, persistenceConfig);
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log(
                "[HyPerks] Could not initialize SQL persistence. JSON fallback will be used."
            );
            return new JsonPlayerStateStore(this.playersDirectory, this.configStore);
        }
    }

    private void closeQuietly(PlayerStateStore store) {
        if (store == null) {
            return;
        }
        try {
            store.close();
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to close persistence backend cleanly.");
        }
    }
}
