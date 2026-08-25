package dev.kristian.combatlog.hook;

import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.combat.CombatTag;
import dev.kristian.combatlog.config.Settings;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Exposes the combat timer to scoreboards and other plugins.
 *
 * <pre>
 *   %combatlog_in_combat%   true / false
 *   %combatlog_time%        formatted with the configured time style
 *   %combatlog_seconds%     whole seconds left
 *   %combatlog_opponent%    who they are fighting
 *   %combatlog_tagged%      how many players are in combat right now
 * </pre>
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final Settings settings;
    private final CombatManager combat;

    public PlaceholderHook(JavaPlugin plugin, Settings settings, CombatManager combat) {
        this.plugin = plugin;
        this.settings = settings;
        this.combat = combat;
    }

    @Override
    public String getIdentifier() {
        return "combatlog";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("tagged")) {
            return Integer.toString(combat.size());
        }
        if (player == null) {
            return "";
        }

        CombatTag tag = combat.get(player.getUniqueId());
        long remaining = tag == null ? 0L : tag.remaining(System.currentTimeMillis());

        return switch (key) {
            case "in_combat" -> Boolean.toString(remaining > 0L);
            case "time" -> remaining > 0L ? settings.timeStyle.format(remaining) : "0";
            case "seconds" -> Long.toString((remaining + 999L) / 1000L);
            case "opponent" -> tag == null ? "" : tag.opponentName();
            default -> null;
        };
    }
}
