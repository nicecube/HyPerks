package ca.nicecube.hyperks.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CosmeticCatalog {
    private List<CosmeticDefinition> cosmetics = new ArrayList<>();

    public static CosmeticCatalog defaults() {
        CosmeticCatalog catalog = new CosmeticCatalog();
        catalog.cosmetics.add(c(
            "ember_halo",
            "auras",
            "Server/Particles/HyPerks/Auras/Ember_Halo.particlesystem",
            "orbit"
        ));
        catalog.cosmetics.add(c(
            "void_orbit",
            "auras",
            "Server/Particles/HyPerks/Auras/Void_Orbit.particlesystem",
            "orbit"
        ));
        catalog.cosmetics.add(c(
            "angel_wings",
            "auras",
            "Server/Particles/HyPerks/Auras/Angel_Wings.particlesystem",
            "wings"
        ));
        catalog.cosmetics.add(c(
            "heart_bloom",
            "auras",
            "Server/Particles/HyPerks/Auras/Heart_Bloom.particlesystem",
            "hearts"
        ));

        catalog.cosmetics.add(c(
            "vip_aura",
            "auras_premium",
            "Server/Particles/HyPerks/PremiumAuras/VIP_Aura.particlesystem",
            "orbit"
        ));
        catalog.cosmetics.add(c(
            "vip_plus_aura",
            "auras_premium",
            "Server/Particles/HyPerks/PremiumAuras/VIPPlus_Aura.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "mvp_aura",
            "auras_premium",
            "Server/Particles/HyPerks/PremiumAuras/MVP_Aura.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "mvp_plus_aura",
            "auras_premium",
            "Server/Particles/HyPerks/PremiumAuras/MVPPlus_Aura.particlesystem",
            "crown"
        ));

        catalog.cosmetics.add(c(
            "vip_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/VIP_Trail.particlesystem",
            "comet"
        ));
        catalog.cosmetics.add(c(
            "vip_plus_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/VIPPlus_Trail.particlesystem",
            "spark"
        ));
        catalog.cosmetics.add(c(
            "mvp_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/MVP_Trail.particlesystem",
            "spiral"
        ));
        catalog.cosmetics.add(c(
            "mvp_plus_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/MVPPlus_Trail.particlesystem",
            "supreme"
        ));
        catalog.cosmetics.add(c(
            "laser_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Laser_Trail.particlesystem",
            "laser"
        ));
        catalog.cosmetics.add(c(
            "star_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Star_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "money_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Money_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "death_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Death_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "music_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Music_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "flame_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Flame_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "lightning_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Lightning_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "clover_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Clover_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "sword_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Sword_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "crown_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Crown_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "dynamite_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Dynamite_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "bomb_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Bomb_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "c4_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/C4_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "radioactive_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Radioactive_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "heart_fire_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Heart_Fire_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "heart_broken_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Heart_Broken_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "exclamation_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Exclamation_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "question_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Question_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "spade_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Spade_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "club_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Club_Trail.particlesystem",
            "icon"
        ));
        catalog.cosmetics.add(c(
            "diamond_trail",
            "trails",
            "Server/Particles/HyPerks/Trails/Diamond_Trail.particlesystem",
            "icon"
        ));

        catalog.cosmetics.add(c(
            "flame_steps",
            "footprints",
            "Server/Particles/HyPerks/Footprints/Flame_Steps.particlesystem",
            "steps"
        ));
        catalog.cosmetics.add(c(
            "frost_steps",
            "footprints",
            "Server/Particles/HyPerks/Footprints/Frost_Steps.particlesystem",
            "steps"
        ));
        catalog.cosmetics.add(c(
            "heart_steps",
            "footprints",
            "Server/Particles/HyPerks/Footprints/Heart_Steps.particlesystem",
            "steps"
        ));
        catalog.cosmetics.add(c(
            "rune_steps",
            "footprints",
            "Server/Particles/HyPerks/Footprints/Rune_Steps.particlesystem",
            "steps"
        ));

        catalog.cosmetics.add(c(
            "vip",
            "floating_badges",
            "Server/Particles/HyPerks/RankTags/VIP_Stream.particlesystem",
            "rank_stream"
        ));
        catalog.cosmetics.add(c(
            "vip_plus",
            "floating_badges",
            "Server/Particles/HyPerks/RankTags/VIPPlus_Stream.particlesystem",
            "rank_stream"
        ));
        catalog.cosmetics.add(c(
            "mvp",
            "floating_badges",
            "Server/Particles/HyPerks/RankTags/MVP_Stream.particlesystem",
            "rank_stream"
        ));
        catalog.cosmetics.add(c(
            "mvp_plus",
            "floating_badges",
            "Server/Particles/HyPerks/RankTags/MVPPlus_Stream.particlesystem",
            "rank_stream"
        ));
        catalog.cosmetics.add(c(
            "mcqc_legacy_fleur",
            "floating_badges",
            "Server/Particles/HyPerks/Badges/MCQC_Legacy_Fleur_Badge.particlesystem",
            "badge"
        ));
        catalog.cosmetics.add(c(
            "mcqc_legacy_fleur_b",
            "floating_badges",
            "Server/Particles/HyPerks/Badges/MCQC_Legacy_Fleur_B_Badge.particlesystem",
            "badge"
        ));
        catalog.cosmetics.add(c(
            "mcqc_legacy_fleur_c",
            "floating_badges",
            "Server/Particles/HyPerks/Badges/MCQC_Legacy_Fleur_C_Badge.particlesystem",
            "badge"
        ));

        catalog.cosmetics.add(c(
            "season1_champion",
            "trophy_badges",
            "Server/Particles/HyPerks/Trophies/Season_Champion_Crown.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "ranked_top10",
            "trophy_badges",
            "Server/Particles/HyPerks/Trophies/Ranked_Top10_Crown.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "event_winner",
            "trophy_badges",
            "Server/Particles/HyPerks/Trophies/Event_Winner_Crown.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "beta_veteran",
            "trophy_badges",
            "Server/Particles/HyPerks/Trophies/Beta_Veteran_Crown.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "mcqc_legacy_trophy",
            "trophy_badges",
            "Server/Particles/HyPerks/Trophies/MCQC_Legacy_Trophy.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "mcqc_legacy_trophy_b",
            "trophy_badges",
            "Server/Particles/HyPerks/Trophies/MCQC_Legacy_Trophy_B.particlesystem",
            "crown"
        ));
        catalog.cosmetics.add(c(
            "mcqc_legacy_trophy_c",
            "trophy_badges",
            "Server/Particles/HyPerks/Trophies/MCQC_Legacy_Trophy_C.particlesystem",
            "crown"
        ));
        return catalog;
    }

    public void normalize() {
        if (this.cosmetics == null) {
            this.cosmetics = new ArrayList<>();
            return;
        }

        List<CosmeticDefinition> normalized = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (CosmeticDefinition cosmetic : this.cosmetics) {
            if (cosmetic == null || !cosmetic.normalize()) {
                continue;
            }

            String key = cosmetic.getCategory() + ":" + cosmetic.getId();
            if (unique.add(key)) {
                normalized.add(cosmetic);
            }
        }

        // Keep custom entries, but automatically inject missing defaults during upgrades.
        for (CosmeticDefinition fallback : defaults().getCosmetics()) {
            String key = fallback.getCategory() + ":" + fallback.getId();
            if (unique.add(key)) {
                normalized.add(fallback);
            }
        }

        this.cosmetics = normalized;
    }

    public List<CosmeticDefinition> getCosmetics() {
        return cosmetics;
    }

    private static CosmeticDefinition c(String id, String category, String effectId, String renderStyle) {
        CosmeticDefinition definition = new CosmeticDefinition(id, category, effectId, renderStyle);
        definition.normalize();
        return definition;
    }
}
