package dev.kristian.nullmod.client.variant;

import dev.kristian.nullmod.entity.variant.MobVariant;
import dev.kristian.nullmod.network.MobVariantPayload;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * The client's copy of which mobs are variants, keyed by entity network id in the current level.
 * Filled from {@link MobVariantPayload}; entries go when their mob leaves the level, on level
 * change and on logout (see {@link MobVariantClientModule}). Render thread only.
 */
public final class ClientMobVariants {
    /** One mob's variant as the server last described it. */
    public record State(MobVariant variant, long seed, float growth) {
    }

    private static final VariantIntMap<State> STATES = new VariantIntMap<>();
    /** Ids whose mob was not in the level at the last sweep. */
    private static int[] suspects = new int[16];
    private static int suspectCount;
    private static int[] scratch = new int[64];

    private ClientMobVariants() {
    }

    public static void accept(MobVariantPayload payload) {
        MobVariant variant = MobVariant.byId(payload.variant());
        if (variant == MobVariant.NONE) {
            remove(payload.entityId());
        } else {
            STATES.put(payload.entityId(), new State(variant, payload.seed(), Math.max(0.0F, Math.min(1.0F, payload.growth()))));
        }
    }

    /** The mob's variant state, or {@code null} for an ordinary mob. */
    @Nullable
    public static State get(Entity entity) {
        return STATES.size() == 0 ? null : STATES.get(entity.getId());
    }

    public static void remove(int entityId) {
        if (STATES.remove(entityId) != null) {
            MobVisuals.forget(entityId);
        }
    }

    /** Forgets everything (logout, level change). */
    public static void clear() {
        STATES.clear();
        MobVisuals.clear();
        suspectCount = 0;
    }

    public static int size() {
        return STATES.size();
    }

    /** Copies the ids of every variant mob into {@code out}; returns how many fitted. */
    static int ids(int[] out) {
        return STATES.keys(out);
    }

    /**
     * Drops entries whose mob has not been in the level for two sweeps in a row: a description
     * of a mob this client never received (the server tells everybody near the mob, and a
     * client with a short view distance may not have it) would otherwise stay until logout.
     */
    static void sweep(Level level) {
        int n = STATES.size();
        if (n == 0) {
            suspectCount = 0;
            return;
        }
        if (scratch.length < n) {
            scratch = new int[Integer.highestOneBit(n) * 2];
        }
        n = STATES.keys(scratch);
        int[] next = new int[Math.max(16, n)];
        int nextCount = 0;
        for (int i = 0; i < n; i++) {
            int id = scratch[i];
            if (level.getEntity(id) != null) {
                continue;
            }
            boolean missingBefore = false;
            for (int j = 0; j < suspectCount; j++) {
                if (suspects[j] == id) {
                    missingBefore = true;
                    break;
                }
            }
            if (missingBefore) {
                remove(id);
            } else {
                next[nextCount++] = id;
            }
        }
        suspects = next;
        suspectCount = nextCount;
    }
}
