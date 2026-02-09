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
            "Server/Particles/Combat/Impact/Misc/Fire/Impact_Fire.particlesystem",
            "orbit"
        ));
        catalog.cosmetics.add(c(
            "void_orbit",
            "auras",
            "Server/Particles/Combat/Impact/Misc/Void/VoidImpact.particlesystem",
            "orbit"
        ));
        catalog.cosmetics.add(c(
            "angel_wings",
            "auras",
            "Server/Particles/Combat/Impact/Critical/Impact_Critical.particlesystem",
            "wings"
        ));
        catalog.cosmetics.add(c(
            "heart_bloom",
            "auras",
            "Server/Particles/Combat/Mace/Signature/Mace_Signature_Cast_End.particlesystem",
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
            "pillar"
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
            "stream"
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
            "spiral"
        ));

        catalog.cosmetics.add(c(
            "flame_steps",
            "footprints",
            "Server/Particles/Block/Lava/Block_Run_Lava.particlesystem",
            "steps"
        ));
        catalog.cosmetics.add(c(
            "frost_steps",
            "footprints",
            "Server/Particles/Block/Snow/Block_Run_Snow.particlesystem",
            "steps"
        ));
        catalog.cosmetics.add(c(
            "heart_steps",
            "footprints",
            "Server/Particles/Block/Grass/Block_Sprint_Grass.particlesystem",
            "steps"
        ));
        catalog.cosmetics.add(c(
            "rune_steps",
            "footprints",
            "Server/Particles/Block/Crystal/Block_Run_Crystal.particlesystem",
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
