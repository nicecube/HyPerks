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
    private boolean runtimeRenderingEnabled = true;
    private int runtimeRenderIntervalMs = 250;
    private int commandCooldownMs = 1200;
    private int permissionCacheTtlMs = 1500;
    private boolean detailedCosmeticDescriptions = true;
    private MenuConfig menu = MenuConfig.defaults();
    private PersistenceConfig persistence = PersistenceConfig.defaults();
    private boolean debugMode = false;

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

        if (this.runtimeRenderIntervalMs < 50) {
            this.runtimeRenderIntervalMs = 50;
        }
        if (this.runtimeRenderIntervalMs > 5000) {
            this.runtimeRenderIntervalMs = 5000;
        }

        if (this.commandCooldownMs < 0) {
            this.commandCooldownMs = 0;
        }
        if (this.commandCooldownMs > 30_000) {
            this.commandCooldownMs = 30_000;
        }

        if (this.permissionCacheTtlMs < 0) {
            this.permissionCacheTtlMs = 0;
        }
        if (this.permissionCacheTtlMs > 60_000) {
            this.permissionCacheTtlMs = 60_000;
        }

        if (this.menu == null) {
            this.menu = MenuConfig.defaults();
        }
        this.menu.normalize();

        if (this.persistence == null) {
            this.persistence = PersistenceConfig.defaults();
        }
        this.persistence.normalize();
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

    public boolean isRuntimeRenderingEnabled() {
        return runtimeRenderingEnabled;
    }

    public int getRuntimeRenderIntervalMs() {
        return runtimeRenderIntervalMs;
    }

    public int getCommandCooldownMs() {
        return commandCooldownMs;
    }

    public int getPermissionCacheTtlMs() {
        return permissionCacheTtlMs;
    }

    public boolean isDetailedCosmeticDescriptions() {
        return detailedCosmeticDescriptions;
    }

    public MenuConfig getMenu() {
        return menu;
    }

    public PersistenceConfig getPersistence() {
        return persistence;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public static class MenuConfig {
        private boolean guiEnabled = true;
        private boolean guiFallbackToChat = true;
        private String guiCommandSeed = "hyperks";

        public static MenuConfig defaults() {
            return new MenuConfig();
        }

        public void normalize() {
            if (this.guiCommandSeed == null || this.guiCommandSeed.isBlank()) {
                this.guiCommandSeed = "hyperks";
            }
            this.guiCommandSeed = this.guiCommandSeed.trim();
        }

        public boolean isGuiEnabled() {
            return guiEnabled;
        }

        public boolean isGuiFallbackToChat() {
            return guiFallbackToChat;
        }

        public String getGuiCommandSeed() {
            return guiCommandSeed;
        }
    }

    public static class PersistenceConfig {
        private String mode = "json";
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";
        private String tableName = "hyperks_player_state";
        private int connectTimeoutMs = 5000;
        private boolean autoCreateTable = true;

        public static PersistenceConfig defaults() {
            return new PersistenceConfig();
        }

        public void normalize() {
            if (this.mode == null || this.mode.isBlank()) {
                this.mode = "json";
            }
            this.mode = this.mode.trim().toLowerCase(Locale.ROOT);
            if (!this.mode.equals("json") && !this.mode.equals("sqlite") && !this.mode.equals("mysql")) {
                this.mode = "json";
            }

            if (this.jdbcUrl == null) {
                this.jdbcUrl = "";
            }
            this.jdbcUrl = this.jdbcUrl.trim();

            if (this.username == null) {
                this.username = "";
            }
            if (this.password == null) {
                this.password = "";
            }

            if (this.tableName == null || this.tableName.isBlank()) {
                this.tableName = "hyperks_player_state";
            }
            this.tableName = this.tableName.trim().toLowerCase(Locale.ROOT);

            if (this.connectTimeoutMs < 1000) {
                this.connectTimeoutMs = 1000;
            }
            if (this.connectTimeoutMs > 60_000) {
                this.connectTimeoutMs = 60_000;
            }
        }

        public String getMode() {
            return mode;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getTableName() {
            return tableName;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public boolean isAutoCreateTable() {
            return autoCreateTable;
        }
    }
}
