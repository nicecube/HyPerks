package ca.nicecube.hyperks.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class PlayerState {
    private String locale = "";
    private Map<String, String> activeCosmetics = new LinkedHashMap<>();

    public static PlayerState defaults() {
        return new PlayerState();
    }

    public void normalize() {
        if (this.locale == null) {
            this.locale = "";
        }
        if (this.activeCosmetics == null) {
            this.activeCosmetics = new LinkedHashMap<>();
        }
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
