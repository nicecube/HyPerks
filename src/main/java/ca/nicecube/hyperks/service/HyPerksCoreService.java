package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.config.CosmeticCatalog;
import ca.nicecube.hyperks.config.CosmeticDefinition;
import ca.nicecube.hyperks.config.HyPerksConfig;
import ca.nicecube.hyperks.model.CosmeticCategory;
import ca.nicecube.hyperks.model.PlayerState;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class HyPerksCoreService {
    private final HytaleLogger logger;
    private final HyPerksPaths paths;
    private final LocalizationService localizationService;
    private final JsonConfigStore configStore;
    private final PlayerStateService playerStateService;

    private HyPerksConfig config = HyPerksConfig.defaults();
    private CosmeticCatalog catalog = CosmeticCatalog.defaults();
    private final Map<CosmeticCategory, Map<String, CosmeticDefinition>> byCategory = new EnumMap<>(CosmeticCategory.class);

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

    public void reload() {
        this.localizationService.loadOrCreateDefaults();
        this.config = this.configStore.loadOrCreate(
            this.paths.getConfigPath(),
            HyPerksConfig.class,
            HyPerksConfig::defaults,
            HyPerksConfig::normalize
        );
        this.catalog = this.configStore.loadOrCreate(
            this.paths.getCosmeticsPath(),
            CosmeticCatalog.class,
            CosmeticCatalog::defaults,
            CosmeticCatalog::normalize
        );
        rebuildLookup();
        this.logger.atInfo().log(
            "[HyPerks] Loaded %s cosmetics across %s categories.",
            this.catalog.getCosmetics().size(),
            this.byCategory.size()
        );
    }

    public void flush() {
        this.playerStateService.flush();
    }

    public void showMenu(CommandContext context) {
        send(context, "cmd.menu.title");
        send(context, "cmd.menu.line", "menu");
        send(context, "cmd.menu.line", "list [category]");
        send(context, "cmd.menu.line", "equip <category> <cosmeticId>");
        send(context, "cmd.menu.line", "unequip <category>");
        send(context, "cmd.menu.line", "active");
        send(context, "cmd.menu.line", "lang <en|fr>");
        send(context, "cmd.menu.line", "reload");
        send(context, "cmd.menu.categories", categorySummary());
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
                    "- [%s] %s (%s/%s) -> %s",
                    lockText,
                    cosmeticName,
                    cosmetic.getCategory(),
                    cosmetic.getId(),
                    cosmetic.getPermission()
                )
            );
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

        Player player = context.senderAs(Player.class);
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

        Player player = context.senderAs(Player.class);
        UUID playerUuid = context.sender().getUuid();
        PlayerState state = this.playerStateService.get(playerUuid);
        state.setLocale(locale);
        this.playerStateService.save(playerUuid);
        send(context, "cmd.lang.success", locale);
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
        PlayerState state = this.playerStateService.get(playerUuid);
        if (this.config.isAutoShowMenuHintOnJoin()) {
            player.sendMessage(Message.raw(tr(player, "join.hint")));
        }

        if (!state.getAllActive().isEmpty()) {
            player.sendMessage(Message.raw(tr(player, "join.active_loaded", state.getAllActive().size())));
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

    private void rebuildLookup() {
        this.byCategory.clear();
        for (CosmeticCategory category : CosmeticCategory.values()) {
            this.byCategory.put(category, new LinkedHashMap<>());
        }

        for (CosmeticDefinition cosmetic : this.catalog.getCosmetics()) {
            if (!cosmetic.isEnabled()) {
                continue;
            }
            CosmeticCategory category = CosmeticCategory.fromId(cosmetic.getCategory());
            if (category == null) {
                continue;
            }
            this.byCategory.get(category).put(cosmetic.getId(), cosmetic);
        }
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
            PlayerState state = this.playerStateService.get(sender.getUuid());
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
        if (sender.hasPermission(cosmetic.getPermission())) {
            return true;
        }
        if (sender.hasPermission("hyperks.cosmetic.*")) {
            return true;
        }
        return sender.hasPermission("hyperks.cosmetic." + cosmetic.getCategory() + ".*");
    }

    private UUID resolvePlayerUuid(PlayerReadyEvent event, Player player) {
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
}
