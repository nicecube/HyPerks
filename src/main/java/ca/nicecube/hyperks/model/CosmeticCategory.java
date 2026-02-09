package ca.nicecube.hyperks.model;

import java.util.Locale;

public enum CosmeticCategory {
    AURAS("auras"),
    AURAS_PREMIUM("auras_premium"),
    TRAILS("trails"),
    FOOTPRINTS("footprints"),
    FLOATING_BADGES("floating_badges"),
    TROPHY_BADGES("trophy_badges");

    private final String id;

    CosmeticCategory(String id) {
        this.id = id;
    }

    public String getId() {
        return this.id;
    }

    public static CosmeticCategory fromId(String id) {
        if (id == null) {
            return null;
        }

        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (CosmeticCategory category : values()) {
            if (category.id.equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
