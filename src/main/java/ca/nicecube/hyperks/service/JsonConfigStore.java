package ca.nicecube.hyperks.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class JsonConfigStore {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final DateTimeFormatter BACKUP_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final HytaleLogger logger;

    public JsonConfigStore(HytaleLogger logger) {
        this.logger = logger;
    }

    public <T> T loadOrCreate(Path path, Class<T> type, Supplier<T> defaultSupplier, Consumer<T> normalizer) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            if (Files.notExists(path)) {
                T defaults = defaultSupplier.get();
                normalizer.accept(defaults);
                save(path, defaults);
                return defaults;
            }

            T loaded;
            try (Reader reader = Files.newBufferedReader(path)) {
                loaded = GSON.fromJson(reader, type);
            }

            if (loaded == null) {
                T defaults = defaultSupplier.get();
                normalizer.accept(defaults);
                save(path, defaults);
                return defaults;
            }

            normalizer.accept(loaded);
            save(path, loaded);
            return loaded;
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to read %s, restoring defaults.", path);
            backupBroken(path);
            T defaults = defaultSupplier.get();
            normalizer.accept(defaults);
            save(path, defaults);
            return defaults;
        }
    }

    public void save(Path path, Object value) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(value, writer);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write JSON file: " + path, ex);
        }
    }

    private void backupBroken(Path path) {
        if (Files.notExists(path)) {
            return;
        }

        try {
            String suffix = LocalDateTime.now().format(BACKUP_SUFFIX);
            Path backupPath = path.resolveSibling(path.getFileName() + ".broken-" + suffix);
            Files.move(path, backupPath);
            this.logger.atWarning().log("[HyPerks] Broken file backed up to %s", backupPath);
        } catch (IOException ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to backup broken file %s", path);
        }
    }
}
