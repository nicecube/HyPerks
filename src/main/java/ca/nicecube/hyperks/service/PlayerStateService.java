package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.model.PlayerState;
import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateService {
    private final HytaleLogger logger;
    private final Path playersDirectory;
    private final JsonConfigStore configStore;
    private final Map<UUID, PlayerState> cache = new ConcurrentHashMap<>();

    public PlayerStateService(HytaleLogger logger, Path playersDirectory, JsonConfigStore configStore) {
        this.logger = logger;
        this.playersDirectory = playersDirectory;
        this.configStore = configStore;
    }

    public PlayerState get(UUID playerUuid) {
        return this.cache.computeIfAbsent(playerUuid, this::loadPlayerState);
    }

    public void save(UUID playerUuid) {
        PlayerState state = this.cache.get(playerUuid);
        if (state == null) {
            return;
        }
        state.normalize();
        this.configStore.save(this.fileFor(playerUuid), state);
    }

    public void flush() {
        for (UUID uuid : this.cache.keySet()) {
            save(uuid);
        }
        this.logger.atInfo().log("[HyPerks] Saved %s player profile(s).", this.cache.size());
    }

    private PlayerState loadPlayerState(UUID playerUuid) {
        return this.configStore.loadOrCreate(
            this.fileFor(playerUuid),
            PlayerState.class,
            PlayerState::defaults,
            PlayerState::normalize
        );
    }

    private Path fileFor(UUID playerUuid) {
        return this.playersDirectory.resolve(playerUuid + ".json");
    }
}
