package dev.kristian.combatlog.listener;

import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.cooldown.CooldownManager;
import dev.kristian.combatlog.text.Text;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.UUID;

/** Everything a tagged player is not allowed to do: run away, fly away, or teleport away. */
public final class RestrictionListener implements Listener {

    private final Settings settings;
    private final CombatManager combat;
    private final CooldownManager cooldowns;
    private final Text text;

    public RestrictionListener(Settings settings, CombatManager combat, CooldownManager cooldowns, Text text) {
        this.settings = settings;
        this.combat = combat;
        this.cooldowns = cooldowns;
        this.text = text;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!settings.restrictions.blockCommands) {
            return;
        }
        Player player = event.getPlayer();
        if (!combat.isTagged(player.getUniqueId()) || player.hasPermission("combatlog.bypass.commands")) {
            return;
        }

        String root = rootCommand(event.getMessage());
        if (root.isEmpty() || root.equals("combatlog") || root.equals("cl") || root.equals("combat")) {
            return;
        }

        boolean listed = settings.restrictions.commands.contains(root);
        boolean blocked = settings.restrictions.commandWhitelistMode ? !listed : listed;
        if (!blocked) {
            return;
        }

        event.setCancelled(true);
        long remaining = combat.remaining(player.getUniqueId());
        text.send(player, settings.messages.commandBlocked,
                "command", root,
                "time", settings.timeStyle.format(remaining),
                "seconds", Long.toString((remaining + 999L) / 1000L));
        settings.sounds.actionBlocked.play(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!settings.restrictions.blockFlight || !event.isFlying()) {
            return;
        }
        Player player = event.getPlayer();
        if (!combat.isTagged(player.getUniqueId()) || player.hasPermission("combatlog.bypass.flight")) {
            return;
        }
        // An elytra launch also fires this event; that is handled by onGlide.
        if (player.isGliding()) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
        text.send(player, settings.messages.flightBlocked);
        settings.sounds.actionBlocked.play(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || !event.isGliding()) {
            return;
        }
        UUID id = player.getUniqueId();

        boolean tagged = combat.isTagged(id);
        if (tagged
                && (settings.restrictions.blockElytra || settings.elytra.disableInCombat)
                && !player.hasPermission("combatlog.bypass.elytra")) {
            event.setCancelled(true);
            player.setGliding(false);
            text.send(player, settings.messages.elytraBlocked);
            settings.sounds.actionBlocked.play(player);
            return;
        }

        long remaining = cooldowns.remaining(id, CooldownManager.ELYTRA_KEY);
        if (remaining > 0L) {
            event.setCancelled(true);
            player.setGliding(false);
            text.send(player, settings.messages.elytraCooldown,
                    "time", settings.timeStyle.format(remaining),
                    "seconds", Long.toString((remaining + 999L) / 1000L));
            settings.sounds.cooldownDenied.play(player);
            return;
        }

        if (settings.elytra.glideCooldownMillis > 0L) {
            cooldowns.set(id, CooldownManager.ELYTRA_KEY, settings.elytra.glideCooldownMillis);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRiptide(PlayerInteractEvent event) {
        if (!settings.restrictions.blockRiptide) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null
                || item.getType() != Material.TRIDENT
                || !item.containsEnchantment(Enchantment.RIPTIDE)) {
            return;
        }
        Player player = event.getPlayer();
        if (!combat.isTagged(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        text.send(player, settings.messages.riptideBlocked);
        settings.sounds.actionBlocked.play(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChorusFruit(PlayerItemConsumeEvent event) {
        if (!settings.restrictions.blockChorusFruit || event.getItem().getType() != Material.CHORUS_FRUIT) {
            return;
        }
        Player player = event.getPlayer();
        if (!combat.isTagged(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        text.send(player, settings.messages.chorusBlocked);
        settings.sounds.actionBlocked.play(player);
    }

    /** Pulls "home" out of "/essentials:home 2". */
    private static String rootCommand(String message) {
        String body = message.startsWith("/") ? message.substring(1) : message;
        body = body.trim();
        int space = body.indexOf(' ');
        String root = space < 0 ? body : body.substring(0, space);
        int colon = root.indexOf(':');
        if (colon >= 0) {
            root = root.substring(colon + 1);
        }
        return root.toLowerCase(Locale.ROOT);
    }
}
