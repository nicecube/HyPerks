package ca.nicecube.hyperks.config;

import java.util.Locale;

public class CosmeticDefinition {
    private String id;
    private String category;
    private String permission;
    private String nameKey;
    private String effectId;
    private String renderStyle;
    private String renderBackend;
    private String modelAssetId;
    private String rigProfile;
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

    public CosmeticDefinition(
        String id,
        String category,
        String effectId,
        String renderStyle,
        String renderBackend,
        String modelAssetId,
        String rigProfile
    ) {
        this.id = id;
        this.category = category;
        this.effectId = effectId;
        this.renderStyle = renderStyle;
        this.renderBackend = renderBackend;
        this.modelAssetId = modelAssetId;
        this.rigProfile = rigProfile;
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
        this.effectId = migrateLegacyAuraEffectId(this.effectId);
        this.effectId = migrateLegacyFootprintEffectId(this.effectId);
        this.effectId = migrateLegacyFloatingBadgeEffectId(this.effectId);
        if (this.effectId.isBlank()) {
            this.effectId = defaultEffectId(this.category);
        }

        this.modelAssetId = normalizeAssetPath(this.modelAssetId);
        if (this.rigProfile == null || this.rigProfile.isBlank()) {
            this.rigProfile = defaultRigProfile(this.category, this.id);
        } else {
            this.rigProfile = this.rigProfile.trim().toLowerCase(Locale.ROOT);
        }

        if (this.renderBackend == null || this.renderBackend.isBlank()) {
            if (!this.modelAssetId.isBlank()) {
                this.renderBackend = "model3d";
            } else {
                this.renderBackend = defaultRenderBackend(this.category, this.id);
            }
        } else {
            this.renderBackend = this.renderBackend.trim().toLowerCase(Locale.ROOT);
        }

        if (!"particle".equals(this.renderBackend) && !"model3d".equals(this.renderBackend)) {
            this.renderBackend = this.modelAssetId.isBlank() ? "particle" : "model3d";
        }

        if ("model3d".equals(this.renderBackend) && this.modelAssetId.isBlank()) {
            this.modelAssetId = defaultModelAssetId(this.category, this.id);
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

    public String getRenderBackend() {
        return renderBackend;
    }

    public String getModelAssetId() {
        return modelAssetId;
    }

    public String getRigProfile() {
        return rigProfile;
    }

    public boolean isModel3dBackend() {
        return "model3d".equals(this.renderBackend);
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
            case "vip_gold" -> migrateToBadge(
                "vip",
                "Server/Particles/HyPerks/Badges/VIP_Gold_Badge.particlesystem"
            );
            case "vip_plus_platinum" -> migrateToBadge(
                "vip_plus",
                "Server/Particles/HyPerks/Badges/VIP_Platinum_Badge.particlesystem"
            );
            case "mvp_diamond" -> migrateToBadge(
                "mvp",
                "Server/Particles/HyPerks/Badges/MVP_Diamond_Badge.particlesystem"
            );
            case "founder_crest" -> migrateToBadge(
                "mvp_plus",
                "Server/Particles/HyPerks/Badges/Founder_Crest_Badge.particlesystem"
            );
            default -> false;
        };
    }

    private boolean migrateToBadge(String newId, String effect) {
        this.id = newId;
        this.effectId = effect;
        this.renderStyle = "badge";
        return true;
    }

    private String defaultEffectId(String category) {
        return switch (category) {
            case "auras" -> "Server/Particles/HyPerks/Auras/Ember_Halo.particlesystem";
            case "auras_premium" -> "Server/Particles/HyPerks/PremiumAuras/VIP_Aura.particlesystem";
            case "trails" -> "Server/Particles/HyPerks/Trails/VIP_Trail.particlesystem";
            case "footprints" -> "Server/Particles/HyPerks/Footprints/Flame_Steps.particlesystem";
            case "floating_badges" -> "Server/Particles/HyPerks/Badges/VIP_Gold_Badge.particlesystem";
            case "trophy_badges" -> "Server/Particles/HyPerks/Trophies/Season_Champion_Crown.particlesystem";
            default -> "Server/Particles/Combat/Impact/Critical/Impact_Critical.particlesystem";
        };
    }

    private String defaultRenderBackend(String category, String id) {
        if ("auras".equals(category)) {
            String modelAsset = defaultModelAssetId(category, id);
            if (!modelAsset.isBlank()) {
                return "model3d";
            }
        }
        return "particle";
    }

    private String defaultModelAssetId(String category, String id) {
        if (!"auras".equals(category) || id == null) {
            return "";
        }

        return switch (id) {
            case "fire_ice_cone" -> "Server/Models/HyPerksVFX/FireIceCone_Rig.json";
            case "storm_clouds" -> "Server/Models/HyPerksVFX/StormClouds_Rig.json";
            case "wingwang_sigil" -> "Server/Models/HyPerksVFX/WingWangSigil_Rig.json";
            case "fireworks_show" -> "Server/Models/HyPerksVFX/FireworksShow_Rig.json";
            default -> "";
        };
    }

    private String defaultRigProfile(String category, String id) {
        if (!"auras".equals(category) || id == null) {
            return "";
        }

        return switch (id) {
            case "fire_ice_cone" -> "fire_ice_cone";
            case "storm_clouds" -> "storm_clouds";
            case "wingwang_sigil" -> "wingwang_sigil";
            case "fireworks_show" -> "fireworks_show";
            default -> "";
        };
    }

    private String migrateLegacyAuraEffectId(String currentEffectId) {
        if (!"auras".equals(this.category)) {
            return currentEffectId;
        }

        String current = currentEffectId == null ? "" : currentEffectId;
        String recommended = auraEffectForId(this.id);
        if (recommended.isBlank()) {
            return current;
        }

        if (current.isBlank()) {
            return recommended;
        }

        return switch (this.id) {
            case "ember_halo" -> current.equals("Server/Particles/Combat/Impact/Misc/Fire/Impact_Fire.particlesystem")
                ? recommended
                : current;
            case "void_orbit" -> current.equals("Server/Particles/Combat/Impact/Misc/Void/VoidImpact.particlesystem")
                ? recommended
                : current;
            case "angel_wings" -> current.equals("Server/Particles/Combat/Impact/Critical/Impact_Critical.particlesystem")
                ? recommended
                : current;
            case "heart_bloom" -> current.equals("Server/Particles/Combat/Mace/Signature/Mace_Signature_Cast_End.particlesystem")
                ? recommended
                : current;
            default -> current;
        };
    }

    private String migrateLegacyFootprintEffectId(String currentEffectId) {
        if (!"footprints".equals(this.category)) {
            return currentEffectId;
        }

        String current = currentEffectId == null ? "" : currentEffectId;
        String recommended = footprintEffectForId(this.id);
        if (recommended.isBlank()) {
            return current;
        }

        if (current.isBlank()) {
            return recommended;
        }

        return switch (this.id) {
            case "flame_steps" -> current.equals("Server/Particles/Block/Lava/Block_Run_Lava.particlesystem")
                ? recommended
                : current;
            case "frost_steps" -> current.equals("Server/Particles/Block/Snow/Block_Run_Snow.particlesystem")
                ? recommended
                : current;
            case "heart_steps" -> current.equals("Server/Particles/Block/Grass/Block_Sprint_Grass.particlesystem")
                ? recommended
                : current;
            case "rune_steps" -> current.equals("Server/Particles/Block/Crystal/Block_Run_Crystal.particlesystem")
                ? recommended
                : current;
            default -> current;
        };
    }

    private String migrateLegacyFloatingBadgeEffectId(String currentEffectId) {
        if (!"floating_badges".equals(this.category)) {
            return currentEffectId;
        }

        String current = currentEffectId == null ? "" : currentEffectId;
        String recommended = floatingBadgeEffectForId(this.id);
        if (recommended.isBlank()) {
            return current;
        }

        if (current.isBlank() || current.contains("/RankTags/")) {
            this.renderStyle = "badge";
            return recommended;
        }

        if ("rank_stream".equalsIgnoreCase(this.renderStyle)) {
            this.renderStyle = "badge";
        }
        return current;
    }

    private String floatingBadgeEffectForId(String cosmeticId) {
        if (cosmeticId == null) {
            return "";
        }

        return switch (cosmeticId) {
            case "vip" -> "Server/Particles/HyPerks/Badges/VIP_Gold_Badge.particlesystem";
            case "vip_plus" -> "Server/Particles/HyPerks/Badges/VIP_Platinum_Badge.particlesystem";
            case "mvp" -> "Server/Particles/HyPerks/Badges/MVP_Diamond_Badge.particlesystem";
            case "mvp_plus" -> "Server/Particles/HyPerks/Badges/Founder_Crest_Badge.particlesystem";
            default -> "";
        };
    }

    private String footprintEffectForId(String cosmeticId) {
        if (cosmeticId == null) {
            return "";
        }

        return switch (cosmeticId) {
            case "flame_steps" -> "Server/Particles/HyPerks/Footprints/Flame_Steps.particlesystem";
            case "frost_steps" -> "Server/Particles/HyPerks/Footprints/Frost_Steps.particlesystem";
            case "heart_steps" -> "Server/Particles/HyPerks/Footprints/Heart_Steps.particlesystem";
            case "rune_steps" -> "Server/Particles/HyPerks/Footprints/Rune_Steps.particlesystem";
            default -> "";
        };
    }

    private String auraEffectForId(String cosmeticId) {
        if (cosmeticId == null) {
            return "";
        }

        return switch (cosmeticId) {
            case "ember_halo" -> "Server/Particles/HyPerks/Auras/Ember_Halo.particlesystem";
            case "void_orbit" -> "Server/Particles/HyPerks/Auras/Void_Orbit.particlesystem";
            case "angel_wings" -> "Server/Particles/HyPerks/Auras/Angel_Wings.particlesystem";
            case "heart_bloom" -> "Server/Particles/HyPerks/Auras/Heart_Bloom.particlesystem";
            case "fire_ice_cone" -> "Server/Particles/HyPerks/Auras/Fire_Ice_Cone.particlesystem";
            case "storm_clouds" -> "Server/Particles/HyPerks/Auras/Storm_Clouds.particlesystem";
            case "wingwang_sigil" -> "Server/Particles/HyPerks/Auras/WingWang_Sigil.particlesystem";
            case "fireworks_show" -> "Server/Particles/HyPerks/Auras/Fireworks_Show.particlesystem";
            default -> "";
        };
    }

    private String defaultRenderStyle(String category, String id) {
        if ("auras".equals(category)) {
            if (id.contains("cone")) {
                return "cone";
            }
            if (id.contains("storm")) {
                return "storm";
            }
            if (id.contains("sigil") || id.contains("wingwang")) {
                return "sigil";
            }
            if (id.contains("firework")) {
                return "fireworks";
            }
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
            case "floating_badges" -> "badge";
            case "trophy_badges" -> "crown";
            default -> "default";
        };
    }
}
