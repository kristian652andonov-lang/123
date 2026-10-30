package dev.kristian.nullmod.entity.nullroot;

/**
 * Per-organism animation state, kept by the client only (the physical client's
 * {@link NullrootClientHook} advances it once per client tick and the renderer interpolates it).
 * Plain numbers, no client classes, so it can live on the common entity; a server never creates
 * one. Every "previous" field holds last tick's value for partial-tick interpolation.
 */
public final class NullrootAnimation {
    /** False until the first client tick filled in the starting values. */
    public boolean started;
    /** The grow sound has been played (only for organisms first seen while still growing). */
    public boolean grewAudible;
    /** Ticks this organism has been animated on this client. */
    public int age;
    /** Stable per-organism randomness (sway phases, twitch timing, texture flicker). */
    public long seed;

    /** 0..1 visible size, smoothed from the synced growth. */
    public float growth;
    public float growthO;
    /** Visible length of the reaching tendril in blocks, smoothed from the synced reach. */
    public float reach;
    public float reachO;
    /** 0..1 capture progress, smoothed. */
    public float capture;
    public float captureO;
    /** Turn toward the watched player, radians about the organism's own up axis. */
    public float yaw;
    public float yawO;
    /** Where it is turning to. */
    public float targetYaw;
    /** Lean of the whole body (radians), set by twitches while nobody looks. */
    public float leanX;
    public float leanZ;
    public float leanXO;
    public float leanZO;
    /** Phase offset of the idle sway; jumps on a twitch so the pose is different when looked at again. */
    public float swayShift;
    /** 0..1 decaying jolt after a twitch, recoil or hit. */
    public float jolt;
    public float joltO;
    /** 0..1 decaying swell after a pulse. */
    public float pulse;
    public float pulseO;
    /** Ticks in a row nobody on this client has looked at it. */
    public int unseenTicks;
    /** Client tick at which it may twitch again. */
    public int nextTwitch;
    /** Direction (world, unit) its tendril last reached in, for retracting after the target is gone. */
    public double reachDirX;
    public double reachDirY = 1.0;
    public double reachDirZ;
    /** Length of the tendril the last time it pointed at something. */
    public float lastReach;

    public static float lerp(float partialTick, float previous, float current) {
        return previous + (current - previous) * partialTick;
    }

    public float growth(float partialTick) {
        return lerp(partialTick, growthO, growth);
    }

    public float reach(float partialTick) {
        return lerp(partialTick, reachO, reach);
    }

    public float capture(float partialTick) {
        return lerp(partialTick, captureO, capture);
    }

    public float jolt(float partialTick) {
        return lerp(partialTick, joltO, jolt);
    }

    public float pulse(float partialTick) {
        return lerp(partialTick, pulseO, pulse);
    }

    public float leanX(float partialTick) {
        return lerp(partialTick, leanXO, leanX);
    }

    public float leanZ(float partialTick) {
        return lerp(partialTick, leanZO, leanZ);
    }

    /** Interpolated turn, taking the short way around. */
    public float yaw(float partialTick) {
        float delta = yaw - yawO;
        while (delta > (float) Math.PI) {
            delta -= (float) (Math.PI * 2.0);
        }
        while (delta < (float) -Math.PI) {
            delta += (float) (Math.PI * 2.0);
        }
        return yawO + delta * partialTick;
    }

    /** Copies every current value into its "previous" slot; call at the start of a client tick. */
    public void shift() {
        growthO = growth;
        reachO = reach;
        captureO = capture;
        yawO = yaw;
        leanXO = leanX;
        leanZO = leanZ;
        joltO = jolt;
        pulseO = pulse;
    }
}
