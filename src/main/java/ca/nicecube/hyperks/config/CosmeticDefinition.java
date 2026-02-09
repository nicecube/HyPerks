package ca.nicecube.hyperks.config;

import java.util.Locale;

public class CosmeticDefinition {
    private String id;
    private String category;
    private String permission;
    private String nameKey;
    private String effectId;
    private String renderStyle;
    private boolean enabled = true;

    public CosmeticDefinition() {
    }

    public CosmeticDefinition(String id, String category) {
        this.id = id;
        this.category = category;
    }

    public CosmeticDefinition(String id, String category, String effectId, String renderStyle) {
        this.id = id;
        this.category = category;
        this.effectId = effectId;
        this.renderStyle = renderStyle;
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

        boolean migrated = applyLegacyMappings();
        if (migrated) {
            this.permission = "";
            this.nameKey = "";
        }

        if (this.permission == null || this.permission.isBlank()) {
            this.permission = "hyperks.cosmetic." + this.category + "." + this.id;
        }

        if (this.nameKey == null || this.nameKey.isBlank()) {
            this.nameKey = "cosmetic." + this.category + "." + this.id + ".name";
        }

        this.effectId = normalizeAssetPath(this.effectId);
        if (this.effectId.isBlank()) {
            this.effectId = defaultEffectId(this.category);
        }

        if (this.renderStyle == null || this.renderStyle.isBlank()) {
            this.renderStyle = defaultRenderStyle(this.category, this.id);
        } else {
            this.renderStyle = this.renderStyle.trim().toLowerCase(Locale.ROOT);
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

    public String getEffectId() {
        return effectId;
    }

    public String getRenderStyle() {
        return renderStyle;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private String normalizeAssetPath(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace('\\', '/');
    }

    private boolean applyLegacyMappings() {
        if (!"floating_badges".equals(this.category)) {
            return false;
        }

        return switch (this.id) {
            case "vip_gold" -> migrateToRankTag(
                "vip",
                "Server/Particles/HyPerks/RankTags/VIP_Stream.particlesystem"
            );
            case "vip_plus_platinum" -> migrateToRankTag(
                "vip_plus",
                "Server/Particles/HyPerks/RankTags/VIPPlus_Stream.particlesystem"
            );
            case "mvp_diamond" -> migrateToRankTag(
                "mvp",
                "Server/Particles/HyPerks/RankTags/MVP_Stream.particlesystem"
            );
            case "founder_crest" -> migrateToRankTag(
                "mvp_plus",
                "Server/Particles/HyPerks/RankTags/MVPPlus_Stream.particlesystem"
            );
            default -> false;
        };
    }

    private boolean migrateToRankTag(String newId, String effect) {
        this.id = newId;
        this.effectId = effect;
        this.renderStyle = "rank_stream";
        return true;
    }

    private String defaultEffectId(String category) {
        return switch (category) {
            case "auras" -> "Server/Particles/Combat/Impact/Misc/Fire/Impact_Fire.particlesystem";
            case "auras_premium" -> "Server/Particles/HyPerks/PremiumAuras/VIP_Aura.particlesystem";
            case "trails" -> "Server/Particles/HyPerks/Trails/VIP_Trail.particlesystem";
            case "footprints" -> "Server/Particles/Block/Sand/Block_Run_Sand.particlesystem";
            case "floating_badges" -> "Server/Particles/HyPerks/RankTags/VIP_Stream.particlesystem";
            case "trophy_badges" -> "Server/Particles/HyPerks/Trophies/Season_Champion_Crown.particlesystem";
            default -> "Server/Particles/Combat/Impact/Critical/Impact_Critical.particlesystem";
        };
    }

    private String defaultRenderStyle(String category, String id) {
        if ("auras".equals(category)) {
            if (id.contains("wing")) {
                return "wings";
            }
            if (id.contains("heart")) {
                return "hearts";
            }
            return "orbit";
        }

        return switch (category) {
            case "auras_premium" -> "crown";
            case "trails" -> "stream";
            case "footprints" -> "steps";
            case "floating_badges" -> "rank_stream";
            case "trophy_badges" -> "crown";
            default -> "default";
        };
    }
}
