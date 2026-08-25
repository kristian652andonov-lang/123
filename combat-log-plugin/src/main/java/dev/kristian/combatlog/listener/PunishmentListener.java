package dev.kristian.combatlog.listener;

import dev.kristian.combatlog.barrier.BarrierManager;
import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.combat.UntagReason;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.display.DisplayManager;
import dev.kristian.combatlog.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Punishes players who disconnect mid-fight, and tidies up their state. */
public final class PunishmentListener implements Listener {

    /** Vanilla caps the experience a dead player scatters at 100. */
    private static final int MAX_DROPPED_EXPERIENCE = 100;

    private final Settings settings;
    private final CombatManager combat;
    private final BarrierManager barrier;
    private final DisplayManager display;
    private final Text text;
    private final Logger logger;

    /** Players who left because they were kicked rather than because they quit. */
    private final Set<UUID> kicked = new HashSet<>();

    public PunishmentListener(Settings settings, CombatManager combat, BarrierManager barrier,
                              DisplayManager display, Text text, Logger logger) {
        this.settings = settings;
        this.combat = combat;
        this.barrier = barrier;
        this.display = display;
        this.text = text;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        kicked.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Nothing should survive a reconnect except item cooldowns, which are
        // deliberately kept so relogging cannot reset a gapple.
        UUID id = event.getPlayer().getUniqueId();
        kicked.remove(id);
        barrier.forget(id);
        display.forget(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean wasKicked = kicked.remove(id);
        boolean tagged = combat.isTagged(id);

        if (tagged
                && settings.punishment.killOnQuit
                && (!wasKicked || settings.punishment.punishOnKick)) {
            punish(player);
        }

        combat.untag(id, UntagReason.QUIT);
        barrier.forget(id);
        display.forget(id);
    }

    private void punish(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();

        try {
            if (settings.punishment.dropInventory && world != null) {
                dropInventory(player, world, location);
            }
            if (settings.punishment.dropExperience && world != null) {
                dropExperience(player, world, location);
            }
            if (settings.punishment.lightningEffect && world != null) {
                world.strikeLightningEffect(location);
            }
            // The kill is what actually stops them dodging the fight; the drops
            // above already happened so nothing can be duplicated by the death.
            player.setHealth(0.0D);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not punish " + player.getName() + " for combat logging", exception);
        }

        announce(player);
        runCommands(player);
    }

    private void dropInventory(Player player, World world, Location location) {
        PlayerInventory inventory = player.getInventory();

        for (ItemStack item : inventory.getStorageContents()) {
            dropItem(world, location, item);
        }
        for (ItemStack item : inventory.getArmorContents()) {
            dropItem(world, location, item);
        }
        dropItem(world, location, inventory.getItemInOffHand());

        if (settings.punishment.clearInventoryAfterDrop) {
            inventory.setStorageContents(new ItemStack[inventory.getStorageContents().length]);
            inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
            inventory.setItemInOffHand(null);
        }
    }

    private static void dropItem(World world, Location location, ItemStack item) {
        if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
            world.dropItemNaturally(location, item);
        }
    }

    private void dropExperience(Player player, World world, Location location) {
        int amount = Math.min(player.getLevel() * 7, MAX_DROPPED_EXPERIENCE);
        if (amount > 0) {
            ExperienceOrb orb = world.spawn(location, ExperienceOrb.class);
            orb.setExperience(amount);
        }
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0F);
    }

    private void announce(Player player) {
        if (!settings.punishment.broadcast) {
            return;
        }
        Component message = text.render(settings.messages.combatLogBroadcast, "player", player.getName());
        if (message == null) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            if (settings.punishment.broadcastSameWorldOnly && !online.getWorld().equals(player.getWorld())) {
                continue;
            }
            online.sendMessage(message);
            settings.sounds.combatLogged.play(online);
        }
        logger.info(text.plain(settings.messages.combatLogBroadcast, "player", player.getName()));
    }

    private void runCommands(Player player) {
        for (String command : settings.punishment.commands) {
            if (command == null || command.isBlank()) {
                continue;
            }
            String prepared = command.replace("%player%", player.getName());
            if (prepared.startsWith("/")) {
                prepared = prepared.substring(1);
            }
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), prepared);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Combat log command failed: " + prepared, exception);
            }
        }
    }
}
