package ca.nicecube.hyperks.ui;

import ca.nicecube.hyperks.model.CosmeticCategory;
import ca.nicecube.hyperks.service.HyPerksCoreService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;

public final class HyPerksMenuPage extends InteractiveCustomUIPage<HyPerksMenuPage.MenuEventData> {
    private static final String PAGE_UI_FILE = "Pages/HyPerksMenuPage.ui";
    private static final String TAB_UI_FILE = "Pages/HyPerksMenuTabButton.ui";
    private static final String ENTRY_UI_FILE = "Pages/HyPerksMenuEntryButton.ui";
    private static final String TAB_ROOT = "#TabList";
    private static final String LIST_ROOT = "#EntryList";
    private static final String ACTIVE_EFFECT_LABEL = "#ActiveEffect";
    private static final String ACTIVE_COUNT_LABEL = "#ActiveCount";
    private static final String HINT_LABEL = "#HintLine";

    private final HyPerksCoreService coreService;
    private String selectedCategoryId = CosmeticCategory.AURAS.getId();

    public HyPerksMenuPage(PlayerRef playerRef, HyPerksCoreService coreService) {
        super(playerRef, CustomPageLifetime.CanDismiss, MenuEventData.CODEC);
        this.coreService = coreService;
    }

    @Override
    public void build(
        Ref<EntityStore> playerReference,
        UICommandBuilder commands,
        UIEventBuilder events,
        Store<EntityStore> store
    ) {
        commands.append(PAGE_UI_FILE);
        buildMenu(playerReference, store, commands, events);
    }

    @Override
    public void handleDataEvent(
        Ref<EntityStore> playerReference,
        Store<EntityStore> store,
        MenuEventData eventData
    ) {
        if (eventData == null) {
            return;
        }

        Player player = store.getComponent(playerReference, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            return;
        }

        boolean shouldRefresh = false;
        String action = eventData.getAction();

        String tabCategoryId = eventData.getTabCategory();
        if ("tab".equals(action) && tabCategoryId != null) {
            this.selectedCategoryId = tabCategoryId;
            shouldRefresh = true;
        }

        String categoryId = eventData.getCategory();
        String cosmeticId = eventData.getCosmeticId();
        if ("toggle".equals(action) && categoryId != null && cosmeticId != null) {
            this.coreService.toggleFromMenu(player, categoryId, cosmeticId);
            shouldRefresh = true;
        } else if (action == null && categoryId != null && cosmeticId != null) {
            // Backward-compatible event handling.
            this.coreService.toggleFromMenu(player, categoryId, cosmeticId);
            shouldRefresh = true;
        }

        if (!shouldRefresh) {
            return;
        }

        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        buildMenu(playerReference, store, commands, events);
        sendUpdate(commands, events, false);
    }

    private void buildMenu(
        Ref<EntityStore> playerReference,
        Store<EntityStore> store,
        UICommandBuilder commands,
        UIEventBuilder events
    ) {
        commands.clear(TAB_ROOT);
        commands.clear(LIST_ROOT);

        Player player = store.getComponent(playerReference, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            return;
        }

        CosmeticCategory selectedCategory = resolveSelectedCategory(player);
        this.selectedCategoryId = selectedCategory.getId();

        buildTabs(player, selectedCategory, commands, events);
        buildCosmeticList(player, selectedCategory, commands, events);
        updateHeaderInfo(player, selectedCategory, commands);
    }

    private void updateHeaderInfo(Player player, CosmeticCategory selectedCategory, UICommandBuilder commands) {
        String categoryName = this.coreService.getCategoryDisplayName(player, selectedCategory.getId());
        String activeName = this.coreService.getActiveCosmeticDisplayName(player, selectedCategory.getId());
        commands.set(
            ACTIVE_EFFECT_LABEL + ".Text",
            this.coreService.trForPlayer(player, "menu.gui.active_effect", categoryName, activeName)
        );

        long activeCount = this.coreService
            .getMenuEntries(player, "", null)
            .stream()
            .filter(HyPerksCoreService.MenuEntry::isActive)
            .count();
        commands.set(ACTIVE_COUNT_LABEL + ".Text", this.coreService.trForPlayer(player, "menu.gui.active_count", activeCount));
        commands.set(HINT_LABEL + ".Text", this.coreService.trForPlayer(player, "menu.gui.hint_toggle"));
    }

    private void buildTabs(
        Player player,
        CosmeticCategory selectedCategory,
        UICommandBuilder commands,
        UIEventBuilder events
    ) {
        int index = 0;
        for (CosmeticCategory category : CosmeticCategory.values()) {
            List<HyPerksCoreService.MenuEntry> entries = this.coreService.getMenuEntries(player, "", category.getId());
            if (entries.isEmpty()) {
                continue;
            }

            String itemPath = TAB_ROOT + "[" + index + "]";
            commands.append(TAB_ROOT, TAB_UI_FILE);

            String categoryName = this.coreService.getCategoryDisplayName(player, category.getId());
            long unlocked = entries.stream().filter(HyPerksCoreService.MenuEntry::isUnlocked).count();
            boolean activeInCategory = entries.stream().anyMatch(HyPerksCoreService.MenuEntry::isActive);
            boolean selected = category == selectedCategory;

            commands.set(itemPath + " #Name.Text", (selected ? "> " : "") + categoryName);
            commands.set(itemPath + " #Meta.Text", this.coreService.trForPlayer(player, "menu.gui.tab_unlocked", unlocked, entries.size()));
            commands.set(
                itemPath + " #Status.Text",
                activeInCategory
                    ? this.coreService.trForPlayer(player, "menu.gui.tab_status.active")
                    : (selected
                        ? this.coreService.trForPlayer(player, "menu.gui.tab_status.open")
                        : this.coreService.trForPlayer(player, "menu.gui.tab_status.view"))
            );

            EventData eventData = EventData
                .of(MenuEventData.KEY_ACTION, "tab")
                .append(MenuEventData.KEY_TAB_CATEGORY, category.getId());
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #Button", eventData, false);
            index++;
        }
    }

    private void buildCosmeticList(
        Player player,
        CosmeticCategory selectedCategory,
        UICommandBuilder commands,
        UIEventBuilder events
    ) {
        List<HyPerksCoreService.MenuEntry> entries = this.coreService.getMenuEntries(player, "", selectedCategory.getId());
        if (entries.isEmpty()) {
            commands.appendInline(
                LIST_ROOT,
                "Label { Text: \"No cosmetics found in this tab.\"; Style: (HorizontalAlignment: Center, TextColor: #ffffff(0.75)); }"
            );
            return;
        }

        for (int index = 0; index < entries.size(); index++) {
            HyPerksCoreService.MenuEntry entry = entries.get(index);
            String itemPath = LIST_ROOT + "[" + index + "]";

            commands.append(LIST_ROOT, ENTRY_UI_FILE);
            commands.set(itemPath + " #Name.Text", entry.getDisplayName());
            commands.set(
                itemPath + " #Meta.Text",
                entry.isActive()
                    ? entry.getDetailLine() + " | " + this.coreService.trForPlayer(player, "menu.gui.click_to_disable")
                    : entry.getDetailLine()
            );
            commands.set(itemPath + " #Status.Text", resolveStatusLabel(player, entry));

            EventData eventData = EventData
                .of(MenuEventData.KEY_ACTION, "toggle")
                .append(MenuEventData.KEY_CATEGORY, entry.getCategoryId())
                .append(MenuEventData.KEY_COSMETIC, entry.getCosmeticId());
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #Button", eventData, false);
        }
    }

    private CosmeticCategory resolveSelectedCategory(Player player) {
        CosmeticCategory selected = CosmeticCategory.fromId(this.selectedCategoryId);
        if (selected != null && !this.coreService.getMenuEntries(player, "", selected.getId()).isEmpty()) {
            return selected;
        }

        for (CosmeticCategory category : CosmeticCategory.values()) {
            if (!this.coreService.getMenuEntries(player, "", category.getId()).isEmpty()) {
                return category;
            }
        }
        return CosmeticCategory.AURAS;
    }

    private String resolveStatusLabel(Player player, HyPerksCoreService.MenuEntry entry) {
        if (entry.isActive()) {
            return this.coreService.trForPlayer(player, "status.active");
        }
        if (entry.isUnlocked()) {
            return this.coreService.trForPlayer(player, "status.unlocked");
        }
        return this.coreService.trForPlayer(player, "status.locked");
    }

    public static final class MenuEventData {
        static final String KEY_ACTION = "Action";
        static final String KEY_CATEGORY = "Category";
        static final String KEY_COSMETIC = "Cosmetic";
        static final String KEY_TAB_CATEGORY = "TabCategory";

        static final BuilderCodec<MenuEventData> CODEC = BuilderCodec
            .builder(MenuEventData.class, MenuEventData::new)
            .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (data, value) -> data.action = value, data -> data.action)
            .add()
            .append(new KeyedCodec<>(KEY_CATEGORY, Codec.STRING), (data, value) -> data.category = value, data -> data.category)
            .add()
            .append(
                new KeyedCodec<>(KEY_COSMETIC, Codec.STRING),
                (data, value) -> data.cosmeticId = value,
                data -> data.cosmeticId
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_TAB_CATEGORY, Codec.STRING),
                (data, value) -> data.tabCategory = value,
                data -> data.tabCategory
            )
            .add()
            .build();

        private String action;
        private String category;
        private String cosmeticId;
        private String tabCategory;

        public String getAction() {
            return action;
        }

        public String getCategory() {
            return category;
        }

        public String getCosmeticId() {
            return cosmeticId;
        }

        public String getTabCategory() {
            return tabCategory;
        }
    }
}
