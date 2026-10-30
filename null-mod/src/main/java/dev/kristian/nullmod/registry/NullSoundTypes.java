package dev.kristian.nullmod.registry;

import net.neoforged.neoforge.common.util.DeferredSoundType;

/** Block sound sets. Corrupted blocks sound distorted when stepped on or broken. */
public final class NullSoundTypes {
    public static final DeferredSoundType CORRUPTED = new DeferredSoundType(1.0F, 1.0F,
            NullSounds.CORRUPTED_BREAK, NullSounds.CORRUPTED_STEP, NullSounds.CORRUPTED_PLACE,
            NullSounds.CORRUPTED_HIT, NullSounds.CORRUPTED_STEP);

    private NullSoundTypes() {
    }
}
