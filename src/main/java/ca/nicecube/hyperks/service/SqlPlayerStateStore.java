package ca.nicecube.hyperks.service;

import ca.nicecube.hyperks.config.HyPerksConfig;
import ca.nicecube.hyperks.model.PlayerState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class SqlPlayerStateStore implements PlayerStateStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final HytaleLogger logger;
    private final String mode;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final int connectTimeoutSeconds;
    private final boolean autoCreateTable;

    private SqlPlayerStateStore(
        HytaleLogger logger,
        String mode,
        String jdbcUrl,
        String username,
        String password,
        String tableName,
        int connectTimeoutSeconds,
        boolean autoCreateTable
    ) {
        this.logger = logger;
        this.mode = mode;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.autoCreateTable = autoCreateTable;
    }

    public static SqlPlayerStateStore create(
        HytaleLogger logger,
        Path dataRoot,
        HyPerksConfig.PersistenceConfig config
    ) {
        String mode = config.getMode();
        String tableName = sanitizeTableName(config.getTableName());
        int timeoutSeconds = Math.max(1, config.getConnectTimeoutMs() / 1000);
        String jdbcUrl = resolveJdbcUrl(mode, dataRoot, config);

        SqlPlayerStateStore store = new SqlPlayerStateStore(
            logger,
            mode,
            jdbcUrl,
            config.getUsername(),
            config.getPassword(),
            tableName,
            timeoutSeconds,
            config.isAutoCreateTable()
        );
        store.initialize();
        return store;
    }

    @Override
    public PlayerState load(UUID playerUuid) {
        PlayerState state = PlayerState.defaults();
        String sql = "SELECT locale, active_cosmetics FROM " + this.tableName + " WHERE player_uuid = ?";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    state.normalize();
                    return state;
                }

                state.setLocale(resultSet.getString("locale"));
                applyActiveCosmetics(resultSet.getString("active_cosmetics"), state);
                state.normalize();
                return state;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load player state for " + playerUuid + " from SQL store.", ex);
        }
    }

    @Override
    public void save(UUID playerUuid, PlayerState state) {
        state.normalize();
        String activeJson = GSON.toJson(state.getStorageActiveCosmetics());
        long updatedAt = Instant.now().toEpochMilli();

        String sql = upsertStatement();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, state.getLocale());
            statement.setString(3, activeJson);
            statement.setLong(4, updatedAt);

            if (isMysql()) {
                statement.setString(5, state.getLocale());
                statement.setString(6, activeJson);
                statement.setLong(7, updatedAt);
            }

            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save player state for " + playerUuid + " to SQL store.", ex);
        }
    }

    @Override
    public String describe() {
        return this.mode + ":" + this.jdbcUrl + " (table=" + this.tableName + ")";
    }

    private void initialize() {
        loadDriver();
        if (!this.autoCreateTable) {
            this.logger.atInfo().log("[HyPerks] SQL persistence enabled without auto table creation.");
            return;
        }

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                "CREATE TABLE IF NOT EXISTS " + this.tableName + " (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY, " +
                    "locale VARCHAR(16) NOT NULL, " +
                    "active_cosmetics TEXT NOT NULL, " +
                    "updated_at BIGINT NOT NULL" +
                    ")"
            );
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not create SQL table '" + this.tableName + "'.", ex);
        }
    }

    private void loadDriver() {
        try {
            if (isMysql()) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } else {
                Class.forName("org.sqlite.JDBC");
            }
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(
                "Missing JDBC driver for mode '" + this.mode + "'. Add runtime dependency to HyPerks build.",
                ex
            );
        }
    }

    private Connection openConnection() throws SQLException {
        DriverManager.setLoginTimeout(this.connectTimeoutSeconds);
        if (this.username.isBlank()) {
            return DriverManager.getConnection(this.jdbcUrl);
        }
        return DriverManager.getConnection(this.jdbcUrl, this.username, this.password);
    }

    private boolean isMysql() {
        return "mysql".equals(this.mode);
    }

    private String upsertStatement() {
        if (isMysql()) {
            return "INSERT INTO " + this.tableName + " (player_uuid, locale, active_cosmetics, updated_at) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE locale = ?, active_cosmetics = ?, updated_at = ?";
        }

        return "INSERT INTO " + this.tableName + " (player_uuid, locale, active_cosmetics, updated_at) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT(player_uuid) DO UPDATE SET " +
            "locale = excluded.locale, " +
            "active_cosmetics = excluded.active_cosmetics, " +
            "updated_at = excluded.updated_at";
    }

    private static String resolveJdbcUrl(
        String mode,
        Path dataRoot,
        HyPerksConfig.PersistenceConfig config
    ) {
        String configuredJdbcUrl = config.getJdbcUrl();
        if (configuredJdbcUrl != null && !configuredJdbcUrl.isBlank()) {
            return configuredJdbcUrl.trim();
        }

        if ("sqlite".equals(mode)) {
            Path sqliteFile = dataRoot.resolve("player-state.db");
            return "jdbc:sqlite:" + sqliteFile.toAbsolutePath();
        }

        if ("mysql".equals(mode)) {
            return String.format(
                Locale.ROOT,
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                config.getIp(),
                config.getPort(),
                config.getDatabaseName()
            );
        }

        throw new IllegalStateException(
            "Could not resolve JDBC URL for mode '" + mode + "'."
        );
    }

    private static String sanitizeTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "hyperks_player_state";
        }
        String normalized = tableName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (normalized.isBlank()) {
            return "hyperks_player_state";
        }
        return normalized;
    }

    private void applyActiveCosmetics(String rawJson, PlayerState state) {
        if (rawJson == null || rawJson.isBlank()) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(rawJson);
            if (!root.isJsonObject()) {
                return;
            }

            JsonObject object = root.getAsJsonObject();
            for (String categoryId : object.keySet()) {
                JsonElement value = object.get(categoryId);
                if (value == null || value.isJsonNull()) {
                    continue;
                }

                if (value.isJsonArray()) {
                    List<String> cosmetics = new ArrayList<>();
                    for (JsonElement arrayEntry : value.getAsJsonArray()) {
                        if (arrayEntry == null || arrayEntry.isJsonNull() || !arrayEntry.isJsonPrimitive()) {
                            continue;
                        }
                        cosmetics.add(arrayEntry.getAsString());
                    }
                    state.setActiveList(categoryId, cosmetics);
                    continue;
                }

                if (value.isJsonPrimitive()) {
                    state.setActive(categoryId, value.getAsString());
                }
            }
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Invalid active_cosmetics JSON in SQL row.");
        }
    }
}
