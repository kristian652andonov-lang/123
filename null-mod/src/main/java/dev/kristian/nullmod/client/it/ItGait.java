package dev.kristian.nullmod.client.it;

import net.minecraft.util.Mth;

/**
 * IT's gait, shared by the model (where the feet and hands go) and the client hook (when each one
 * hits the ground, for the footfalls). Upright it walks with long slow steps, legs half a cycle
 * apart, arms swinging; at speed it drops onto its hands and bounds in a rotary gallop (left leg,
 * right leg a beat later, then the hands). Mirrors {@code tools/gen_it.py}.
 */
final class ItGait {
    /** Phase offsets of the legs (left, right) walking and galloping. */
    private static final float[] LEG_WALK = {0.0F, 0.5F};
    private static final float[] LEG_RUN = {0.0F, 0.12F};
    /** Phase offsets of the hands (left, right) when galloping. */
    static final float[] ARM_RUN = {0.5F, 0.62F};
    /** Phase offsets of the arm swing (left, right) when walking: opposite to the legs. */
    static final float[] ARM_WALK = {0.5F, 0.0F};

    private ItGait() {
    }

    /** Blocks covered per gait cycle: long slow steps when it prowls, huge bounds at a gallop. */
    static float cycleLength(float speed) {
        return 4.0F + 4.0F * Math.min(1.0F, speed / 0.78F);
    }

    /** Share of the cycle a limb spends on the ground. */
    static float duty(float run) {
        return 0.62F + (0.42F - 0.62F) * run;
    }

    /** How far a planted limb travels backwards under the body, in model pixels. */
    static float stride(float speed, float duty) {
        return 16.0F * duty * cycleLength(speed) * Math.min(1.0F, speed / 0.04F);
    }

    static float legOffset(int side, float run) {
        return LEG_WALK[side] + (LEG_RUN[side] - LEG_WALK[side]) * run;
    }

    static float frac(float value) {
        return value - Mth.floor(value);
    }

    /** True while a limb with this phase offset is on the ground. */
    static boolean planted(float phase, float offset, float duty) {
        return frac(phase + offset) < duty;
    }
}
