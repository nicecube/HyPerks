package ca.nicecube.hyperks.command;

import ca.nicecube.hyperks.service.HyPerksCoreService;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class HyPerksCommand extends AbstractCommand {
    private final HyPerksCoreService coreService;
    private final OptionalArg<String> actionArg;
    private final OptionalArg<String> arg1;
    private final OptionalArg<String> arg2;

    public HyPerksCommand(HyPerksCoreService coreService) {
        super("hyperks", "Main command for HyPerks cosmetics.");
        this.coreService = coreService;
        this.addAliases("perks", "cosmetics");
        this.requirePermission("hyperks.use");

        this.actionArg = this.withOptionalArg("action", "Action: menu/list/equip/unequip/active/lang/reload", ArgTypes.STRING);
        this.arg1 = this.withOptionalArg("arg1", "First argument", ArgTypes.STRING);
        this.arg2 = this.withOptionalArg("arg2", "Second argument", ArgTypes.STRING);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        String action = optional(this.actionArg, context);
        String first = optional(this.arg1, context);
        String second = optional(this.arg2, context);

        if (action == null || action.isBlank() || action.equalsIgnoreCase("menu") || action.equalsIgnoreCase("help")) {
            this.coreService.showMenu(context);
            return CompletableFuture.completedFuture(null);
        }

        switch (action.toLowerCase(Locale.ROOT)) {
            case "list" -> this.coreService.listCosmetics(context, first);
            case "equip" -> {
                if (first == null || second == null) {
                    this.coreService.send(context, "error.usage", "/hyperks equip <category> <cosmeticId>");
                } else {
                    this.coreService.equip(context, first, second);
                }
            }
            case "unequip" -> {
                if (first == null) {
                    this.coreService.send(context, "error.usage", "/hyperks unequip <category>");
                } else {
                    this.coreService.unequip(context, first);
                }
            }
            case "active" -> this.coreService.showActive(context);
            case "lang" -> {
                if (first == null) {
                    this.coreService.send(context, "error.usage", "/hyperks lang <en|fr>");
                } else {
                    this.coreService.setPlayerLanguage(context, first);
                }
            }
            case "reload" -> {
                if (!this.coreService.canReload(context.sender())) {
                    this.coreService.send(context, "error.no_permission");
                } else {
                    this.coreService.reload();
                    this.coreService.send(context, "cmd.reload.success");
                }
            }
            default -> {
                this.coreService.send(context, "error.action_not_found", action);
                this.coreService.showMenu(context);
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    private String optional(OptionalArg<String> arg, CommandContext context) {
        return arg.provided(context) ? arg.get(context) : null;
    }
}
