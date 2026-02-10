package ca.nicecube.hyperks.event;

import ca.nicecube.hyperks.service.HyPerksCoreService;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;

public class PlayerLifecycleListener {
    private final HyPerksCoreService coreService;

    public PlayerLifecycleListener(HyPerksCoreService coreService) {
        this.coreService = coreService;
    }

    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        this.coreService.onPlayerDisconnect(event);
    }

    public void onDrainPlayerFromWorld(DrainPlayerFromWorldEvent event) {
        this.coreService.onDrainPlayerFromWorld(event);
    }

    public void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
        this.coreService.onAddPlayerToWorld(event);
    }
}
