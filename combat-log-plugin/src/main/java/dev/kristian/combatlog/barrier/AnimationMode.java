package dev.kristian.combatlog.barrier;

import java.util.Locale;

/** How the red glass wall cycles through its configured frames. */
public enum AnimationMode {

    /** A ripple that travels outwards from the player. */
    WAVE,
    /** The whole wall changes colour together. */
    PULSE,
    /** No animation at all - always the first frame. */
    STATIC;

    public static AnimationMode parse(String name, AnimationMode fallback) {
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
