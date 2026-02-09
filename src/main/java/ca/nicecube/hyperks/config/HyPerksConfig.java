package ca.nicecube.hyperks.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class HyPerksConfig {
    private String defaultLanguage = "en";
    private List<String> worldWhitelist = new ArrayList<>(List.of("default"));
    private boolean allowInAllWorlds = false;
    private boolean autoShowMenuHintOnJoin = true;

    public static HyPerksConfig defaults() {
        return new HyPerksConfig();
    }

    public void normalize() {
        if (this.defaultLanguage == null || this.defaultLanguage.isBlank()) {
            this.defaultLanguage = "en";
        }
        this.defaultLanguage = this.defaultLanguage.trim().toLowerCase(Locale.ROOT);

        if (this.worldWhitelist == null) {
            this.worldWhitelist = new ArrayList<>();
        }

        LinkedHashSet<String> normalizedWorlds = new LinkedHashSet<>();
        for (String world : this.worldWhitelist) {
            if (world == null) {
                continue;
            }
            String normalized = world.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                normalizedWorlds.add(normalized);
            }
        }
        this.worldWhitelist = new ArrayList<>(normalizedWorlds);
    }

    public boolean isWorldAllowed(String worldName) {
        if (this.allowInAllWorlds) {
            return true;
        }

        if (worldName == null) {
            return false;
        }

        if (this.worldWhitelist.isEmpty()) {
            return true;
        }

        return this.worldWhitelist.contains(worldName.trim().toLowerCase(Locale.ROOT));
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public List<String> getWorldWhitelist() {
        return worldWhitelist;
    }

    public boolean isAllowInAllWorlds() {
        return allowInAllWorlds;
    }

    public boolean isAutoShowMenuHintOnJoin() {
        return autoShowMenuHintOnJoin;
    }
}
