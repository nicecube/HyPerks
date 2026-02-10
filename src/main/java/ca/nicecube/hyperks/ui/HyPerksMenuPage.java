package ca.nicecube.hyperks.ui;

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
import java.util.Locale;

public final class HyPerksMenuPage extends InteractiveCustomUIPage<HyPerksMenuPage.MenuEventData> {
    private static final String PAGE_UI_FILE = "Pages/HyPerksMenuPage.ui";
    private static final String ENTRY_UI_FILE = "Pages/HyPerksMenuEntryButton.ui";
    private static final String LIST_ROOT = "#CosmeticList";
    private static final String SEARCH_INPUT = "#SearchInput";

    private final HyPerksCoreService coreService;
    private String searchQuery = "";

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
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            SEARCH_INPUT,
            EventData.of(MenuEventData.KEY_SEARCH_QUERY, SEARCH_INPUT + ".Value")
        );
        buildCosmeticList(playerReference, store, commands, events);
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

        String incomingSearch = eventData.getSearchQuery();
        if (incomingSearch != null) {
            this.searchQuery = incomingSearch.trim().toLowerCase(Locale.ROOT);
            shouldRefresh = true;
        }

        String categoryId = eventData.getCategory();
        String cosmeticId = eventData.getCosmeticId();
        if (categoryId != null && cosmeticId != null) {
            this.coreService.equipFromMenu(player, categoryId, cosmeticId);
            shouldRefresh = true;
        }

        if (!shouldRefresh) {
            return;
        }

        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        buildCosmeticList(playerReference, store, commands, events);
        sendUpdate(commands, events, false);
    }

    private void buildCosmeticList(
        Ref<EntityStore> playerReference,
        Store<EntityStore> store,
        UICommandBuilder commands,
        UIEventBuilder events
    ) {
        commands.clear(LIST_ROOT);

        Player player = store.getComponent(playerReference, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            return;
        }

        List<HyPerksCoreService.MenuEntry> entries = this.coreService.getMenuEntries(player, this.searchQuery);
        if (entries.isEmpty()) {
            commands.appendInline(
                LIST_ROOT,
                "Label { Text: \"No cosmetics found\"; Style: (Alignment: Center, TextColor: #ffffff(0.75)); }"
            );
            return;
        }

        for (int index = 0; index < entries.size(); index++) {
            HyPerksCoreService.MenuEntry entry = entries.get(index);
            String itemPath = LIST_ROOT + "[" + index + "]";

            commands.append(LIST_ROOT, ENTRY_UI_FILE);
            commands.set(itemPath + " #Name.Text", entry.getDisplayName());
            commands.set(itemPath + " #Meta.Text", entry.getDetailLine());
            commands.set(itemPath + " #Status.Text", resolveStatusLabel(entry));

            EventData eventData = EventData
                .of(MenuEventData.KEY_CATEGORY, entry.getCategoryId())
                .append(MenuEventData.KEY_COSMETIC, entry.getCosmeticId());
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath, eventData, false);
        }
    }

    private String resolveStatusLabel(HyPerksCoreService.MenuEntry entry) {
        if (entry.isActive()) {
            return "ACTIVE";
        }
        if (entry.isUnlocked()) {
            return "UNLOCKED";
        }
        return "LOCKED";
    }

    public static final class MenuEventData {
        static final String KEY_CATEGORY = "Category";
        static final String KEY_COSMETIC = "Cosmetic";
        static final String KEY_SEARCH_QUERY = "@SearchQuery";

        static final BuilderCodec<MenuEventData> CODEC = BuilderCodec
            .builder(MenuEventData.class, MenuEventData::new)
            .append(new KeyedCodec<>(KEY_CATEGORY, Codec.STRING), (data, value) -> data.category = value, data -> data.category)
            .add()
            .append(
                new KeyedCodec<>(KEY_COSMETIC, Codec.STRING),
                (data, value) -> data.cosmeticId = value,
                data -> data.cosmeticId
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_SEARCH_QUERY, Codec.STRING),
                (data, value) -> data.searchQuery = value,
                data -> data.searchQuery
            )
            .add()
            .build();

        private String category;
        private String cosmeticId;
        private String searchQuery;

        public String getCategory() {
            return category;
        }

        public String getCosmeticId() {
            return cosmeticId;
        }

        public String getSearchQuery() {
            return searchQuery;
        }
    }
}
