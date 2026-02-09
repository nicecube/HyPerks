package ca.nicecube.hyperks.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerState {
    private String locale = "";
    private Map<String, String> activeCosmetics = new ConcurrentHashMap<>();

    public static PlayerState defaults() {
        return new PlayerState();
    }

    public void normalize() {
        if (this.locale == null) {
            this.locale = "";
        }
        if (this.activeCosmetics == null) {
            this.activeCosmetics = new ConcurrentHashMap<>();
            return;
        }

        Map<String, String> normalized = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> entry : this.activeCosmetics.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String category = entry.getKey().trim().toLowerCase();
            String cosmeticId = entry.getValue().trim().toLowerCase();
            if (!category.isBlank() && !cosmeticId.isBlank()) {
                normalized.put(category, cosmeticId);
            }
        }
        this.activeCosmetics = normalized;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale == null ? "" : locale.trim();
    }

    public String getActive(String category) {
        return this.activeCosmetics.get(category);
    }

    public void setActive(String category, String cosmeticId) {
        this.activeCosmetics.put(category, cosmeticId);
    }

    public void removeActive(String category) {
        this.activeCosmetics.remove(category);
    }

    public Map<String, String> getAllActive() {
        return this.activeCosmetics;
    }
}
