package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.model.PlayerState;

import java.util.UUID;

public interface PlayerStateStore {
    PlayerState load(UUID playerUuid);

    void save(UUID playerUuid, PlayerState state);

    default void flush() {
    }

    default void close() {
    }

    String describe();
}
