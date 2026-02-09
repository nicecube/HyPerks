package ca.nicecube.hyperks.config;

import java.util.Locale;

public class CosmeticDefinition {
    private String id;
    private String category;
    private String permission;
    private String nameKey;
    private boolean enabled = true;

    public CosmeticDefinition() {
    }

    public CosmeticDefinition(String id, String category) {
        this.id = id;
        this.category = category;
    }

    public boolean normalize() {
        if (this.id == null || this.category == null) {
            return false;
        }

        this.id = this.id.trim().toLowerCase(Locale.ROOT);
        this.category = this.category.trim().toLowerCase(Locale.ROOT);

        if (this.id.isBlank() || this.category.isBlank()) {
            return false;
        }

        if (this.permission == null || this.permission.isBlank()) {
            this.permission = "hyperks.cosmetic." + this.category + "." + this.id;
        }

        if (this.nameKey == null || this.nameKey.isBlank()) {
            this.nameKey = "cosmetic." + this.category + "." + this.id + ".name";
        }

        return true;
    }

    public String getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public String getPermission() {
        return permission;
    }

    public String getNameKey() {
        return nameKey;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
