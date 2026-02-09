package ca.nicecube.hyperks.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocalizationService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final HytaleLogger logger;
    private final Path langDirectory;
    private final Map<String, Map<String, String>> bundles = new HashMap<>();

    public LocalizationService(HytaleLogger logger, Path langDirectory) {
        this.logger = logger;
        this.langDirectory = langDirectory;
    }

    public void loadOrCreateDefaults() {
        try {
            Files.createDirectories(this.langDirectory);
            copyResourceIfMissing("lang/en.json", this.langDirectory.resolve("en.json"));
            copyResourceIfMissing("lang/fr.json", this.langDirectory.resolve("fr.json"));

            this.bundles.clear();
            Files.list(this.langDirectory)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(this::loadOneBundle);

            if (!this.bundles.containsKey("en")) {
                this.logger.atWarning().log("[HyPerks] Missing en.json lang file, fallback keys may appear.");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load language files from " + this.langDirectory, ex);
        }
    }

    public String translate(String locale, String key, Object... args) {
        String normalizedLocale = normalizeLocale(locale);
        String template = lookup(normalizedLocale, key);
        if (template == null) {
            template = lookup("en", key);
        }
        if (template == null) {
            template = key;
        }

        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    private String lookup(String locale, String key) {
        Map<String, String> bundle = this.bundles.get(locale);
        if (bundle == null) {
            return null;
        }
        return bundle.get(key);
    }

    private void loadOneBundle(Path path) {
        String fileName = path.getFileName().toString();
        String locale = fileName.substring(0, fileName.length() - ".json".length()).toLowerCase(Locale.ROOT);
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, String> values = GSON.fromJson(reader, MAP_TYPE);
            if (values == null) {
                values = Map.of();
            }
            this.bundles.put(locale, new HashMap<>(values));
        } catch (Exception ex) {
            this.logger.atWarning().withCause(ex).log("[HyPerks] Failed to load locale file %s", path);
        }
    }

    private void copyResourceIfMissing(String resourcePath, Path destination) throws IOException {
        if (Files.exists(destination)) {
            return;
        }

        try (InputStream stream = LocalizationService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled resource: " + resourcePath);
            }
            Files.copy(stream, destination);
        }
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en";
        }
        return locale.trim().toLowerCase(Locale.ROOT);
    }
}
