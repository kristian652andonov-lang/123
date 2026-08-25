package dev.kristian.combatlog.history;

import dev.kristian.combatlog.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Keeps the record of who fought who, who killed who, and what they were
 * carrying when it happened.
 *
 * <p>Entries live in memory newest first and are mirrored to
 * {@code plugins/CombatLog/history.yml}. Item data is turned into base64 the
 * moment it is captured, on the main thread, so the periodic save can be handed
 * to an async task without ever touching a live Bukkit object off-thread.
 */
public final class HistoryManager {

    private final Plugin plugin;
    private final Settings settings;
    private final Logger logger;
    private final File file;

    private final List<CombatEvent> events = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1L);
    private final Object writeLock = new Object();

    public HistoryManager(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.logger = plugin.getLogger();
        this.file = new File(plugin.getDataFolder(), "history.yml");
    }

    // ------------------------------------------------------------- recording

    /** The first hit of a new engagement between two players. */
    public void recordFight(Player attacker, Player victim) {
        if (!settings.history.enabled || !settings.history.recordFights) {
            return;
        }
        Location at = victim.getLocation();
        add(new CombatEvent(nextId.getAndIncrement(), EventType.FIGHT, System.currentTimeMillis(),
                victim.getUniqueId(), victim.getName(),
                attacker.getUniqueId(), attacker.getName(),
                at.getWorld() == null ? "?" : at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                null, heldItemName(attacker), null));
    }

    /**
     * A death. Called at LOWEST priority so the inventory is captured exactly as
     * the player was carrying it, before drops or keep-inventory plugins run.
     */
    public void recordDeath(Player victim, Player killer) {
        if (!settings.history.enabled) {
            return;
        }
        boolean byPlayer = killer != null && !killer.getUniqueId().equals(victim.getUniqueId());
        if (byPlayer ? !settings.history.recordKills : !settings.history.recordDeathsWithoutKiller) {
            return;
        }

        Location at = victim.getLocation();
        add(new CombatEvent(nextId.getAndIncrement(),
                byPlayer ? EventType.KILL : EventType.DEATH,
                System.currentTimeMillis(),
                victim.getUniqueId(), victim.getName(),
                byPlayer ? killer.getUniqueId() : null,
                byPlayer ? killer.getName() : null,
                at.getWorld() == null ? "?" : at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                describeCause(victim),
                byPlayer ? heldItemName(killer) : null,
                capture(victim)));
    }

    /** Somebody disconnected while tagged. Called before their gear is dropped. */
    public void recordCombatLog(Player player, String opponentName, UUID opponentId) {
        if (!settings.history.enabled || !settings.history.recordCombatLogs) {
            return;
        }
        Location at = player.getLocation();
        add(new CombatEvent(nextId.getAndIncrement(), EventType.COMBAT_LOG, System.currentTimeMillis(),
                player.getUniqueId(), player.getName(),
                opponentId, opponentName,
                at.getWorld() == null ? "?" : at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                "combat log", null, capture(player)));
    }

    /**
     * Snapshots a player's current inventory before it is overwritten, so a
     * rollback is itself reversible.
     */
    public void recordRollback(Player target, String actorName) {
        if (!settings.history.enabled) {
            return;
        }
        Location at = target.getLocation();
        add(new CombatEvent(nextId.getAndIncrement(), EventType.ROLLBACK, System.currentTimeMillis(),
                target.getUniqueId(), target.getName(),
                null, actorName,
                at.getWorld() == null ? "?" : at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                "inventory replaced by " + actorName, null, capture(target)));
    }

    private String capture(Player player) {
        if (!settings.history.keepInventorySnapshots) {
            return null;
        }
        PlayerInventory inventory = player.getInventory();
        return new InventorySnapshot(
                inventory.getStorageContents().clone(),
                inventory.getArmorContents().clone(),
                inventory.getItemInOffHand().clone()).encode();
    }

    private void add(CombatEvent event) {
        events.add(0, event);
        trim();
    }

    private void trim() {
        while (events.size() > settings.history.maxEntries) {
            events.remove(events.size() - 1);
        }
        int kept = 0;
        for (CombatEvent event : events) {
            if (!event.hasSnapshot()) {
                continue;
            }
            if (++kept > settings.history.maxSnapshots) {
                event.clearSnapshot();
            }
        }
    }

    // -------------------------------------------------------------- querying

    public List<CombatEvent> all() {
        return List.copyOf(events);
    }

    public int size() {
        return events.size();
    }

    /**
     * @param type   only entries of this kind, or null for every kind
     * @param player only entries involving this player, or null for everyone
     */
    public List<CombatEvent> filtered(EventType type, UUID player) {
        List<CombatEvent> out = new ArrayList<>();
        for (CombatEvent event : events) {
            if (type != null && event.type() != type) {
                continue;
            }
            if (player != null && !event.involves(player)) {
                continue;
            }
            out.add(event);
        }
        return out;
    }

    /**
     * Finds a player's id by name from the log itself, so an offline player can
     * still be filtered on.
     *
     * @return the id from the most recent entry naming them, or null
     */
    public UUID findPlayerId(String name) {
        for (CombatEvent event : events) {
            if (event.subjectName().equalsIgnoreCase(name)) {
                return event.subjectId();
            }
            if (event.hasActor() && event.actorName().equalsIgnoreCase(name)) {
                return event.actorId();
            }
        }
        return null;
    }

    public CombatEvent byId(long id) {
        for (CombatEvent event : events) {
            if (event.id() == id) {
                return event;
            }
        }
        return null;
    }

    public void clear() {
        events.clear();
        saveAsync();
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            long highest = 0L;
            for (Map<?, ?> row : config.getMapList("entries")) {
                CombatEvent event = CombatEvent.fromMap(row);
                if (event == null) {
                    continue;
                }
                events.add(event);
                highest = Math.max(highest, event.id());
            }
            nextId.set(Math.max(config.getLong("next-id", 1L), highest + 1L));
            trim();
            logger.info("Loaded " + events.size() + " combat log entries.");
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "history.yml could not be read - starting with an empty log", exception);
        }
    }

    public void saveAsync() {
        List<Map<String, Object>> rows = serialize();
        long id = nextId.get();
        if (!plugin.isEnabled()) {
            write(rows, id);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(rows, id));
    }

    public void saveNow() {
        write(serialize(), nextId.get());
    }

    /** Snapshots the log as plain data so the write can safely happen off-thread. */
    private List<Map<String, Object>> serialize() {
        List<Map<String, Object>> rows = new ArrayList<>(events.size());
        for (CombatEvent event : events) {
            rows.add(event.toMap());
        }
        return rows;
    }

    private void write(List<Map<String, Object>> rows, long id) {
        synchronized (writeLock) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    logger.warning("Could not create " + parent);
                    return;
                }
                YamlConfiguration config = new YamlConfiguration();
                config.set("next-id", id);
                config.set("entries", rows);
                config.save(file);
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Could not write history.yml", exception);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String heldItemName(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            return "fists";
        }
        return held.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Turns "ENTITY_ATTACK by zombie" into something readable in a lore line. */
    private static String describeCause(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last == null) {
            return "unknown";
        }
        String cause = last.getCause().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            String source = damager instanceof Player player
                    ? player.getName()
                    : damager.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            return cause + " - " + source;
        }
        return cause;
    }
}
