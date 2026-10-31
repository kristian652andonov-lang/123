package dev.kristian.nullmod.client.variant;

import dev.kristian.nullmod.entity.it.ItEntity;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;

/**
 * The {@link MobVisual} of every variant mob, created the first time it is drawn and dropped
 * together with its {@link ClientMobVariants} entry. Render thread only.
 */
final class MobVisuals {
    private static final VariantIntMap<MobVisual> VISUALS = new VariantIntMap<>();

    private MobVisuals() {
    }

    /** The visual for a variant mob, (re)initialised when its variant or seed changed. */
    static MobVisual of(Entity entity, ClientMobVariants.State state) {
        int id = entity.getId();
        MobVisual visual = VISUALS.get(id);
        if (visual == null) {
            visual = new MobVisual(id);
            // IT is corrupted on the client at all times and as hard as it gets.
            visual.heavy = entity instanceof ItEntity;
            VISUALS.put(id, visual);
        }
        if (visual.variant != state.variant() || visual.seed != state.seed()) {
            visual.reset(state.variant(), state.seed(), state.growth());
        }
        visual.targetGrowth = state.growth();
        return visual;
    }

    @Nullable
    static MobVisual get(int id) {
        return VISUALS.get(id);
    }

    static void forget(int id) {
        VISUALS.remove(id);
    }

    static void clear() {
        VISUALS.clear();
    }
}
