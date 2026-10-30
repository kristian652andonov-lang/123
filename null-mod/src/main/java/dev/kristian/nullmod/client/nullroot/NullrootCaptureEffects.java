package dev.kristian.nullmod.client.nullroot;

import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * What the captured player goes through, driven by the synced capture progress of the organism
 * holding them: the legs are caught (a jolt), the camera starts shaking, tendrils close in over
 * the edges of the screen, the picture warps and splits, the sound bends, the world darkens. All
 * of it goes through {@link VisualDistortionManager} and {@link AudioCorruptionManager}, so every
 * accessibility setting applies, and all of it is let go the moment the capture ends (or the
 * player leaves the world or the dimension).
 */
final class NullrootCaptureEffects {
    private static float reported = -1.0F;
    private static boolean active;
    private static int ticks;
    @Nullable
    private static ClientLevel lastLevel;

    private NullrootCaptureEffects() {
    }

    /** An organism holds the local player this tick, this far into the capture. */
    static void report(float progress) {
        reported = Math.max(reported, progress);
    }

    static boolean active() {
        return active;
    }

    /** After the organisms have ticked (client tick end). */
    static void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            end();
        }
        if (minecraft.level == null || minecraft.player == null) {
            reported = -1.0F;
            end();
            return;
        }
        if (minecraft.isPaused()) {
            // Entities did not tick, so nobody reported; keep everything as it is.
            return;
        }
        float progress = reported;
        reported = -1.0F;
        if (progress < 0.0F) {
            end();
            return;
        }
        if (!active) {
            active = true;
            ticks = 0;
            // The grab itself.
            VisualDistortionManager.add(VisualDistortionManager.Kind.CAMERA_JERK, 0.35F, 8);
        }
        apply(progress);
        ticks++;
    }

    private static void apply(float p) {
        VisualDistortionManager.setCaptureOverlay(NullrootGeometry.smooth((p - 0.12F) / 0.88F));
        AudioCorruptionManager.setCaptureDistortion(NullrootGeometry.smooth((p - 0.3F) / 0.65F));
        if (p > 0.06F && ticks % 10 == 0) {
            VisualDistortionManager.shake(0.08F + 0.42F * p, 16);
        }
        if (p > 0.4F && ticks % 30 == 5) {
            VisualDistortionManager.add(VisualDistortionManager.Kind.WARP, 0.08F + 0.3F * (p - 0.4F) / 0.6F, 40);
        }
        if (p > 0.5F && ticks % 30 == 15) {
            VisualDistortionManager.add(VisualDistortionManager.Kind.RGB_SPLIT, 0.1F + 0.35F * (p - 0.5F) / 0.5F, 40);
        }
        if (p > 0.65F && ticks % 30 == 25) {
            VisualDistortionManager.add(VisualDistortionManager.Kind.DARKEN, 0.12F + 0.45F * (p - 0.65F) / 0.35F, 40);
        }
    }

    /** Lets go of the overlay and the audio distortion (the short distortions fade by themselves). */
    private static void end() {
        if (!active) {
            return;
        }
        active = false;
        ticks = 0;
        VisualDistortionManager.setCaptureOverlay(0.0F);
        AudioCorruptionManager.setCaptureDistortion(0.0F);
    }

    static void reset() {
        reported = -1.0F;
        end();
        lastLevel = null;
    }
}
