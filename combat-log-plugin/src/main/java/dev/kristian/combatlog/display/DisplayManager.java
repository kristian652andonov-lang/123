package dev.kristian.combatlog.display;

import dev.kristian.combatlog.combat.CombatTag;
import dev.kristian.combatlog.combat.TagListener;
import dev.kristian.combatlog.combat.UntagReason;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.text.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the player actually sees: the action bar above the hotbar, the
 * optional boss bar, and the messages, titles and sounds fired when a tag
 * starts or ends.
 */
public final class DisplayManager implements TagListener {

    private final Settings settings;
    private final Text text;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    public DisplayManager(Settings settings, Text text) {
        this.settings = settings;
        this.text = text;
    }

    /** Redraws the timer for one player. Called from the tick loop. */
    public void update(Player player, CombatTag tag, long tick) {
        long now = System.currentTimeMillis();
        long remaining = tag.remaining(now);
        float progress = tag.progress(now);
        String time = settings.timeStyle.format(remaining);
        String seconds = Long.toString((remaining + 999L) / 1000L);

        if (settings.actionBar.enabled) {
            text.sendActionBar(player, settings.actionBar.format,
                    "!bar", buildProgressBar(progress, remaining, tick),
                    "time", time,
                    "seconds", seconds,
                    "opponent", tag.opponentName());
        }

        if (settings.bossBar.enabled) {
            BossBar bar = bossBars.get(player.getUniqueId());
            Component title = text.render(settings.bossBar.title,
                    "time", time, "seconds", seconds, "opponent", tag.opponentName());
            if (bar == null) {
                bar = BossBar.bossBar(
                        title == null ? Component.empty() : title,
                        progress,
                        settings.bossBar.color,
                        settings.bossBar.overlay);
                bossBars.put(player.getUniqueId(), bar);
                player.showBossBar(bar);
            } else {
                bar.name(title == null ? Component.empty() : title);
                bar.progress(progress);
            }
        }
    }

    /** Wipes the timer from the screen. */
    public void clear(Player player) {
        if (settings.actionBar.enabled) {
            player.sendActionBar(Component.empty());
        }
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    public void forget(UUID playerId) {
        bossBars.remove(playerId);
    }

    public void clearEveryone() {
        for (Map.Entry<UUID, BossBar> entry : Map.copyOf(bossBars).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
                if (settings.actionBar.enabled) {
                    player.sendActionBar(Component.empty());
                }
            }
        }
        bossBars.clear();
    }

    // ------------------------------------------------------------- listener

    @Override
    public void onTag(Player player, CombatTag tag, boolean fresh) {
        long seconds = (tag.duration() + 999L) / 1000L;
        if (!fresh) {
            text.send(player, settings.messages.refreshed,
                    "time", settings.timeStyle.format(tag.remaining(System.currentTimeMillis())),
                    "seconds", Long.toString(seconds),
                    "opponent", tag.opponentName());
            return;
        }

        text.send(player, settings.messages.tagged,
                "seconds", Long.toString(seconds),
                "time", settings.timeStyle.format(tag.duration()),
                "duration", Long.toString(seconds),
                "opponent", tag.opponentName());

        if (!tag.opponentName().isEmpty()) {
            text.send(player, settings.messages.taggedBy, "opponent", tag.opponentName());
        }

        settings.sounds.tagged.play(player);
        showTitle(player, settings.tagTitle, Long.toString(seconds), tag.opponentName());
    }

    @Override
    public void onUntag(UUID playerId, CombatTag tag, UntagReason reason) {
        BossBar bar = bossBars.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (bar != null) {
            player.hideBossBar(bar);
        }
        clear(player);

        // Nothing to celebrate if they died, quit, or the server is shutting down.
        if (reason != UntagReason.EXPIRED && reason != UntagReason.KILL && reason != UntagReason.ADMIN) {
            return;
        }

        text.send(player, settings.messages.untagged);
        settings.sounds.untagged.play(player);
        showTitle(player, settings.untagTitle, "0", tag.opponentName());
    }

    // -------------------------------------------------------------- drawing

    /**
     * Builds the {@code %bar%} string. The remaining share of the timer is drawn
     * in the "filled" colour, which swaps to the low colour near the end and
     * blinks once the flash threshold is crossed.
     */
    private String buildProgressBar(float progress, long remaining, long tick) {
        Settings.ActionBar options = settings.actionBar;
        if (!options.barEnabled) {
            return "";
        }

        int length = options.barLength;
        int filled = Math.round(progress * length);
        if (filled <= 0 && remaining > 0L) {
            filled = 1;
        }
        filled = Math.max(0, Math.min(length, filled));

        String filledColor = options.barFilled;
        if (progress * 100.0F <= options.lowThresholdPercent) {
            filledColor = options.barLow;
        }
        if (options.flashEnabled
                && remaining <= options.flashBelowMillis
                && (tick / options.flashIntervalTicks) % 2L == 0L) {
            filledColor = options.flashColor;
        }

        StringBuilder bar = new StringBuilder(length * options.barSymbol.length() + 32);
        bar.append(filledColor);
        bar.append(options.barSymbol.repeat(filled));
        bar.append(options.barEmpty);
        bar.append(options.barSymbol.repeat(length - filled));
        return bar.toString();
    }

    private void showTitle(Player player, Settings.TitleOptions options, String seconds, String opponent) {
        if (!options.enabled) {
            return;
        }
        Component title = text.render(options.title, "seconds", seconds, "opponent", opponent);
        Component subtitle = text.render(options.subtitle, "seconds", seconds, "opponent", opponent);
        if (title == null && subtitle == null) {
            return;
        }
        player.showTitle(Title.title(
                title == null ? Component.empty() : title,
                subtitle == null ? Component.empty() : subtitle,
                Title.Times.times(
                        Duration.ofMillis(options.fadeInTicks * 50L),
                        Duration.ofMillis(options.stayTicks * 50L),
                        Duration.ofMillis(options.fadeOutTicks * 50L))));
    }
}
