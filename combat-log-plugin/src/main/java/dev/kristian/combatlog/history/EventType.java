package dev.kristian.combatlog.history;

import java.util.Locale;

/** The kinds of thing the combat log records. */
public enum EventType {

    /** Two players started a fight - the first hit of an engagement. */
    FIGHT,
    /** A player was killed by another player. */
    KILL,
    /** A player died with nobody to blame - fall, mob, void. */
    DEATH,
    /** A player disconnected while tagged and was punished for it. */
    COMBAT_LOG,
    /** An operator restored somebody's inventory. Kept so a rollback can be undone. */
    ROLLBACK;

    public static EventType parse(String name, EventType fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
