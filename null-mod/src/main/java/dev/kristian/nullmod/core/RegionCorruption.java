package dev.kristian.nullmod.core;

import net.minecraft.util.Mth;

/**
 * Makes different regions of the world corrupt at different speeds. A smooth value-noise
 * field over 160-block cells scales the global progress, so some areas fall apart early and
 * others hold out for a long time. Nothing is stored; the field is derived from the world seed.
 */
public final class RegionCorruption {
    private static final int CELL = 160;

    private RegionCorruption() {
    }

    /** Multiplier in roughly 0.35..1.65 for a block column. */
    public static float regionFactor(long seed, int blockX, int blockZ) {
        double fx = (double) blockX / CELL;
        double fz = (double) blockZ / CELL;
        int x0 = Mth.floor(fx);
        int z0 = Mth.floor(fz);
        double tx = smooth(fx - x0);
        double tz = smooth(fz - z0);
        double a = cellValue(seed, x0, z0);
        double b = cellValue(seed, x0 + 1, z0);
        double c = cellValue(seed, x0, z0 + 1);
        double d = cellValue(seed, x0 + 1, z0 + 1);
        double v = Mth.lerp(tz, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
        return (float) (0.35 + 1.3 * v);
    }

    /** 0..1 corruption of a column, combining the stage with the region's speed. */
    public static float localCorruption(CorruptionContext context, int blockX, int blockZ) {
        float base = ((context.stage().number() - 1) + context.stageProgress()) / 4.0F;
        float local = base * regionFactor(context.seed(), blockX, blockZ);
        return Mth.clamp(local, 0.0F, 1.0F);
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double cellValue(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
