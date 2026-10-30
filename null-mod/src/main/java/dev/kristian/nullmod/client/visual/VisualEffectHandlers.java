package dev.kristian.nullmod.client.visual;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.network.ClientEffectPayload;
import net.minecraft.util.Mth;

import static dev.kristian.nullmod.client.visual.VisualDistortionManager.BLACKOUT;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.CAMERA_JERK;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.CH_BANDS;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.CH_SKY_FLICKER;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.CH_TEAR;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.COLOR_SHIFT;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.DARKEN;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.DESATURATE;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.FLASH;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.FOV_PULSE;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.NOISE;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.RGB_SPLIT;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.SCANLINES;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.SHAKE;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.SKY_TINT;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.STYLE_FLICKER;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.STYLE_PULSE;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.STYLE_SMOOTH;
import static dev.kristian.nullmod.client.visual.VisualDistortionManager.WARP;

/**
 * The client side of every {@code visual.*} and {@code block.*} effect. Each handler turns the
 * payload's intensity (already scaled by stage, region and the server's intensity setting) into
 * a combination of distortions: faint in Stage 1, violent in Stage 5.
 */
final class VisualEffectHandlers {
    private VisualEffectHandlers() {
    }

    static void register() {
        ClientEffects.register("visual.distort", VisualEffectHandlers::distort);
        ClientEffects.register("visual.shake", VisualEffectHandlers::shake);
        ClientEffects.register("visual.camera_jerk", VisualEffectHandlers::cameraJerk);
        ClientEffects.register("visual.screen_distortion", VisualEffectHandlers::screenDistortion);
        ClientEffects.register("visual.freeze", VisualEffectHandlers::freeze);
        ClientEffects.register("visual.blackout", VisualEffectHandlers::blackout);
        ClientEffects.register("visual.full_blackout", VisualEffectHandlers::fullBlackout);
        ClientEffects.register("visual.sky_color", VisualEffectHandlers::skyColor);
        ClientEffects.register("visual.sky_flicker", VisualEffectHandlers::skyFlicker);
        ClientEffects.register("visual.terrain_shift", VisualEffectHandlers::terrainShift);
        ClientEffects.register("visual.darken", VisualEffectHandlers::darken);
        ClientEffects.register("visual.desaturate", VisualEffectHandlers::desaturate);
        ClientEffects.register("visual.rgb_storm", VisualEffectHandlers::rgbStorm);
        ClientEffects.register("visual.glitch_text", VisualEffectHandlers::glitchText);
        ClientEffects.register("visual.flash", VisualEffectHandlers::flash);
        ClientEffects.register("visual.chunk_flicker",
                p -> ChunkFlickerRenderer.start(p.intensity(), p.duration(), p.seed()));
        ClientEffects.register("visual.silhouette",
                p -> SilhouetteRenderer.spawn(p.intensity(), p.duration(), p.seed(), false));
        ClientEffects.register("visual.distant_movement",
                p -> SilhouetteRenderer.spawn(p.intensity(), p.duration(), p.seed(), true));
        ClientEffects.register("visual.fake_disconnect", p -> FakeDisconnectScreen.request(p.duration()));

        ClientEffects.register("block.black", ClientBlockGlitchManager::black);
        ClientEffects.register("block.missing", ClientBlockGlitchManager::missing);
        ClientEffects.register("block.error", ClientBlockGlitchManager::error);
        ClientEffects.register("block.vanish", ClientBlockGlitchManager::vanish);
        ClientEffects.register("block.wrong", ClientBlockGlitchManager::wrong);
        ClientEffects.register("block.tree", ClientBlockGlitchManager::tree);
        ClientEffects.register("block.terrain_gap", ClientBlockGlitchManager::terrainGap);
        ClientEffects.register("block.structure_flicker", ClientBlockGlitchManager::structureFlicker);
        ClientEffects.register("block.displace", ClientBlockGlitchManager::displace);
        ClientEffects.register("block.structure_vanish", ClientBlockGlitchManager::structureVanish);
    }

    private static float intensity(ClientEffectPayload p) {
        return Mth.clamp(p.intensity(), 0.0F, 1.0F);
    }

    private static int duration(ClientEffectPayload p, int min, int max) {
        return Mth.clamp(p.duration(), min, max);
    }

    private static long seed(ClientEffectPayload p, long salt) {
        return SmoothNoise.mix(p.seed() + salt * 0x9E3779B97F4A7C15L);
    }

    /** texture_distortion: the picture swims and its colours slip. Barely there in Stage 1. */
    private static void distort(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            int d = duration(p, 30, 200);
            VisualDistortionManager.addEffect(WARP, 0.12F + 0.6F * i, d, seed(p, 1), STYLE_SMOOTH, d * 0.3F, d * 0.4F, 0.0F);
            if (i > 0.3F) {
                VisualDistortionManager.addEffect(COLOR_SHIFT, 0.08F + 0.3F * i, d, seed(p, 2), STYLE_SMOOTH, d * 0.3F, d * 0.4F, 0.0F);
            }
            if (i > 0.55F) {
                VisualDistortionManager.addEffect(NOISE, 0.1F + 0.2F * i, d, seed(p, 3), STYLE_SMOOTH, -1.0F, -1.0F, 0.0F);
            }
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void shake(ClientEffectPayload p) {
        float i = intensity(p);
        VisualDistortionManager.addEffect(SHAKE, 0.22F + 0.65F * i, duration(p, 10, 200), p.seed(), STYLE_SMOOTH, 3.0F, -1.0F, 0.0F);
    }

    private static void cameraJerk(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            VisualDistortionManager.add(VisualDistortionManager.Kind.CAMERA_JERK, 0.45F + 0.55F * i, duration(p, 8, 40), p.seed());
            if (i > 0.7F) {
                // Strong ones are followed by a smaller after-jerk.
                VisualDistortionManager.addEffect(CAMERA_JERK, 0.25F * i, duration(p, 8, 40), seed(p, 7), VisualDistortionManager.STYLE_SNAP,
                        0.0F, 0.0F, 0.0F, 6 + (int) (p.seed() & 7L));
            }
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void screenDistortion(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            int d = duration(p, 20, 160);
            VisualDistortionManager.addEffect(WARP, 0.35F + 0.5F * i, d, seed(p, 1), STYLE_SMOOTH, 4.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(SCANLINES, 0.3F + 0.5F * i, d, seed(p, 2), STYLE_SMOOTH, 3.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(RGB_SPLIT, 0.2F + 0.5F * i, d, seed(p, 3), STYLE_PULSE, 3.0F, -1.0F, 0.7F);
            VisualDistortionManager.addEffect(CH_TEAR, 0.3F + 0.6F * i, d, seed(p, 4), STYLE_SMOOTH, 2.0F, -1.0F, 0.0F);
            if (i > 0.5F) {
                VisualDistortionManager.addEffect(NOISE, 0.2F + 0.4F * i, d, seed(p, 5), STYLE_SMOOTH, 3.0F, -1.0F, 0.0F);
                VisualDistortionManager.addEffect(FOV_PULSE, 0.25F * i, d, seed(p, 6), STYLE_SMOOTH, 6.0F, -1.0F, 0.0F);
            }
            VisualSounds.tear(i);
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void freeze(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            int d = duration(p, 20, 100);
            if (VisualDistortionManager.freeze(d, p.seed())) {
                VisualSounds.freeze(d);
                // When it lets go, the picture snaps back with a short burst of damage.
                VisualDistortionManager.addEffect(RGB_SPLIT, 0.6F, 8, seed(p, 1), STYLE_SMOOTH, 0.5F, 6.0F, 0.0F, d);
                VisualDistortionManager.addEffect(CH_TEAR, 0.6F, 6, seed(p, 2), STYLE_SMOOTH, 0.5F, 4.0F, 0.0F, d);
            }
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void blackout(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            int d = duration(p, 10, 60);
            VisualDistortionManager.add(VisualDistortionManager.Kind.BLACKOUT, 0.88F + 0.12F * i, d, p.seed());
            // The world comes back a little darker than it was, then recovers.
            VisualDistortionManager.addEffect(DARKEN, 0.35F, d + 30, seed(p, 1), STYLE_SMOOTH, d * 0.6F, 24.0F, 0.0F);
            VisualSounds.blackout(i, false, d);
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void fullBlackout(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            int d = duration(p, 40, 200);
            VisualDistortionManager.addEffect(BLACKOUT, 1.0F, d, p.seed(), STYLE_SMOOTH, 2.0F, Math.min(30.0F, d * 0.3F), 0.0F);
            VisualDistortionManager.addEffect(DARKEN, 0.4F, d + 50, seed(p, 1), STYLE_SMOOTH, d * 0.7F, 40.0F, 0.0F);
            VisualSounds.blackout(i, true, d);
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void skyColor(ClientEffectPayload p) {
        float i = intensity(p);
        int d = duration(p, 100, 1200);
        VisualDistortionManager.addEffect(SKY_TINT, 0.35F + 0.65F * i, d, p.seed(), STYLE_SMOOTH, Math.min(80.0F, d * 0.25F),
                Math.min(100.0F, d * 0.3F), 0.0F);
    }

    private static void skyFlicker(ClientEffectPayload p) {
        float i = intensity(p);
        int d = duration(p, 4, 30);
        float segment = 1.0F + (p.seed() & 3L) * 0.5F;
        VisualDistortionManager.addEffect(CH_SKY_FLICKER, 0.75F + 0.25F * i, d, p.seed(), STYLE_FLICKER, 0.0F, 1.0F, segment);
        VisualSounds.skyFlicker(i);
    }

    private static void terrainShift(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            int d = duration(p, 15, 120);
            VisualDistortionManager.addEffect(CH_BANDS, 0.4F + 0.6F * i, d, p.seed(), STYLE_SMOOTH, 1.5F, Math.min(12.0F, d * 0.3F), 0.0F);
            VisualDistortionManager.addEffect(RGB_SPLIT, 0.12F + 0.2F * i, d, seed(p, 1), STYLE_SMOOTH, 1.5F, -1.0F, 0.0F);
            VisualSounds.tear(i);
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void darken(ClientEffectPayload p) {
        float i = intensity(p);
        int d = duration(p, 40, 600);
        VisualDistortionManager.addEffect(DARKEN, 0.3F + 0.45F * i, d, p.seed(), STYLE_SMOOTH, Math.min(40.0F, d * 0.3F),
                Math.min(60.0F, d * 0.4F), 0.0F);
    }

    private static void desaturate(ClientEffectPayload p) {
        float i = intensity(p);
        int d = duration(p, 60, 1200);
        VisualDistortionManager.addEffect(DESATURATE, 0.45F + 0.5F * i, d, p.seed(), STYLE_SMOOTH, Math.min(80.0F, d * 0.3F),
                Math.min(100.0F, d * 0.35F), 0.0F);
    }

    private static void rgbStorm(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            int d = duration(p, 30, 200);
            if (!ClientLimits.intenseAllowed()) {
                // Without intense effects a storm is only a slow, mild colour fringe.
                VisualDistortionManager.addEffect(RGB_SPLIT, 0.3F, d, p.seed(), STYLE_SMOOTH, 15.0F, 25.0F, 0.0F);
                return;
            }
            VisualDistortionManager.addEffect(RGB_SPLIT, 0.85F + 0.15F * i, d, seed(p, 1), STYLE_PULSE, 2.0F, -1.0F, 1.1F);
            VisualDistortionManager.addEffect(COLOR_SHIFT, 0.5F + 0.4F * i, d, seed(p, 2), STYLE_PULSE, 3.0F, -1.0F, 0.45F);
            VisualDistortionManager.addEffect(NOISE, 0.45F + 0.3F * i, d, seed(p, 3), STYLE_SMOOTH, 2.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(SCANLINES, 0.4F + 0.3F * i, d, seed(p, 4), STYLE_SMOOTH, 2.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(CH_TEAR, 0.6F + 0.4F * i, d, seed(p, 5), STYLE_FLICKER, 0.0F, 4.0F, 2.0F);
            VisualDistortionManager.addEffect(CH_BANDS, 0.35F + 0.3F * i, d, seed(p, 6), STYLE_SMOOTH, 2.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(SHAKE, 0.2F + 0.25F * i, d, seed(p, 7), STYLE_SMOOTH, 4.0F, -1.0F, 0.0F);
            VisualSounds.glitch(i);
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void glitchText(ClientEffectPayload p) {
        VisualDistortionManager.beginGroup();
        try {
            float i = intensity(p);
            int d = duration(p, 30, 300);
            String text = p.text() == null || p.text().isBlank() ? "NULL" : p.text();
            VisualDistortionManager.showGlitchText(text, d, p.data() != 0);
            VisualDistortionManager.addEffect(RGB_SPLIT, 0.25F + 0.3F * i, d, seed(p, 1), STYLE_SMOOTH, 2.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(NOISE, 0.2F + 0.3F * i, d, seed(p, 2), STYLE_SMOOTH, 2.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(CH_TEAR, 0.3F + 0.4F * i, d, seed(p, 3), STYLE_SMOOTH, 2.0F, -1.0F, 0.0F);
            VisualDistortionManager.addEffect(DARKEN, 0.25F, d, seed(p, 4), STYLE_SMOOTH, 6.0F, -1.0F, 0.0F);
            VisualSounds.glitchText(i);
        } finally {
            VisualDistortionManager.endGroup();
        }
    }

    private static void flash(ClientEffectPayload p) {
        VisualDistortionManager.addEffect(FLASH, Math.max(0.2F, intensity(p)), duration(p, 4, 80), p.seed(), STYLE_SMOOTH,
                1.0F, Math.max(3.0F, p.duration() * 0.7F), 0.0F);
    }
}
