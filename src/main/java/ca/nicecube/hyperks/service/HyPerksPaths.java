package ca.nicecube.hyperks.service;

import java.nio.file.Path;

public class HyPerksPaths {
    private final Path rootDirectory;
    private final Path configPath;
    private final Path cosmeticsPath;
    private final Path langDirectory;
    private final Path playersDirectory;

    private HyPerksPaths(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.configPath = rootDirectory.resolve("config.json");
        this.cosmeticsPath = rootDirectory.resolve("cosmetics.json");
        this.langDirectory = rootDirectory.resolve("lang");
        this.playersDirectory = rootDirectory.resolve("players");
    }

    public static HyPerksPaths fromRoot(Path rootDirectory) {
        return new HyPerksPaths(rootDirectory);
    }

    public static Path resolveDataDirectory(Path suggestedDirectory, String fixedName) {
        Path parent = suggestedDirectory.getParent();
        if (parent == null) {
            return suggestedDirectory;
        }
        return parent.resolve(fixedName);
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public Path getCosmeticsPath() {
        return cosmeticsPath;
    }

    public Path getLangDirectory() {
        return langDirectory;
    }

    public Path getPlayersDirectory() {
        return playersDirectory;
    }
}
