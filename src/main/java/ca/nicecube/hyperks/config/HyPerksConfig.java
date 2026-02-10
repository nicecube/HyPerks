package ca.nicecube.hyperks.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class HyPerksConfig {
    private String _comment = "JSON does not support // comments. Use _comment fields as documentation.";
    private String _commentLanguage = "defaultLanguage supports: en, fr";
    private String _commentWorlds = "worldWhitelist is used when allowInAllWorlds=false";
    private String _commentRuntime = "runtimeRenderIntervalMs valid range: 50..5000";
    private String _commentPersistence = "Set persistence.mode to json/sqlite/mysql";
    private String _commentModelVfx = "modelVfx controls 3D rig budget and LOD thresholds";
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
    private ModelVfxConfig modelVfx = ModelVfxConfig.defaults();
    private PersistenceConfig persistence = PersistenceConfig.defaults();
    private boolean debugMode = false;

    public static HyPerksConfig defaults() {
        return new HyPerksConfig();
    }

    public void normalize() {
        if (this._comment == null || this._comment.isBlank()) {
            this._comment = "JSON does not support // comments. Use _comment fields as documentation.";
        }
        if (this._commentLanguage == null || this._commentLanguage.isBlank()) {
            this._commentLanguage = "defaultLanguage supports: en, fr";
        }
        if (this._commentWorlds == null || this._commentWorlds.isBlank()) {
            this._commentWorlds = "worldWhitelist is used when allowInAllWorlds=false";
        }
        if (this._commentRuntime == null || this._commentRuntime.isBlank()) {
            this._commentRuntime = "runtimeRenderIntervalMs valid range: 50..5000";
        }
        if (this._commentPersistence == null || this._commentPersistence.isBlank()) {
            this._commentPersistence = "Set persistence.mode to json/sqlite/mysql";
        }
        if (this._commentModelVfx == null || this._commentModelVfx.isBlank()) {
            this._commentModelVfx = "modelVfx controls 3D rig budget and LOD thresholds";
        }

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

        if (this.modelVfx == null) {
            this.modelVfx = ModelVfxConfig.defaults();
        }
        this.modelVfx.normalize();

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

    public ModelVfxConfig getModelVfx() {
        return modelVfx;
    }

    public PersistenceConfig getPersistence() {
        return persistence;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public static class MenuConfig {
        private String _comment = "guiCommandSeed controls the default GUI command search keyword";
        private boolean guiEnabled = true;
        private boolean guiFallbackToChat = true;
        private String guiCommandSeed = "hyperks";

        public static MenuConfig defaults() {
            return new MenuConfig();
        }

        public void normalize() {
            if (this._comment == null || this._comment.isBlank()) {
                this._comment = "guiCommandSeed controls the default GUI command search keyword";
            }
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

    public static class ModelVfxConfig {
        private String _comment = "maxRigsPerPlayer range: 1..64, lodUltraMaxWorldPlayers range: 1..200, lodNearbyRadius range: 6..96, updateIntervalMs range: 50..1000";
        private int maxRigsPerPlayer = 16;
        private int lodUltraMaxWorldPlayers = 10;
        private int lodNearbyRadius = 24;
        private int updateIntervalMs = 50;

        public static ModelVfxConfig defaults() {
            return new ModelVfxConfig();
        }

        public void normalize() {
            if (this._comment == null || this._comment.isBlank()) {
                this._comment = "maxRigsPerPlayer range: 1..64, lodUltraMaxWorldPlayers range: 1..200, lodNearbyRadius range: 6..96, updateIntervalMs range: 50..1000";
            }

            if (this.maxRigsPerPlayer < 1) {
                this.maxRigsPerPlayer = 1;
            }
            if (this.maxRigsPerPlayer > 64) {
                this.maxRigsPerPlayer = 64;
            }

            if (this.lodUltraMaxWorldPlayers < 1) {
                this.lodUltraMaxWorldPlayers = 1;
            }
            if (this.lodUltraMaxWorldPlayers > 200) {
                this.lodUltraMaxWorldPlayers = 200;
            }

            if (this.lodNearbyRadius < 6) {
                this.lodNearbyRadius = 6;
            }
            if (this.lodNearbyRadius > 96) {
                this.lodNearbyRadius = 96;
            }

            if (this.updateIntervalMs < 50) {
                this.updateIntervalMs = 50;
            }
            if (this.updateIntervalMs > 1000) {
                this.updateIntervalMs = 1000;
            }
        }

        public int getMaxRigsPerPlayer() {
            return maxRigsPerPlayer;
        }

        public int getLodUltraMaxWorldPlayers() {
            return lodUltraMaxWorldPlayers;
        }

        public int getUpdateIntervalMs() {
            return updateIntervalMs;
        }

        public int getLodNearbyRadius() {
            return lodNearbyRadius;
        }

        public void setMaxRigsPerPlayer(int maxRigsPerPlayer) {
            this.maxRigsPerPlayer = maxRigsPerPlayer;
        }

        public void setLodUltraMaxWorldPlayers(int lodUltraMaxWorldPlayers) {
            this.lodUltraMaxWorldPlayers = lodUltraMaxWorldPlayers;
        }

        public void setUpdateIntervalMs(int updateIntervalMs) {
            this.updateIntervalMs = updateIntervalMs;
        }

        public void setLodNearbyRadius(int lodNearbyRadius) {
            this.lodNearbyRadius = lodNearbyRadius;
        }
    }

    public static class PersistenceConfig {
        private String _comment = "mysql: fill ip/port/databaseName/username/password, or set jdbcUrl directly";
        private String mode = "json";
        private String ip = "127.0.0.1";
        private int port = 3306;
        private String databaseName = "hyperks";
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
            if (this._comment == null || this._comment.isBlank()) {
                this._comment = "mysql: fill ip/port/databaseName/username/password, or set jdbcUrl directly";
            }

            if (this.mode == null || this.mode.isBlank()) {
                this.mode = "json";
            }
            this.mode = this.mode.trim().toLowerCase(Locale.ROOT);
            if (!this.mode.equals("json") && !this.mode.equals("sqlite") && !this.mode.equals("mysql")) {
                this.mode = "json";
            }

            if (this.ip == null || this.ip.isBlank()) {
                this.ip = "127.0.0.1";
            }
            this.ip = this.ip.trim();

            if (this.port < 1) {
                this.port = 1;
            }
            if (this.port > 65535) {
                this.port = 65535;
            }

            if (this.databaseName == null || this.databaseName.isBlank()) {
                this.databaseName = "hyperks";
            }
            this.databaseName = this.databaseName.trim().toLowerCase(Locale.ROOT);
            if (!this.databaseName.matches("[a-z0-9_\\-]+")) {
                this.databaseName = "hyperks";
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

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        public String getDatabaseName() {
            return databaseName;
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
