package dev.kristian.nullmod.entity.variant;

/**
 * What NULL has done to a mob. Any mob (vanilla or from another mod) can become a variant; the
 * variant lives in a data attachment on the mob, so a corrupted cow is still a cow.
 */
public enum MobVariant {
    /** An ordinary mob. */
    NONE(0),
    /** The mob is failing like the world: wrong textures, stutters, freezes, wrong sounds. */
    CORRUPTED(1),
    /** The mob is a host: something grows out of it. */
    PARASITE(2);

    private static final MobVariant[] VALUES = values();

    private final int id;

    MobVariant(int id) {
        this.id = id;
    }

    /** Stable id used in saves and payloads. */
    public int id() {
        return id;
    }

    /** The variant with this id; unknown ids read as {@link #NONE}. */
    public static MobVariant byId(int id) {
        for (MobVariant variant : VALUES) {
            if (variant.id == id) {
                return variant;
            }
        }
        return NONE;
    }
}
