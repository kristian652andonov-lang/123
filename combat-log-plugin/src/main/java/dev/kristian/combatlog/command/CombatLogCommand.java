package dev.kristian.combatlog.command;

import dev.kristian.combatlog.CombatLogPlugin;
import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.combat.UntagReason;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.gui.GuiContext;
import dev.kristian.combatlog.gui.HistoryGui;
import dev.kristian.combatlog.history.HistoryManager;
import dev.kristian.combatlog.region.RegionService;
import dev.kristian.combatlog.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** {@code /combatlog} and its subcommands. */
public final class CombatLogCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("help", "status", "check", "history", "tag", "untag", "zones", "reload", "clearhistory");

    private final CombatLogPlugin plugin;
    private final Settings settings;
    private final CombatManager combat;
    private final RegionService regions;
    private final Text text;
    private final HistoryManager history;
    private final GuiContext guiContext;

    public CombatLogCommand(CombatLogPlugin plugin, Settings settings, CombatManager combat,
                            RegionService regions, Text text, HistoryManager history, GuiContext guiContext) {
        this.plugin = plugin;
        this.settings = settings;
        this.combat = combat;
        this.regions = regions;
        this.text = text;
        this.history = history;
        this.guiContext = guiContext;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> sendHelp(sender);
            case "status" -> status(sender);
            case "check" -> check(sender, args);
            case "history", "gui", "logs" -> openHistory(sender, args);
            case "clearhistory" -> clearHistory(sender);
            case "tag" -> tag(sender, args);
            case "untag" -> untag(sender, args);
            case "zones" -> zones(sender);
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        for (Component line : text.renderAll(settings.messages.help,
                "version", plugin.getDescription().getVersion())) {
            sender.sendMessage(line);
        }
    }

    private void status(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            text.send(sender, settings.messages.playersOnly);
            return;
        }
        long remaining = combat.remaining(player.getUniqueId());
        if (remaining <= 0L) {
            text.send(player, settings.messages.statusSafe);
            return;
        }
        text.send(player, settings.messages.statusInCombat,
                "time", settings.timeStyle.format(remaining),
                "seconds", Long.toString((remaining + 999L) / 1000L));
    }

    private void check(CommandSender sender, String[] args) {
        if (!has(sender, "combatlog.check")) {
            return;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            text.send(sender, settings.messages.playerNotFound, "player", args[1]);
            return;
        }

        long remaining = combat.remaining(target.getUniqueId());
        if (remaining <= 0L) {
            text.send(sender, settings.messages.checkSafe, "player", target.getName());
            return;
        }
        text.send(sender, settings.messages.checkInCombat,
                "player", target.getName(),
                "time", settings.timeStyle.format(remaining),
                "seconds", Long.toString((remaining + 999L) / 1000L));
    }

    /** Opens the combat log browser, optionally narrowed to one player. */
    private void openHistory(CommandSender sender, String[] args) {
        if (!has(sender, "combatlog.history")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            text.send(sender, settings.messages.playersOnly);
            return;
        }
        if (!settings.history.enabled) {
            text.send(player, settings.messages.historyEmpty);
            return;
        }

        UUID filter = null;
        String filterName = null;
        if (args.length >= 2) {
            Player online = Bukkit.getPlayerExact(args[1]);
            if (online != null) {
                filter = online.getUniqueId();
                filterName = online.getName();
            } else {
                // They may well be offline - the log knows their id anyway.
                filter = history.findPlayerId(args[1]);
                filterName = args[1];
                if (filter == null) {
                    text.send(player, settings.messages.playerNotFound, "player", args[1]);
                    return;
                }
            }
        }
        new HistoryGui(guiContext, filter, filterName).open(player);
    }

    private void clearHistory(CommandSender sender) {
        if (!has(sender, "combatlog.admin")) {
            return;
        }
        int count = history.size();
        history.clear();
        text.send(sender, settings.messages.historyCleared, "count", Integer.toString(count));
    }

    private void tag(CommandSender sender, String[] args) {
        if (!has(sender, "combatlog.admin")) {
            return;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            text.send(sender, settings.messages.playerNotFound, "player", args[1]);
            return;
        }

        long durationMillis = settings.general.durationMillis;
        if (args.length >= 3) {
            try {
                durationMillis = (long) (Double.parseDouble(args[2]) * 1000.0D);
            } catch (NumberFormatException exception) {
                text.send(sender, settings.messages.invalidNumber, "input", args[2]);
                return;
            }
            if (durationMillis <= 0L) {
                text.send(sender, settings.messages.invalidNumber, "input", args[2]);
                return;
            }
        }

        combat.tag(target, sender instanceof Player player ? player : null, durationMillis);
        text.send(sender, settings.messages.adminTagged,
                "player", target.getName(),
                "seconds", Long.toString((durationMillis + 999L) / 1000L));
    }

    private void untag(CommandSender sender, String[] args) {
        if (!has(sender, "combatlog.admin")) {
            return;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            text.send(sender, settings.messages.playerNotFound, "player", args[1]);
            return;
        }
        combat.untag(target.getUniqueId(), UntagReason.ADMIN);
        text.send(sender, settings.messages.adminUntagged, "player", target.getName());
    }

    /**
     * Prints what the plugin can see around the sender. This is the fastest way
     * to work out why a spawn region is or is not being treated as a safe zone.
     */
    private void zones(CommandSender sender) {
        if (!has(sender, "combatlog.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            text.send(sender, settings.messages.playersOnly);
            return;
        }
        if (!regions.isAvailable()) {
            text.send(player, settings.messages.worldGuardMissing);
            return;
        }

        List<String> here = new ArrayList<>();
        for (String description : regions.describeRegionsAt(player.getLocation())) {
            here.add(Text.escape(description));
        }
        int radius = settings.barrier.radius;
        var nearby = regions.safeZonesNear(player.getWorld(),
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY(),
                player.getLocation().getBlockZ(),
                radius + 2);

        text.send(player, "%prefix%<muted>standing in</muted> <primary>"
                + (here.isEmpty() ? "no regions" : String.join("<dark_gray>, </dark_gray><primary>", here)) + "</primary>");

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < nearby.size(); i++) {
            if (i > 0) {
                names.append("<dark_gray>, </dark_gray><accent>");
            }
            names.append(Text.escape(nearby.get(i).name()));
        }
        text.send(player, "%prefix%<muted>safe zones within</muted> <accent>" + (radius + 2)
                + "</accent> <muted>blocks:</muted> <accent>"
                + (nearby.isEmpty() ? "none" : names) + "</accent>");
        text.send(player, "%prefix%<muted>you are</muted> "
                + (regions.isSafe(player.getLocation())
                ? "<success>inside a safe zone</success>"
                : "<danger>outside every safe zone</danger>"));
    }

    private void reload(CommandSender sender) {
        if (!has(sender, "combatlog.admin")) {
            return;
        }
        long start = System.nanoTime();
        plugin.reloadEverything();
        long millis = (System.nanoTime() - start) / 1_000_000L;
        text.send(sender, settings.messages.reloaded, "ms", Long.toString(millis));
    }

    private boolean has(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        text.send(sender, settings.messages.noPermission);
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("check") || sub.equals("tag") || sub.equals("untag") || sub.equals("history")) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return filter(names, args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("tag")) {
            return filter(List.of("10", "15", "30", "60"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
