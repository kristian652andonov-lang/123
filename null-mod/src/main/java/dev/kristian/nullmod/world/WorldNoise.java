package dev.kristian.nullmod.world;

import net.minecraft.util.Mth;

/**
 * Small deterministic hash and value-noise helpers for the terrain and structure generators.
 * Everything is a pure function of the seed and coordinates, so a generator given the same seed
 * always produces the same shape.
 */
public final class WorldNoise {
    private WorldNoise() {
    }

    /** Well-mixed 64-bit hash of a seed and three integers. */
    public static long hash(long seed, int x, int y, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xD1B54A32D192ED03L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** Uniform value in [0, 1) for a lattice point. */
    public static double hash01(long seed, int x, int y, int z) {
        return (hash(seed, x, y, z) >>> 11) * 0x1.0p-53;
    }

    public static double hash01(long seed, int x, int z) {
        return hash01(seed, x, 0, z);
    }

    /** Smooth 2D value noise in [0, 1]; one feature per unit of {@code x} and {@code z}. */
    public static double value(long seed, double x, double z) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);
        double tx = smooth(x - x0);
        double tz = smooth(z - z0);
        double a = hash01(seed, x0, z0);
        double b = hash01(seed, x0 + 1, z0);
        double c = hash01(seed, x0, z0 + 1);
        double d = hash01(seed, x0 + 1, z0 + 1);
        return Mth.lerp(tz, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
    }

    /** Two octaves of value noise, still in [0, 1]. */
    public static double fractal(long seed, double x, double z) {
        return (value(seed, x, z) * 2.0 + value(seed ^ 0x5DEECE66DL, x * 2.03, z * 2.03)) / 3.0;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
