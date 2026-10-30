package dev.kristian.nullmod.client.dimension;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.registry.NullDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * The state of the NULL dimension's atmosphere on the client, read by the sky, the fog, the
 * lightmap and the hum:
 * <ul>
 * <li>the geometry blackout ({@code dimension.blackout}): the sky, every light (glowing blocks
 * included) and the hum go dark for one to three seconds;</li>
 * <li>the RGB wave ({@code dimension.pulse}): red, then green, then blue light runs through the
 * sky and the structure;</li>
 * <li>ambient sky trouble scheduled here: the sky stutters now and then and rarely goes out
 * completely for a moment.</li>
 * </ul>
 * Accessibility: with a low flashing setting every change of brightness becomes a slow fade and
 * the colour wave is weaker and slower; stuttering only happens while strobing is allowed.
 * Render thread only.
 */
final class DimensionAtmosphere {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final float[] TINT = new float[3];

    /** Client ticks spent in a world, not counting pauses: the clock of every envelope here. */
    private static long clock;
    private static final Envelope BLACKOUT = new Envelope();
    private static final Envelope SKY = new Envelope();
    private static long pulseStart = Long.MIN_VALUE;
    private static float pulseLength = 1.0F;
    private static float pulseStrength;
    private static long nextAmbient = -1L;

    private DimensionAtmosphere() {
    }

    /** An attack/hold/release curve, optionally chopped into a stutter. */
    private static final class Envelope {
        long start = Long.MIN_VALUE;
        float length;
        float attack;
        float release;
        float strength;
        boolean stutter;
        long seed;

        void start(long now, float length, float attack, float release, float strength, boolean stutter, long seed) {
            this.start = now;
            this.length = Math.max(1.0F, length);
            this.attack = attack;
            this.release = release;
            this.strength = Mth.clamp(strength, 0.0F, 1.0F);
            this.stutter = stutter;
            this.seed = seed;
        }

        float value(double now) {
            if (start == Long.MIN_VALUE) {
                return 0.0F;
            }
            double age = now - start;
            if (age < 0.0 || age >= length) {
                return 0.0F;
            }
            float in = attack <= 0.0F ? 1.0F : smooth((float) (age / attack));
            float out = release <= 0.0F ? 1.0F : smooth((float) ((length - age) / release));
            float v = strength * Math.min(in, out);
            if (stutter) {
                long step = (long) Math.floor(age / 2.0);
                v *= hash01(seed, step) < 0.55F ? 1.0F : 0.15F;
            }
            return v;
        }

        void clear() {
            start = Long.MIN_VALUE;
        }
    }

    // ------------------------------------------------------------------ effects

    /** {@code dimension.blackout}: the sky and every light of the dimension go dark for 1-3 seconds. */
    static void onBlackout(ClientEffectPayload effect) {
        if (ClientLimits.maxVisualEffects() <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float intensity = Mth.clamp(effect.intensity(), 0.0F, 1.0F);
        int length = 20 + (int) (hash01(effect.seed(), 7L) * 40.0F);
        if (mc.level == null || !NullDimensions.isNull(mc.level)) {
            // Outside NULL (an administrator forced it) it is an ordinary blackout.
            VisualDistortionManager.add(VisualDistortionManager.Kind.BLACKOUT, 0.6F + 0.4F * intensity, length, effect.seed());
            return;
        }
        float flashing = Mth.clamp(ClientLimits.flashing(), 0.0F, 1.0F);
        float attack = Mth.lerp(flashing, 16.0F, 2.0F);
        float release = Mth.lerp(flashing, 26.0F, 9.0F);
        BLACKOUT.start(clock, Math.max(length, attack + release + 6.0F), attack, release, 0.85F + 0.15F * intensity, false,
                effect.seed());
    }

    /** {@code dimension.pulse}: a wave of red, green and blue light runs through the dimension. */
    static void onPulse(ClientEffectPayload effect) {
        if (ClientLimits.maxVisualEffects() <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float intensity = Mth.clamp(effect.intensity(), 0.0F, 1.0F);
        if (mc.level == null || !NullDimensions.isNull(mc.level)) {
            VisualDistortionManager.add(VisualDistortionManager.Kind.COLOR_SHIFT, 0.25F + 0.25F * intensity, 50, effect.seed());
            VisualDistortionManager.add(VisualDistortionManager.Kind.RGB_SPLIT, 0.3F * intensity, 30, effect.seed());
            return;
        }
        float flashing = Mth.clamp(ClientLimits.flashing(), 0.0F, 1.0F);
        pulseStart = clock;
        pulseLength = Mth.lerp(flashing, 96.0F, 46.0F);
        pulseStrength = (0.3F + 0.7F * flashing) * (0.6F + 0.4F * intensity);
        VisualDistortionManager.add(VisualDistortionManager.Kind.RGB_SPLIT, 0.22F * pulseStrength, (int) (pulseLength * 0.6F), effect.seed());
    }

    // ------------------------------------------------------------------ queries

    /** The atmosphere clock in ticks, smoothed by the partial tick. */
    static double time(float partial) {
        return clock + partial;
    }

    /** 0..1: how dark the geometry blackout has made everything. */
    static float blackoutLevel(float partial) {
        return BLACKOUT.value(clock + partial);
    }

    /** 0..1 multiplier for the whole sky (blackouts, stutters). */
    static float skyBrightness(float partial) {
        double now = clock + partial;
        return (1.0F - BLACKOUT.value(now)) * (1.0F - SKY.value(now));
    }

    /**
     * The colour of the RGB wave right now (hue runs red, green, blue) written into {@code rgb},
     * and its strength, 0 when there is no wave.
     */
    static float pulseColor(float partial, float[] rgb) {
        if (pulseStart == Long.MIN_VALUE) {
            rgb[0] = 1.0F;
            rgb[1] = 1.0F;
            rgb[2] = 1.0F;
            return 0.0F;
        }
        float p = (float) ((clock + partial - pulseStart) / pulseLength);
        if (p < 0.0F || p >= 1.0F) {
            rgb[0] = 1.0F;
            rgb[1] = 1.0F;
            rgb[2] = 1.0F;
            return 0.0F;
        }
        int color = Mth.hsvToRgb(p * 0.72F, 0.9F, 1.0F);
        rgb[0] = (color >> 16 & 255) / 255.0F;
        rgb[1] = (color >> 8 & 255) / 255.0F;
        rgb[2] = (color & 255) / 255.0F;
        return pulseStrength * Mth.sin(p * Mth.PI);
    }

    /**
     * Lightmap colours inside NULL: light is colder and more neutral than torchlight, the RGB wave
     * tints it, and the geometry blackout takes it away, glowing blocks included (they read the
     * lightmap at full brightness, which goes dark too).
     */
    static void adjustLight(float partial, Vector3f colors) {
        float r = colors.x();
        float g = colors.y();
        float b = colors.z();
        float lum = 0.2126F * r + 0.7152F * g + 0.0722F * b;
        r = Mth.lerp(0.5F, r, lum * 0.9F);
        g = Mth.lerp(0.5F, g, lum * 0.98F);
        b = Mth.lerp(0.5F, b, Math.min(1.0F, lum * 1.12F));
        float wave = pulseColor(partial, TINT);
        if (wave > 0.0F) {
            float bright = Math.max(lum, 0.25F) * (1.0F + 0.6F * wave);
            float k = Math.min(1.0F, wave * 0.75F);
            r = Mth.lerp(k, r, TINT[0] * bright);
            g = Mth.lerp(k, g, TINT[1] * bright);
            b = Mth.lerp(k, b, TINT[2] * bright);
        }
        float dark = BLACKOUT.value(clock + partial);
        if (dark > 0.0F) {
            float k = 1.0F - 0.97F * dark;
            r *= k;
            g *= k;
            b *= k;
        }
        colors.set(r, g, b);
    }

    // ------------------------------------------------------------------ ticking

    static void tick(Minecraft mc, boolean inNull, boolean transitioning) {
        if (mc.level == null) {
            return;
        }
        if (!mc.isPaused()) {
            clock++;
        }
        if (!inNull || transitioning) {
            nextAmbient = -1L;
            return;
        }
        if (nextAmbient < 0L) {
            nextAmbient = clock + 300 + RANDOM.nextInt(600);
            return;
        }
        if (clock < nextAmbient) {
            return;
        }
        nextAmbient = clock + 400 + RANDOM.nextInt(1000);
        if (ClientLimits.maxVisualEffects() <= 0) {
            return;
        }
        float flashing = Mth.clamp(ClientLimits.flashing(), 0.0F, 1.0F);
        boolean strobe = ClientLimits.intenseAllowed() && flashing >= 0.6F;
        if (RANDOM.nextFloat() < 0.75F) {
            if (strobe) {
                // The sky stutters for a moment.
                SKY.start(clock, 6 + RANDOM.nextInt(10), 0.5F, 1.0F, 0.6F + 0.35F * RANDOM.nextFloat(), true, RANDOM.nextLong());
            } else {
                SKY.start(clock, 40 + RANDOM.nextInt(20), 14.0F, 18.0F, 0.45F, false, RANDOM.nextLong());
            }
        } else {
            // The sky goes out completely.
            float attack = Mth.lerp(flashing, 16.0F, 3.0F);
            float release = Mth.lerp(flashing, 24.0F, 10.0F);
            SKY.start(clock, Math.max(20 + RANDOM.nextInt(41), attack + release + 6.0F), attack, release, 1.0F, false, RANDOM.nextLong());
        }
    }

    /** Leaving NULL, a transition or logging out: nothing carries over. */
    static void reset() {
        BLACKOUT.clear();
        SKY.clear();
        pulseStart = Long.MIN_VALUE;
        pulseStrength = 0.0F;
        nextAmbient = -1L;
    }

    // ------------------------------------------------------------------ helpers

    static float smooth(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    static float hash01(long seed, long index) {
        long h = seed + index * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }
}
