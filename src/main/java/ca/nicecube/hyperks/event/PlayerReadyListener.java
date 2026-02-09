package ca.nicecube.hyperks.event;

import ca.nicecube.hyperks.service.HyPerksCoreService;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;

public class PlayerReadyListener {
    private final HyPerksCoreService coreService;

    public PlayerReadyListener(HyPerksCoreService coreService) {
        this.coreService = coreService;
    }

    public void onPlayerReady(PlayerReadyEvent event) {
        this.coreService.onPlayerReady(event);
    }
}
