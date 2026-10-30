package dev.kristian.nullmod.client.menu;

/** Small allocation-free helpers shared by the corrupted main menu. */
final class MenuMath {
    private MenuMath() {
    }

    static float clamp01(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
    }

    static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    /** Smoothstep of an already normalised 0..1 value. */
    static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3.0F - 2.0F * t);
    }

    static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    /** Moves {@code current} towards {@code target} by at most {@code step}. */
    static float approach(float current, float target, float step) {
        if (current < target) {
            return Math.min(target, current + step);
        }
        return Math.max(target, current - step);
    }

    /** Deterministic pseudo random value in [0, 1) for any 64-bit input (splitmix64). */
    static float hash01(long n) {
        long z = n + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 40) / (float) (1L << 24);
    }

    /** Signed variant of {@link #hash01} in [-1, 1). */
    static float hashSigned(long n) {
        return hash01(n) * 2.0F - 1.0F;
    }

    /** Exponentially distributed waiting time (seconds) for an event that happens {@code perMinute} times a minute. */
    static float nextInterval(java.util.Random random, float perMinute) {
        if (perMinute <= 0.0001F) {
            return Float.MAX_VALUE;
        }
        double u = random.nextDouble();
        return (float) (-Math.log(1.0 - u * 0.999) * 60.0 / perMinute);
    }

    static float range(java.util.Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    /** Packs an RGB colour with an alpha in 0..1. Text drawing must skip alpha below 4/255 (see {@link #visible}). */
    static int argb(int rgb, float alpha) {
        int a = (int) (clamp01(alpha) * 255.0F + 0.5F);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** Minecraft draws text whose alpha byte is below 4 fully opaque, so such text must not be drawn at all. */
    static boolean visible(int argb) {
        return (argb >>> 24) >= 4;
    }

    static int lerpRgb(float t, int from, int to) {
        t = clamp01(t);
        int r = (int) lerp(t, (from >> 16) & 255, (to >> 16) & 255);
        int g = (int) lerp(t, (from >> 8) & 255, (to >> 8) & 255);
        int b = (int) lerp(t, from & 255, to & 255);
        return (r << 16) | (g << 8) | b;
    }

    /** Multiplies the brightness of an RGB colour. */
    static int scaleRgb(int rgb, float factor) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * factor));
        int b = Math.min(255, (int) ((rgb & 255) * factor));
        return (r << 16) | (g << 8) | b;
    }
}
