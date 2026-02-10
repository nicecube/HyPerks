package ca.nicecube.hyperks;

import ca.nicecube.hyperks.command.HyPerksCommand;
import ca.nicecube.hyperks.event.PlayerLifecycleListener;
import ca.nicecube.hyperks.event.PlayerReadyListener;
import ca.nicecube.hyperks.service.HyPerksCoreService;
import ca.nicecube.hyperks.service.HyPerksPaths;
import ca.nicecube.hyperks.service.JsonConfigStore;
import ca.nicecube.hyperks.service.LocalizationService;
import ca.nicecube.hyperks.service.PlayerStateService;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.nio.file.Path;

public class HyPerksPlugin extends JavaPlugin {
    private HyPerksCoreService coreService;

    public HyPerksPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Path dataDirectory = HyPerksPaths.resolveDataDirectory(this.getDataDirectory(), "HyPerks");
        HyPerksPaths paths = HyPerksPaths.fromRoot(dataDirectory);

        JsonConfigStore configStore = new JsonConfigStore(this.getLogger());
        LocalizationService localization = new LocalizationService(this.getLogger(), paths.getLangDirectory());
        PlayerStateService playerStateService = new PlayerStateService(
            this.getLogger(),
            paths.getRootDirectory(),
            paths.getPlayersDirectory(),
            configStore
        );

        this.coreService = new HyPerksCoreService(
            this.getLogger(),
            paths,
            localization,
            configStore,
            playerStateService
        );
        this.coreService.reload();

        this.getCommandRegistry().registerCommand(new HyPerksCommand(this.coreService));
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, new PlayerReadyListener(this.coreService)::onPlayerReady);
        PlayerLifecycleListener lifecycleListener = new PlayerLifecycleListener(this.coreService);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, lifecycleListener::onPlayerDisconnect);
        this.getEventRegistry().registerGlobal(DrainPlayerFromWorldEvent.class, lifecycleListener::onDrainPlayerFromWorld);
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, lifecycleListener::onAddPlayerToWorld);

        this.getLogger().atInfo().log("[%s] Enabled. Data folder: %s", this.getName(), dataDirectory.toAbsolutePath());
    }

    @Override
    protected void start() {
        if (this.coreService != null) {
            this.coreService.startRuntime();
        }
        this.getLogger().atInfo().log("[%s] Runtime started.", this.getName());
    }

    @Override
    protected void shutdown() {
        if (this.coreService != null) {
            this.coreService.stopRuntime();
            this.coreService.flush();
            this.coreService.close();
        }
        this.getLogger().atInfo().log("[%s] Disabled.", this.getName());
    }
}
