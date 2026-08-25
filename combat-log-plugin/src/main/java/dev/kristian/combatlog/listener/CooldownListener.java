package dev.kristian.combatlog.listener;

import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.cooldown.CooldownManager;
import dev.kristian.combatlog.cooldown.CooldownRule;
import dev.kristian.combatlog.text.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Enforces the per-item cooldowns: golden apples, pearls, rockets and food.
 *
 * <p>Two events are needed. Edible items start their cooldown from the consume
 * event, so a cancelled bite never burns the cooldown. Everything else starts
 * from the interact event. The interact event also carries the refusal message,
 * because once an item is on the vanilla cooldown clock the consume event stops
 * firing altogether.
 */
public final class CooldownListener implements Listener {

    private final Settings settings;
    private final CombatManager combat;
    private final CooldownManager cooldowns;
    private final Text text;

    public CooldownListener(Settings settings, CombatManager combat, CooldownManager cooldowns, Text text) {
        this.settings = settings;
        this.combat = combat;
        this.cooldowns = cooldowns;
        this.text = text;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material material = event.getItem().getType();
        CooldownRule rule = ruleFor(player, material);
        if (rule == null) {
            return;
        }

        long remaining = cooldowns.remaining(player.getUniqueId(), material);
        if (remaining > 0L) {
            event.setCancelled(true);
            deny(player, material, remaining);
            return;
        }
        start(player, rule);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        Player player = event.getPlayer();
        Material material = item.getType();
        CooldownRule rule = ruleFor(player, material);
        if (rule == null) {
            return;
        }

        long remaining = cooldowns.remaining(player.getUniqueId(), material);
        if (remaining > 0L) {
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            deny(player, material, remaining);
            return;
        }

        // Edible items wait for the consume event, which only fires once the
        // eating animation actually completes.
        if (!material.isEdible()) {
            start(player, rule);
        }
    }

    /** The rule that applies to this player right now, or null when nothing does. */
    private CooldownRule ruleFor(Player player, Material material) {
        Settings.Cooldowns options = settings.cooldowns;
        if (!options.enabled) {
            return null;
        }
        if (player.hasPermission("combatlog.bypass.cooldowns")) {
            return null;
        }

        CooldownRule rule = options.itemRules.get(material);
        if (rule != null) {
            if (rule.onlyWhileGliding() && !player.isGliding()) {
                return null;
            }
            if (rule.onlyInCombat() && !combat.isTagged(player.getUniqueId())) {
                return null;
            }
            return rule;
        }

        if (!options.foodEnabled
                || options.foodMillis <= 0L
                || !material.isEdible()
                || options.foodIgnored.contains(material)) {
            return null;
        }
        if (options.foodOnlyInCombat && !combat.isTagged(player.getUniqueId())) {
            return null;
        }
        return new CooldownRule(material, options.foodMillis, options.foodOnlyInCombat, false);
    }

    private void start(Player player, CooldownRule rule) {
        cooldowns.set(player.getUniqueId(), rule.material(), rule.millis());
        if (settings.cooldowns.showItemAnimation) {
            player.setCooldown(rule.material(), (int) Math.max(1L, rule.millis() / 50L));
        }
    }

    private void deny(Player player, Material material, long remaining) {
        if (!cooldowns.shouldNotify(player.getUniqueId(), settings.cooldowns.messageCooldownMillis)) {
            return;
        }
        text.send(player, settings.messages.cooldownActive,
                "item", prettyName(material),
                "time", settings.timeStyle.format(remaining),
                "seconds", Long.toString((remaining + 999L) / 1000L));
        settings.sounds.cooldownDenied.play(player);
    }

    /** GOLDEN_APPLE -> "golden apple", so it reads well in small caps. */
    private static String prettyName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
