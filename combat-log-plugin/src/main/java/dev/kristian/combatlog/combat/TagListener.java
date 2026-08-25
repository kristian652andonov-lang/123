package dev.kristian.combatlog.combat;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Notified whenever a combat tag starts, is refreshed, or ends. */
public interface TagListener {

    /**
     * @param fresh {@code true} the first time a player enters combat, {@code false}
     *              when an existing timer is topped back up by another hit
     */
    void onTag(Player player, CombatTag tag, boolean fresh);

    /** The player may be offline (a quit or a timer that ran out while away). */
    void onUntag(UUID playerId, CombatTag tag, UntagReason reason);
}
