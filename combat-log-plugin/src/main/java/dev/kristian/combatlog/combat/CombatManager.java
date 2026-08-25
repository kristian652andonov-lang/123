package dev.kristian.combatlog.combat;

import dev.kristian.combatlog.config.Settings;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every live combat timer.
 *
 * <p>This class holds state and nothing else - messages, sounds and the barrier
 * are driven by whatever {@link TagListener}s are registered, which keeps the
 * timer logic independent of how it is presented.
 */
public final class CombatManager {

    private final Map<UUID, CombatTag> tags = new ConcurrentHashMap<>();
    private final List<TagListener> listeners = new ArrayList<>(4);
    private final Settings settings;

    public CombatManager(Settings settings) {
        this.settings = settings;
    }

    public void addListener(TagListener listener) {
        listeners.add(listener);
    }

    /**
     * Puts a player in combat, or tops their timer back up if they are already in it.
     *
     * @param opponent who caused it, may be null for mob or admin tags
     * @return true if this started a new tag rather than refreshing one
     */
    public boolean tag(Player player, Player opponent) {
        return tag(player, opponent, settings.general.durationMillis);
    }

    public boolean tag(Player player, Player opponent, long durationMillis) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        CombatTag existing = tags.get(id);

        if (existing == null) {
            CombatTag tag = new CombatTag(id, durationMillis, now);
            applyOpponent(tag, opponent);
            tags.put(id, tag);
            for (TagListener listener : listeners) {
                listener.onTag(player, tag, true);
            }
            return true;
        }

        if (settings.general.refreshOnHit) {
            existing.refresh(durationMillis, now);
        } else if (now + durationMillis > existing.expiresAt()) {
            // Never let a second hit shorten a timer that is already running longer.
            existing.extendTo(now + durationMillis, durationMillis);
        }
        applyOpponent(existing, opponent);
        for (TagListener listener : listeners) {
            listener.onTag(player, existing, false);
        }
        return false;
    }

    public boolean isTagged(UUID playerId) {
        CombatTag tag = tags.get(playerId);
        return tag != null && tag.remaining(System.currentTimeMillis()) > 0L;
    }

    public CombatTag get(UUID playerId) {
        return tags.get(playerId);
    }

    public long remaining(UUID playerId) {
        CombatTag tag = tags.get(playerId);
        return tag == null ? 0L : tag.remaining(System.currentTimeMillis());
    }

    public Collection<CombatTag> active() {
        return tags.values();
    }

    public int size() {
        return tags.size();
    }

    public void untag(UUID playerId, UntagReason reason) {
        CombatTag removed = tags.remove(playerId);
        if (removed == null) {
            return;
        }
        for (TagListener listener : listeners) {
            listener.onUntag(playerId, removed, reason);
        }
    }

    public void clearAll(UntagReason reason) {
        for (UUID id : List.copyOf(tags.keySet())) {
            untag(id, reason);
        }
    }

    /** Drops every timer that has run out. Called from the plugin's tick loop. */
    public void expireFinishedTags() {
        if (tags.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<UUID> finished = null;
        for (Iterator<Map.Entry<UUID, CombatTag>> it = tags.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, CombatTag> entry = it.next();
            if (entry.getValue().remaining(now) <= 0L) {
                if (finished == null) {
                    finished = new ArrayList<>(2);
                }
                finished.add(entry.getKey());
            }
        }
        if (finished != null) {
            for (UUID id : finished) {
                untag(id, UntagReason.EXPIRED);
            }
        }
    }

    private static void applyOpponent(CombatTag tag, Player opponent) {
        if (opponent != null) {
            tag.setOpponent(opponent.getUniqueId(), opponent.getName());
        }
    }
}
