package dev.kristian.nullmod.client.variant;

import dev.kristian.nullmod.entity.variant.MobVariant;
import dev.kristian.nullmod.network.MobVariantPayload;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;

/**
 * The client's copy of which mobs are variants, keyed by entity network id in the current level.
 * Filled from {@link MobVariantPayload}; render thread only.
 */
public final class ClientMobVariants {
    /** One mob's variant as the server last described it. */
    public record State(MobVariant variant, long seed, float growth) {
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    private ClientMobVariants() {
    }

    public static void accept(MobVariantPayload payload) {
        MobVariant variant = MobVariant.byId(payload.variant());
        if (variant == MobVariant.NONE) {
            STATES.remove(payload.entityId());
        } else {
            STATES.put(payload.entityId(), new State(variant, payload.seed(), Math.max(0.0F, Math.min(1.0F, payload.growth()))));
        }
    }

    /** The mob's variant state, or {@code null} for an ordinary mob. */
    @Nullable
    public static State get(Entity entity) {
        return STATES.get(entity.getId());
    }

    public static void remove(int entityId) {
        STATES.remove(entityId);
    }

    /** Forgets everything (logout, level change). */
    public static void clear() {
        STATES.clear();
    }

    public static int size() {
        return STATES.size();
    }
}
