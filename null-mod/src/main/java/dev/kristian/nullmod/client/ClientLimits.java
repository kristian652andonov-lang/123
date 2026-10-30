package dev.kristian.nullmod.client;

import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.config.NullConfig;

/**
 * Effect strengths after combining the player's own settings with the server's limits.
 * Every client effect should scale itself through these, so accessibility settings always win.
 */
public final class ClientLimits {
    private ClientLimits() {
    }

    /** 0..1 multiplier for screen shake and camera movement. */
    public static float shake() {
        double value = NullClientSettings.get(NullClientSettings.SCREEN_SHAKE_INTENSITY)
                * Math.min(1.0, NullConfig.get(NullConfig.MAX_SCREEN_SHAKE));
        if (NullClientSettings.get(NullClientSettings.REDUCE_SCREEN_SHAKE)) {
            value *= 0.2;
        }
        return (float) Math.max(0.0, value);
    }

    /** 0..1 multiplier for flashes, flicker and blackouts; at 0 they must become slow fades. */
    public static float flashing() {
        double value = NullClientSettings.get(NullClientSettings.FLASHING_INTENSITY);
        if (NullClientSettings.get(NullClientSettings.DISABLE_INTENSE_EFFECTS)) {
            value = Math.min(value, 0.35);
        }
        return (float) Math.max(0.0, value);
    }

    /** True if freezes, strobing and other intense effects may run. */
    public static boolean intenseAllowed() {
        return !NullClientSettings.get(NullClientSettings.DISABLE_INTENSE_EFFECTS);
    }

    /** Most visual distortion effects that may run at once. */
    public static int maxVisualEffects() {
        return Math.max(0, Math.min(NullClientSettings.get(NullClientSettings.MAX_VISUAL_EFFECTS),
                NullConfig.get(NullConfig.MAX_VISUAL_EFFECTS)));
    }

    public static boolean shaderEffects() {
        return NullClientSettings.get(NullClientSettings.SHADER_EFFECTS);
    }

    /** 0..1 multiplier for audio corruption; 0 when horror sounds are switched off. */
    public static float audio() {
        if (!NullClientSettings.get(NullClientSettings.HORROR_SOUNDS)) {
            return 0.0F;
        }
        return (float) (NullClientSettings.get(NullClientSettings.AUDIO_INTENSITY)
                * Math.min(1.0, NullConfig.get(NullConfig.MAX_AUDIO_CORRUPTION)));
    }
}
