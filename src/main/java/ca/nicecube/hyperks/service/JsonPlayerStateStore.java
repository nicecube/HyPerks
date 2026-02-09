package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.model.PlayerState;

import java.nio.file.Path;
import java.util.UUID;

public class JsonPlayerStateStore implements PlayerStateStore {
    private final Path playersDirectory;
    private final JsonConfigStore configStore;

    public JsonPlayerStateStore(Path playersDirectory, JsonConfigStore configStore) {
        this.playersDirectory = playersDirectory;
        this.configStore = configStore;
    }

    @Override
    public PlayerState load(UUID playerUuid) {
        return this.configStore.loadOrCreate(
            fileFor(playerUuid),
            PlayerState.class,
            PlayerState::defaults,
            PlayerState::normalize
        );
    }

    @Override
    public void save(UUID playerUuid, PlayerState state) {
        state.normalize();
        this.configStore.save(fileFor(playerUuid), state);
    }

    @Override
    public String describe() {
        return "json:" + this.playersDirectory.toAbsolutePath();
    }

    private Path fileFor(UUID playerUuid) {
        return this.playersDirectory.resolve(playerUuid + ".json");
    }
}
