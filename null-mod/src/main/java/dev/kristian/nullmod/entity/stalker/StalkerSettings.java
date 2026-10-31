package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.config.NullConfig;

/** The "stalkers" section of the server config, combined with the world modification switches. */
public final class StalkerSettings {
    private StalkerSettings() {
    }

    public static boolean enabled() {
        return NullConfig.get(NullConfig.STALKERS_ENABLED);
    }

    /** Multiplier for how often the figure appears; 0 when it is switched off. */
    public static double frequency() {
        return enabled() ? NullConfig.get(NullConfig.STALKER_FREQUENCY) : 0.0;
    }

    public static boolean doorOpening() {
        return enabled() && NullConfig.get(NullConfig.WORLD_MODIFICATION) && NullConfig.get(NullConfig.STALKER_DOOR_OPENING);
    }

    public static boolean windowBreaking() {
        return enabled() && NullConfig.get(NullConfig.WORLD_MODIFICATION) && NullConfig.get(NullConfig.STALKER_WINDOW_BREAKING);
    }

    /** Digging replaces natural stone with air, so it needs natural replacement as well. */
    public static boolean minerDigging() {
        return enabled() && NullConfig.mayReplaceNatural() && NullConfig.get(NullConfig.STALKER_MINER_DIGGING);
    }
}
