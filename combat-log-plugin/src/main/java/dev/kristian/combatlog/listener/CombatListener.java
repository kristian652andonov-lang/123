package dev.kristian.combatlog.listener;

import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.combat.UntagReason;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.history.HistoryManager;
import dev.kristian.combatlog.region.RegionService;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Locale;

/** Decides who gets put in combat, and frees them again when somebody dies. */
public final class CombatListener implements Listener {

    private final Settings settings;
    private final CombatManager combat;
    private final RegionService regions;
    private final HistoryManager history;

    public CombatListener(Settings settings, CombatManager combat, RegionService regions, HistoryManager history) {
        this.settings = settings;
        this.combat = combat;
        this.regions = regions;
        this.history = history;
    }

    /**
     * Runs at MONITOR so every protection plugin has already had its say - if
     * WorldGuard cancelled the hit because it happened inside spawn, nobody is
     * tagged for it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Settings.General general = settings.general;
        if (isDisabledWorld(victim.getWorld().getName())) {
            return;
        }

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker != null) {
            if (!general.tagOnPlayerDamage) {
                return;
            }
            if (general.ignoreSelfDamage && attacker.getUniqueId().equals(victim.getUniqueId())) {
                return;
            }
        } else if (!general.tagOnMobDamage) {
            return;
        }

        if (general.noTagInsideSafeZone) {
            if (regions.isSafe(victim.getLocation())) {
                return;
            }
            if (attacker != null && regions.isSafe(attacker.getLocation())) {
                return;
            }
        }

        if (general.tagVictim && canBeTagged(victim)) {
            // A fresh tag means this is the start of an engagement rather than
            // another hit in one already running, so it is worth recording.
            boolean fresh = combat.tag(victim, attacker);
            if (fresh && attacker != null) {
                history.recordFight(attacker, victim);
            }
        }
        if (attacker != null && general.tagAttacker && canBeTagged(attacker)) {
            combat.tag(attacker, victim);
        }
    }

    /**
     * Runs first so the inventory is captured exactly as the victim was carrying
     * it, before drops are computed or a keep-inventory plugin empties it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void recordDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        history.recordDeath(victim, victim.getKiller());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (settings.general.untagOnDeath) {
            combat.untag(victim.getUniqueId(), UntagReason.DEATH);
        }
        Player killer = victim.getKiller();
        if (killer != null && settings.general.untagKillerOnKill && !killer.getUniqueId().equals(victim.getUniqueId())) {
            combat.untag(killer.getUniqueId(), UntagReason.KILL);
        }
    }

    /** Walks back from whatever dealt the damage to the player responsible for it. */
    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (settings.general.countProjectiles && damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        if (settings.general.countPets && damager instanceof Tameable tameable && tameable.isTamed()) {
            if (tameable.getOwner() instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private boolean canBeTagged(Player player) {
        if (settings.general.respectBypassPermission && player.hasPermission("combatlog.bypass")) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }

    private boolean isDisabledWorld(String worldName) {
        return settings.general.disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
