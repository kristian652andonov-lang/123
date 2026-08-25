package dev.kristian.combatlog.cooldown;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player item cooldowns.
 *
 * <p>Kept separate from the vanilla {@code Player#setCooldown} clock: the vanilla
 * one is only a visual (and is wiped by a death or a respawn), while this map is
 * what the listeners actually enforce.
 */
public final class CooldownManager {

    /** Key used for the elytra, which is not tied to a single item. */
    public static final String ELYTRA_KEY = "@elytra";

    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    public void set(UUID playerId, Material material, long millis) {
        set(playerId, material.name(), millis);
    }

    public void set(UUID playerId, String key, long millis) {
        if (millis <= 0L) {
            return;
        }
        cooldowns.computeIfAbsent(playerId, id -> new HashMap<>(8))
                .put(key, System.currentTimeMillis() + millis);
    }

    public long remaining(UUID playerId, Material material) {
        return remaining(playerId, material.name());
    }

    /** Milliseconds left, or 0 when nothing is running. Expired entries are dropped here. */
    public long remaining(UUID playerId, String key) {
        Map<String, Long> byKey = cooldowns.get(playerId);
        if (byKey == null) {
            return 0L;
        }
        Long expiresAt = byKey.get(key);
        if (expiresAt == null) {
            return 0L;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            byKey.remove(key);
            return 0L;
        }
        return remaining;
    }

    public void clear(UUID playerId, String key) {
        Map<String, Long> byKey = cooldowns.get(playerId);
        if (byKey != null) {
            byKey.remove(key);
        }
    }

    public void clearAll(UUID playerId) {
        cooldowns.remove(playerId);
        lastMessageAt.remove(playerId);
    }

    public void clearEveryone() {
        cooldowns.clear();
        lastMessageAt.clear();
    }

    /** Stops a held-down right click from flooding the chat with the same warning. */
    public boolean shouldNotify(UUID playerId, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Long last = lastMessageAt.get(playerId);
        if (last != null && now - last < cooldownMillis) {
            return false;
        }
        lastMessageAt.put(playerId, now);
        return true;
    }
}
