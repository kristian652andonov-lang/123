package dev.kristian.nullmod.client.visual;

/**
 * Deterministic hashing and smooth 1D value noise. Everything here is allocation free so it
 * can run every frame: camera shake, flicker patterns and glitch layouts are all derived from
 * a seed and the visual clock instead of from stored random state.
 */
final class SmoothNoise {
    private SmoothNoise() {
    }

    static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= x >>> 33;
        return x;
    }

    /** Uniform 0..1 for (seed, n). */
    static float hash01(long seed, long n) {
        return (mix(seed * 0x9E3779B97F4A7C15L + n * 0xD1B54A32D192ED03L + 0x632BE59BD9B4E019L) >>> 40) * 0x1.0p-24F;
    }

    /** Uniform -1..1 for (seed, n). */
    static float hashSigned(long seed, long n) {
        return hash01(seed, n) * 2.0F - 1.0F;
    }

    /** Smooth value noise in -1..1, continuous in {@code t}, one new value per unit of {@code t}. */
    static float noise(long seed, double t) {
        double fl = Math.floor(t);
        long i = (long) fl;
        float f = (float) (t - fl);
        float u = f * f * f * (f * (f * 6.0F - 15.0F) + 10.0F);
        float a = hashSigned(seed, i);
        float b = hashSigned(seed, i + 1);
        return a + (b - a) * u;
    }

    /** Two octaves of {@link #noise}; still in -1..1. */
    static float fbm(long seed, double t) {
        return noise(seed, t) * 0.68F + noise(seed ^ 0x5DEECE66DL, t * 2.137) * 0.32F;
    }

    /** Smooth value noise remapped to 0..1. */
    static float noise01(long seed, double t) {
        return noise(seed, t) * 0.5F + 0.5F;
    }

    static float clamp01(float x) {
        return x < 0.0F ? 0.0F : (x > 1.0F ? 1.0F : x);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0F - 2.0F * t);
    }
}
