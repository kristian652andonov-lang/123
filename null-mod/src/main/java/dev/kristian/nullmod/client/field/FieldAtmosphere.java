package dev.kristian.nullmod.client.field;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import dev.kristian.nullmod.field.FieldCycle;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Light, fog colour and the sun's glow in the field, derived from {@link FieldClientState}:
 * <ul>
 * <li>the day is pale and a little warm, and dims as the sun cracks;</li>
 * <li>the glow of the sun fails: it sinks with the cracks and, late in the day, stutters (slow fades
 * when the player turned flashing down);</li>
 * <li>when the sun breaks its core flares (only as bright as the flashing setting allows, never with
 * intense effects off) and the sky light goes out within seconds;</li>
 * <li>at night only a trace of sky light is left, while block light (torches, the candles in the
 * houses) works as always.</li>
 * </ul>
 * Render thread only.
 */
final class FieldAtmosphere {
    /** The haze of the day, also the colour of the horizon and of the distance. */
    static final float[] DAY_FOG = {0.735F, 0.722F, 0.655F};
    static final float[] NIGHT_FOG = {0.004F, 0.004F, 0.006F};

    private FieldAtmosphere() {
    }

    /** A phase began; seen live, the shattering also shakes the world. */
    static void onPhase(FieldCycle.Phase phase, boolean live) {
        if (live && phase == FieldCycle.Phase.SHATTER) {
            // Scaled down (or off) by the player's shake settings inside the visual system.
            VisualDistortionManager.shake(0.5F, 45);
        }
    }

    /**
     * 0..1 the flare of the sun's core as it breaks: a burst and a long decay, or with low flashing
     * a slow swell; capped low with intense effects off. Derived from the phase time alone, so
     * someone joining late never sees it.
     */
    static float flare(float partial) {
        if (!FieldClientState.active() || FieldClientState.phase() != FieldCycle.Phase.SHATTER) {
            return 0.0F;
        }
        float flashing = Mth.clamp(ClientLimits.flashing(), 0.0F, 1.0F);
        float t = FieldClientState.elapsed(partial);
        float attack = Mth.lerp(flashing, 22.0F, 3.0F);
        float release = Mth.lerp(flashing, 45.0F, 35.0F);
        float envelope = t < attack ? t / attack : Math.max(0.0F, 1.0F - (t - attack) / release);
        envelope = envelope * envelope * (3.0F - 2.0F * envelope);
        float peak = 0.2F + 0.8F * flashing;
        if (!ClientLimits.intenseAllowed()) {
            peak = Math.min(peak, 0.3F);
        }
        return peak * envelope;
    }

    /** 0..1 strength of the sun's glow right now. */
    static float glow(float partial) {
        if (!FieldClientState.active()) {
            return 1.0F;
        }
        FieldCycle.Phase phase = FieldClientState.phase();
        float progress = FieldClientState.progress(partial);
        return switch (phase) {
            case DAY -> {
                float crack = FieldClientState.crack(partial);
                yield (1.0F - 0.62F * crack) * (1.0F - stutter(partial, crack));
            }
            case SHATTER -> 0.38F * Math.max(0.0F, 1.0F - progress * 3.0F);
            case NIGHT, HUNT -> 0.0F;
            case DAWN -> FieldClientState.smooth(0.35F, 1.0F, progress);
        };
    }

    /**
     * The glow failing for a moment: now and then (more often as the cracks grow) it sinks and
     * recovers. A quick dip when flashing is allowed, a slow, shallow sag when it is not.
     */
    private static float stutter(float partial, float crack) {
        if (crack < 0.45F) {
            return 0.0F;
        }
        float flashing = Mth.clamp(ClientLimits.flashing(), 0.0F, 1.0F);
        double time = FieldClientState.time(partial);
        int period = 50;
        long segment = (long) Math.floor(time / period);
        float total = 0.0F;
        for (long k = segment - 1; k <= segment; k++) {
            float chance = (crack - 0.45F) * 1.3F;
            if (FieldClientState.hash01(FieldClientState.seed() ^ 0x5455L, k) >= chance) {
                continue;
            }
            double center = k * period + 10 + FieldClientState.hash01(FieldClientState.seed() ^ 0x5456L, k) * 30.0;
            double width = Mth.lerp(flashing, 22.0F, 5.0F);
            double d = Math.abs(time - center) / width;
            if (d < 1.0) {
                float bump = (float) (1.0 - d * d);
                total = Math.max(total, bump * bump * (0.25F + 0.45F * flashing));
            }
        }
        return total;
    }

    /**
     * Lightmap colours in the field: the share of sky light the phase leaves (all of it by day, a
     * trace at night), the pale warmth of the day; block light is never touched.
     *
     * @param skyDarken vanilla's sky darkness factor (1 at the field's fixed noon)
     * @param skyLight  brightness of this row's sky light level as vanilla computed it
     */
    static void adjustLight(float partial, float skyDarken, float skyLight, Vector3f colors) {
        if (!FieldClientState.active()) {
            return;
        }
        float day = FieldClientState.daylight(partial);
        float keep = FieldClientState.NIGHT_SKY_LIGHT + (1.0F - FieldClientState.NIGHT_SKY_LIGHT) * day;
        // Vanilla added 0.96 * skyLight * (tint, tint, 1) for the sky; take away what the field does not keep.
        float tint = skyDarken * 0.65F + 0.35F;
        float remove = 0.96F * skyLight * (1.0F - keep);
        float r = colors.x() - remove * tint;
        float g = colors.y() - remove * tint;
        float b = colors.z() - remove;
        // The day's light is pale and faintly warm, like light through haze.
        float lum = 0.2126F * r + 0.7152F * g + 0.0722F * b;
        float pale = 0.22F * day;
        r = Mth.lerp(pale, r, lum * 1.05F);
        g = Mth.lerp(pale, g, lum * 1.0F);
        b = Mth.lerp(pale, b, lum * 0.88F);
        colors.set(Math.max(0.0F, r), Math.max(0.0F, g), Math.max(0.0F, b));
    }

    /** The fog (and clear) colour: the day's haze, darkening with the light, black at night. */
    static void fogColor(float partial, float[] out) {
        float day = FieldClientState.daylight(partial);
        float flare = flare(partial);
        for (int i = 0; i < 3; i++) {
            out[i] = Mth.lerp(day, NIGHT_FOG[i], DAY_FOG[i]);
        }
        // The flare of the breaking sun lights the haze for a moment.
        out[0] = Math.min(1.0F, out[0] + 0.35F * flare);
        out[1] = Math.min(1.0F, out[1] + 0.3F * flare);
        out[2] = Math.min(1.0F, out[2] + 0.22F * flare);
    }
}
