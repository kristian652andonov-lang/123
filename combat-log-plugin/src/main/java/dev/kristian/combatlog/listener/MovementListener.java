package dev.kristian.combatlog.listener;

import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.combat.CombatTag;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.region.RegionService;
import dev.kristian.combatlog.text.Text;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/**
 * Keeps tagged players out of safe zones.
 *
 * <p>The fake glass wall already stops them client side, but a client can be
 * modified and a teleport ignores blocks entirely, so this listener is the part
 * that actually enforces the rule.
 */
public final class MovementListener implements Listener {

    private final Settings settings;
    private final CombatManager combat;
    private final RegionService regions;
    private final Text text;

    public MovementListener(Settings settings, CombatManager combat, RegionService regions, Text text) {
        this.settings = settings;
        this.combat = combat;
        this.regions = regions;
        this.text = text;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!settings.worldGuard.enabled || !settings.worldGuard.blockEntering || !regions.isAvailable()) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        // Looking around fires this event too - only a change of block matters.
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        CombatTag tag = combat.get(player.getUniqueId());
        if (tag == null || tag.remaining(System.currentTimeMillis()) <= 0L) {
            return;
        }
        if (!regions.isSafe(to)) {
            return;
        }
        // Already standing in one - let them walk back out instead of trapping them.
        if (regions.isSafe(from)) {
            return;
        }

        event.setCancelled(true);
        pushBack(player, from, to);
        warn(player, tag, settings.messages.safezoneBlocked);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!settings.worldGuard.enabled || !regions.isAvailable()) {
            return;
        }

        Player player = event.getPlayer();
        CombatTag tag = combat.get(player.getUniqueId());
        if (tag == null || tag.remaining(System.currentTimeMillis()) <= 0L) {
            return;
        }

        Location to = event.getTo();
        if (!regions.isSafe(to) || regions.isSafe(event.getFrom())) {
            return;
        }

        boolean pearl = event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
        if (pearl) {
            if (!settings.worldGuard.blockEnderPearl) {
                return;
            }
            event.setCancelled(true);
            warn(player, tag, settings.messages.enderpearlBlocked);
            return;
        }

        if (!settings.worldGuard.blockTeleport) {
            return;
        }
        event.setCancelled(true);
        warn(player, tag, settings.messages.teleportBlocked);
    }

    /** Nudges the player back the way they came so they do not stick to the border. */
    private void pushBack(Player player, Location from, Location to) {
        if (!settings.worldGuard.pushBack) {
            return;
        }
        Vector away = from.toVector().subtract(to.toVector());
        away.setY(0.0D);
        if (away.lengthSquared() < 1.0E-6D) {
            away = player.getLocation().getDirection().multiply(-1.0D);
            away.setY(0.0D);
        }
        if (away.lengthSquared() < 1.0E-6D) {
            return;
        }
        away.normalize().multiply(settings.worldGuard.pushStrength).setY(settings.worldGuard.pushUpward);
        player.setVelocity(away);
    }

    private void warn(Player player, CombatTag tag, String message) {
        if (!tag.tryBlockedMessage(System.currentTimeMillis(), settings.worldGuard.messageCooldownMillis)) {
            return;
        }
        long remaining = tag.remaining(System.currentTimeMillis());
        text.send(player, message,
                "time", settings.timeStyle.format(remaining),
                "seconds", Long.toString((remaining + 999L) / 1000L));
        settings.sounds.barrierBlocked.play(player);
    }
}
