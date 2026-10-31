package dev.kristian.nullmod.entity.it;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The kill, as a timeline both sides follow: where IT holds its victim (the victim's feet,
 * relative to IT's feet and facing) at every tick, and when each moment happens. The server
 * places the passenger with it; every client places it the same way and ItModel bends IT's arm
 * to wherever the victim is. Coordinates are (right, up, forward) in blocks.
 */
public final class ItKillPath {
    /** Seized and pulled in, still low. */
    public static final int SEIZE_END = 20;
    /** Raised to its face. */
    public static final int LIFT_END = 45;
    /** The other hand goes through the chest. */
    public static final int PIERCE_AT = 52;
    /** The jaw comes down. */
    public static final int DEVOUR_START = 72;
    public static final int BITE_AT = 82;
    public static final int TEAR_AT = 90;
    public static final int SWALLOW_AT = 100;
    /** The victim dies (a little over five seconds after the grab). */
    public static final int END = 108;

    private static final float[] TIMES = {SEIZE_END, LIFT_END, PIERCE_AT, PIERCE_AT + 5, DEVOUR_START, SWALLOW_AT, END};
    private static final float[][] KEYS = {
            {0.45F, 0.25F, 2.9F},
            {0.3F, 4.7F, 3.3F},
            {0.3F, 4.78F, 3.5F},
            {0.3F, 4.66F, 3.25F},
            {0.25F, 4.64F, 3.2F},
            {0.05F, 4.55F, 2.75F},
            {0.0F, 4.6F, 2.55F}
    };

    private ItKillPath() {
    }

    /** The kill stage at a tick: one of the {@code ItEntity.KILL_*} values (never NONE). */
    public static int stageAt(int ticks) {
        if (ticks < SEIZE_END) {
            return ItEntity.KILL_SEIZE;
        }
        if (ticks < LIFT_END) {
            return ItEntity.KILL_LIFT;
        }
        return ticks < DEVOUR_START ? ItEntity.KILL_PIERCE : ItEntity.KILL_DEVOUR;
    }

    /**
     * The victim's feet at {@code ticks} into the kill, given where they were when seized
     * ({@code fromRight, fromUp, fromForward}): from there to the first key, then from key to key.
     */
    public static Vec3 at(float ticks, float fromRight, float fromUp, float fromForward) {
        float lowUp = Mth.clamp(fromUp, 0.0F, 3.0F);
        if (ticks <= SEIZE_END) {
            float t = smooth(ticks / SEIZE_END);
            return new Vec3(Mth.lerp(t, fromRight, KEYS[0][0]), Mth.lerp(t, fromUp, KEYS[0][1] + lowUp),
                    Mth.lerp(t, fromForward, KEYS[0][2]));
        }
        for (int i = 1; i < TIMES.length; i++) {
            if (ticks <= TIMES[i]) {
                float t = smooth((ticks - TIMES[i - 1]) / (TIMES[i] - TIMES[i - 1]));
                float fromLift = i == 1 ? lowUp : 0.0F;
                return new Vec3(Mth.lerp(t, KEYS[i - 1][0], KEYS[i][0]), Mth.lerp(t, KEYS[i - 1][1] + fromLift, KEYS[i][1]),
                        Mth.lerp(t, KEYS[i - 1][2], KEYS[i][2]));
            }
        }
        float[] last = KEYS[KEYS.length - 1];
        return new Vec3(last[0], last[1], last[2]);
    }

    private static float smooth(float t) {
        float c = Mth.clamp(t, 0.0F, 1.0F);
        return c * c * (3.0F - 2.0F * c);
    }
}
