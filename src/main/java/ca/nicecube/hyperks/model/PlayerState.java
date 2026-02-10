package ca.nicecube.hyperks.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerState {
    private String locale = "";
    private Map<String, String> activeCosmetics = new ConcurrentHashMap<>();
    private Map<String, List<String>> activeCosmeticsMulti = new ConcurrentHashMap<>();

    public static PlayerState defaults() {
        return new PlayerState();
    }

    public void normalize() {
        if (this.locale == null) {
            this.locale = "";
        }

        Map<String, List<String>> normalizedMulti = new ConcurrentHashMap<>();

        if (this.activeCosmetics != null) {
            for (Map.Entry<String, String> entry : this.activeCosmetics.entrySet()) {
                String category = normalizeValue(entry.getKey());
                String cosmeticId = normalizeValue(entry.getValue());
                if (category.isBlank() || cosmeticId.isBlank()) {
                    continue;
                }
                addUnique(normalizedMulti, category, cosmeticId);
            }
        }

        if (this.activeCosmeticsMulti != null) {
            for (Map.Entry<String, List<String>> entry : this.activeCosmeticsMulti.entrySet()) {
                String category = normalizeValue(entry.getKey());
                if (category.isBlank() || entry.getValue() == null) {
                    continue;
                }
                for (String rawId : entry.getValue()) {
                    String cosmeticId = normalizeValue(rawId);
                    if (!cosmeticId.isBlank()) {
                        addUnique(normalizedMulti, category, cosmeticId);
                    }
                }
            }
        }

        this.activeCosmeticsMulti = normalizedMulti;
        this.activeCosmetics = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<String>> entry : normalizedMulti.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                this.activeCosmetics.put(entry.getKey(), entry.getValue().get(0));
            }
        }
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale == null ? "" : locale.trim();
    }

    public String getActive(String category) {
        String normalizedCategory = normalizeValue(category);
        if (normalizedCategory.isBlank()) {
            return null;
        }

        List<String> activeList = this.activeCosmeticsMulti.get(normalizedCategory);
        if (activeList != null && !activeList.isEmpty()) {
            return activeList.get(0);
        }

        return this.activeCosmetics.get(normalizedCategory);
    }

    public void setActive(String category, String cosmeticId) {
        String normalizedCategory = normalizeValue(category);
        String normalizedCosmeticId = normalizeValue(cosmeticId);
        if (normalizedCategory.isBlank() || normalizedCosmeticId.isBlank()) {
            return;
        }

        List<String> single = new ArrayList<>();
        single.add(normalizedCosmeticId);
        this.activeCosmeticsMulti.put(normalizedCategory, single);
        this.activeCosmetics.put(normalizedCategory, normalizedCosmeticId);
    }

    public void removeActive(String category) {
        String normalizedCategory = normalizeValue(category);
        if (normalizedCategory.isBlank()) {
            return;
        }

        this.activeCosmetics.remove(normalizedCategory);
        this.activeCosmeticsMulti.remove(normalizedCategory);
    }

    public Map<String, String> getAllActive() {
        return this.activeCosmetics;
    }

    public List<String> getActiveList(String category) {
        String normalizedCategory = normalizeValue(category);
        if (normalizedCategory.isBlank()) {
            return List.of();
        }
        List<String> list = this.activeCosmeticsMulti.get(normalizedCategory);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public void setActiveList(String category, List<String> cosmeticIds) {
        String normalizedCategory = normalizeValue(category);
        if (normalizedCategory.isBlank()) {
            return;
        }

        if (cosmeticIds == null || cosmeticIds.isEmpty()) {
            removeActive(normalizedCategory);
            return;
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String cosmeticId : cosmeticIds) {
            String normalizedCosmeticId = normalizeValue(cosmeticId);
            if (!normalizedCosmeticId.isBlank()) {
                unique.add(normalizedCosmeticId);
            }
        }

        if (unique.isEmpty()) {
            removeActive(normalizedCategory);
            return;
        }

        List<String> normalizedList = new ArrayList<>(unique);
        this.activeCosmeticsMulti.put(normalizedCategory, normalizedList);
        this.activeCosmetics.put(normalizedCategory, normalizedList.get(0));
    }

    public void addActive(String category, String cosmeticId) {
        String normalizedCategory = normalizeValue(category);
        String normalizedCosmeticId = normalizeValue(cosmeticId);
        if (normalizedCategory.isBlank() || normalizedCosmeticId.isBlank()) {
            return;
        }

        List<String> list = new ArrayList<>(getActiveList(normalizedCategory));
        if (!list.contains(normalizedCosmeticId)) {
            list.add(normalizedCosmeticId);
        }
        setActiveList(normalizedCategory, list);
    }

    public void removeActive(String category, String cosmeticId) {
        String normalizedCategory = normalizeValue(category);
        String normalizedCosmeticId = normalizeValue(cosmeticId);
        if (normalizedCategory.isBlank() || normalizedCosmeticId.isBlank()) {
            return;
        }

        List<String> list = new ArrayList<>(getActiveList(normalizedCategory));
        list.removeIf(id -> id.equals(normalizedCosmeticId));
        if (list.isEmpty()) {
            removeActive(normalizedCategory);
            return;
        }
        setActiveList(normalizedCategory, list);
    }

    public boolean isActive(String category, String cosmeticId) {
        String normalizedCategory = normalizeValue(category);
        String normalizedCosmeticId = normalizeValue(cosmeticId);
        if (normalizedCategory.isBlank() || normalizedCosmeticId.isBlank()) {
            return false;
        }

        List<String> list = this.activeCosmeticsMulti.get(normalizedCategory);
        if (list == null || list.isEmpty()) {
            return false;
        }
        return list.contains(normalizedCosmeticId);
    }

    public Map<String, List<String>> getAllActiveMulti() {
        return this.activeCosmeticsMulti;
    }

    public Map<String, Object> getStorageActiveCosmetics() {
        normalize();

        Map<String, Object> payload = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<String>> entry : this.activeCosmeticsMulti.entrySet()) {
            List<String> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            if (list.size() == 1) {
                payload.put(entry.getKey(), list.get(0));
            } else {
                payload.put(entry.getKey(), new ArrayList<>(list));
            }
        }
        return payload;
    }

    private static void addUnique(Map<String, List<String>> target, String category, String cosmeticId) {
        List<String> list = target.computeIfAbsent(category, ignored -> new ArrayList<>());
        if (!list.contains(cosmeticId)) {
            list.add(cosmeticId);
        }
    }

    private static String normalizeValue(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase();
    }
}
