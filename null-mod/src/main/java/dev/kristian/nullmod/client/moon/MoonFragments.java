package dev.kristian.nullmod.client.moon;

import dev.kristian.nullmod.moon.MoonGeometry;

/**
 * Where the broken-off pieces of the moon are: a pure function of the shard, the moon
 * progress and time, so every player sees the same slowly drifting debris field. Positions
 * are in disc space (1 unit = one moon diameter) relative to the moon.
 */
final class MoonFragments {
    /** Progress it takes a fresh piece to drift most of the way out. */
    static final float DRIFT_SPAN = 0.22F;
    /** The same for pieces that break off during the shattering. */
    static final float SHATTER_DRIFT_SPAN = 0.07F;
    /** Index layout of {@link #place} output. */
    static final int OFFSET_U = 0;
    static final int OFFSET_V = 1;
    static final int ANGLE = 2;
    static final int SCALE = 3;
    static final int BRIGHTNESS = 4;
    static final int SIZE = 5;

    private MoonFragments() {
    }

    /**
     * Places a broken shard.
     *
     * @param animation 0..1 progress of a "breaking off right now" animation, or 1 when settled
     * @return false if the shard has not broken off at this progress
     */
    static boolean place(MoonGeometry.Shard shard, float progress, float seconds, float animation, float[] out) {
        if (!shard.brokenAt(progress)) {
            return false;
        }
        float age = progress - shard.breakAt();
        // Pieces that come off while the moon shatters fly apart faster, so the debris field
        // spreads out instead of piling up in front of what is left.
        boolean shattering = shard.breakAt() >= MoonGeometry.SHATTER;
        float span = shattering ? SHATTER_DRIFT_SPAN : DRIFT_SPAN;
        float settle = Math.min(1.0F, age / span);
        float eased = 1.0F - (1.0F - settle) * (1.0F - settle);
        float reach = shard.driftDistance() * (shattering ? 1.2F : 1.0F);
        // A broken piece is always clearly apart from its hole, then creeps further out.
        float separation = 0.16F + 0.12F * shard.depth();
        float drift = separation + (reach - separation) * eased + 0.15F * Math.max(0.0F, age - span);
        drift *= easeOutBack(animation);
        float wobble = shard.wobble() * Math.min(1.0F, animation * 1.5F);
        float u = shard.driftX() * drift + wobble * (float) Math.sin(seconds * 0.07F + shard.phase());
        float v = shard.driftY() * drift + wobble * (float) Math.cos(seconds * 0.05F + shard.phase() * 1.3F);
        out[OFFSET_U] = u;
        out[OFFSET_V] = v;
        out[ANGLE] = shard.spin() * (age * 5.0F + seconds * 0.006F) * animation;
        out[SCALE] = 1.0F - 0.18F * shard.depth() * eased * animation;
        out[BRIGHTNESS] = (0.95F - 0.3F * shard.depth() * eased) * (0.75F + 0.25F * animation) + 0.25F * (1.0F - animation);
        return true;
    }

    /** Small overshoot so a piece visibly snaps off before it floats away. */
    static float easeOutBack(float t) {
        if (t >= 1.0F) {
            return 1.0F;
        }
        if (t <= 0.0F) {
            return 0.0F;
        }
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float x = t - 1.0F;
        return 1.0F + c3 * x * x * x + c1 * x * x;
    }

    /** Deterministic 0..1 hash for per-debris variation. */
    static float hash(long seed, int a, int b) {
        long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ 0x5DEECE66DL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) * 0x1.0p-24F;
    }
}
