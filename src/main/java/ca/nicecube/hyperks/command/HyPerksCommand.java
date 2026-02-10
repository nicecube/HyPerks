package ca.nicecube.hyperks.command;

import ca.nicecube.hyperks.service.HyPerksCoreService;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class HyPerksCommand extends AbstractCommand {
    private final HyPerksCoreService coreService;

    public HyPerksCommand(HyPerksCoreService coreService) {
        super("hyperks", "Main command for HyPerks cosmetics.");
        this.coreService = coreService;
        this.addAliases("perks", "cosmetics");
        this.requirePermission("hyperks.use");
        this.setAllowsExtraArguments(true);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        ParsedArgs parsedArgs = parseArguments(context);
        String action = parsedArgs.action;
        String first = parsedArgs.arg1;
        String second = parsedArgs.arg2;

        if (!this.coreService.checkCommandCooldown(context)) {
            return CompletableFuture.completedFuture(null);
        }

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
            case "clear", "clearall" -> this.coreService.clearAll(context);
            case "active" -> this.coreService.showActive(context);
            case "status" -> this.coreService.showStatus(context);
            case "refreshperms" -> this.coreService.refreshPermissionCache(context);
            case "debugmodel" -> this.coreService.debugModel(context, first);
            case "debugmodels" -> this.coreService.debugModels(context, first);
            case "modelvfx" -> this.coreService.modelVfx(context, first, second);
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

    private ParsedArgs parseArguments(CommandContext context) {
        String rawInput = context.getInputString();
        if (rawInput == null || rawInput.isBlank()) {
            return ParsedArgs.empty();
        }

        String[] split = rawInput.trim().split("\\s+");
        List<String> tokens = new ArrayList<>(split.length);
        for (String token : split) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return ParsedArgs.empty();
        }

        stripCommandTokenIfPresent(context, tokens);
        if (tokens.isEmpty()) {
            return ParsedArgs.empty();
        }

        String action = null;
        String arg1 = null;
        String arg2 = null;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (token.startsWith("--action=")) {
                action = sanitize(token.substring("--action=".length()));
                continue;
            }
            if (token.startsWith("--arg1=")) {
                arg1 = sanitize(token.substring("--arg1=".length()));
                continue;
            }
            if (token.startsWith("--arg2=")) {
                arg2 = sanitize(token.substring("--arg2=".length()));
                continue;
            }
            if (token.equalsIgnoreCase("--action")) {
                if (i + 1 < tokens.size()) {
                    action = sanitize(tokens.get(++i));
                }
                continue;
            }
            if (token.equalsIgnoreCase("--arg1")) {
                if (i + 1 < tokens.size()) {
                    arg1 = sanitize(tokens.get(++i));
                }
                continue;
            }
            if (token.equalsIgnoreCase("--arg2")) {
                if (i + 1 < tokens.size()) {
                    arg2 = sanitize(tokens.get(++i));
                }
                continue;
            }

            positional.add(token);
        }

        int position = 0;
        if (action == null && position < positional.size()) {
            action = sanitize(positional.get(position++));
        }
        if (arg1 == null && position < positional.size()) {
            arg1 = sanitize(positional.get(position++));
        }
        if (arg2 == null && position < positional.size()) {
            arg2 = sanitize(positional.get(position));
        }

        return new ParsedArgs(action, arg1, arg2);
    }

    private void stripCommandTokenIfPresent(CommandContext context, List<String> tokens) {
        if (tokens.isEmpty()) {
            return;
        }

        String firstToken = normalizeCommandToken(tokens.get(0));
        if (firstToken.isEmpty()) {
            return;
        }

        if (commandMatches(firstToken, context.getCalledCommand().getName())) {
            tokens.remove(0);
            return;
        }

        for (String alias : context.getCalledCommand().getAliases()) {
            if (commandMatches(firstToken, alias)) {
                tokens.remove(0);
                return;
            }
        }
    }

    private boolean commandMatches(String token, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return token.equalsIgnoreCase(normalizeCommandToken(name));
    }

    private String normalizeCommandToken(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class ParsedArgs {
        private final String action;
        private final String arg1;
        private final String arg2;

        private ParsedArgs(String action, String arg1, String arg2) {
            this.action = action;
            this.arg1 = arg1;
            this.arg2 = arg2;
        }

        private static ParsedArgs empty() {
            return new ParsedArgs(null, null, null);
        }
    }
}
