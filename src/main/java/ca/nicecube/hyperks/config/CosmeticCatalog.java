package ca.nicecube.hyperks.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CosmeticCatalog {
    private List<CosmeticDefinition> cosmetics = new ArrayList<>();

    public static CosmeticCatalog defaults() {
        CosmeticCatalog catalog = new CosmeticCatalog();
        catalog.cosmetics.add(c("ember_halo", "auras"));
        catalog.cosmetics.add(c("void_orbit", "auras"));
        catalog.cosmetics.add(c("angel_wings", "auras"));
        catalog.cosmetics.add(c("heart_bloom", "auras"));

        catalog.cosmetics.add(c("flame_steps", "footprints"));
        catalog.cosmetics.add(c("frost_steps", "footprints"));
        catalog.cosmetics.add(c("heart_steps", "footprints"));
        catalog.cosmetics.add(c("rune_steps", "footprints"));

        catalog.cosmetics.add(c("vip_gold", "floating_badges"));
        catalog.cosmetics.add(c("vip_plus_platinum", "floating_badges"));
        catalog.cosmetics.add(c("mvp_diamond", "floating_badges"));
        catalog.cosmetics.add(c("founder_crest", "floating_badges"));

        catalog.cosmetics.add(c("season1_champion", "trophy_badges"));
        catalog.cosmetics.add(c("ranked_top10", "trophy_badges"));
        catalog.cosmetics.add(c("event_winner", "trophy_badges"));
        catalog.cosmetics.add(c("beta_veteran", "trophy_badges"));
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

        this.cosmetics = normalized;
    }

    public List<CosmeticDefinition> getCosmetics() {
        return cosmetics;
    }

    private static CosmeticDefinition c(String id, String category) {
        CosmeticDefinition definition = new CosmeticDefinition(id, category);
        definition.normalize();
        return definition;
    }
}
