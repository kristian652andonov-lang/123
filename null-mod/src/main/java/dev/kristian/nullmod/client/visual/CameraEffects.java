package dev.kristian.nullmod.client.visual;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.core.CorruptionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Camera-side distortion: shake and jerks (smooth offsets on the camera angles, never random
 * jitter), the FOV pulse, and fog that is tinted, darkened and pulled in.
 */
final class CameraEffects {
    private CameraEffects() {
    }

    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float yaw = VisualDistortionManager.cameraYaw();
        float pitch = VisualDistortionManager.cameraPitch();
        float roll = VisualDistortionManager.cameraRoll();
        if (yaw == 0.0F && pitch == 0.0F && roll == 0.0F) {
            return;
        }
        event.setYaw(event.getYaw() + yaw);
        event.setPitch(Mth.clamp(event.getPitch() + pitch, -90.0F, 90.0F));
        event.setRoll(event.getRoll() + roll);
    }

    static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) {
            // The held-item pass keeps its own FOV, so the hand does not swim.
            return;
        }
        float offset = VisualDistortionManager.fovOffset();
        if (offset != 0.0F) {
            event.setFOV(Mth.clamp(event.getFOV() + offset, 10.0, 160.0));
        }
    }

    static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float tint = VisualDistortionManager.level(VisualDistortionManager.SKY_TINT) * 0.45F;
        float darken = VisualDistortionManager.level(VisualDistortionManager.DARKEN);
        float blackout = VisualDistortionManager.level(VisualDistortionManager.BLACKOUT);
        float desaturate = VisualDistortionManager.level(VisualDistortionManager.DESATURATE) * 0.8F;
        float wrong = stageWrongness();
        if (tint <= 0.0F && darken <= 0.0F && blackout <= 0.0F && desaturate <= 0.0F && wrong <= 0.0F) {
            return;
        }
        float r = event.getRed();
        float g = event.getGreen();
        float b = event.getBlue();
        float lum = 0.299F * r + 0.587F * g + 0.114F * b;
        if (wrong > 0.0F) {
            // Stages 4-5: the air itself looks slightly dead, a grey-green cast.
            r = Mth.lerp(wrong, r, lum * 0.92F);
            g = Mth.lerp(wrong, g, lum * 1.04F);
            b = Mth.lerp(wrong, b, lum * 0.9F);
        }
        if (desaturate > 0.0F) {
            r = Mth.lerp(desaturate, r, lum);
            g = Mth.lerp(desaturate, g, lum);
            b = Mth.lerp(desaturate, b, lum);
        }
        if (tint > 0.0F) {
            float bright = 0.3F + lum;
            r = Mth.lerp(tint, r, VisualDistortionManager.skyTintR() * bright);
            g = Mth.lerp(tint, g, VisualDistortionManager.skyTintG() * bright);
            b = Mth.lerp(tint, b, VisualDistortionManager.skyTintB() * bright);
        }
        float scale = Math.max(0.0F, 1.0F - 0.55F * darken) * (1.0F - 0.95F * blackout);
        event.setRed(Mth.clamp(r * scale, 0.0F, 1.0F));
        event.setGreen(Mth.clamp(g * scale, 0.0F, 1.0F));
        event.setBlue(Mth.clamp(b * scale, 0.0F, 1.0F));
    }

    static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        float pull = Math.max(VisualDistortionManager.level(VisualDistortionManager.DARKEN) * 0.45F,
                VisualDistortionManager.level(VisualDistortionManager.SKY_TINT) * 0.3F);
        pull = Math.max(pull, VisualDistortionManager.level(VisualDistortionManager.BLACKOUT) * 0.85F);
        pull = Math.max(pull, VisualDistortionManager.level(VisualDistortionManager.WARP) * 0.2F);
        pull = Math.max(pull, stageWrongness() * 0.5F);
        if (pull <= 0.002F) {
            return;
        }
        float far = event.getFarPlaneDistance();
        float near = event.getNearPlaneDistance();
        float newFar = Math.max(8.0F, far * (1.0F - 0.6F * pull));
        float newNear = Math.min(newFar - 1.0F, near * (1.0F - 0.85F * pull));
        event.setFarPlaneDistance(newFar);
        event.setNearPlaneDistance(newNear);
        event.setCanceled(true);
    }

    /** 0 below Stage 4; a faint permanent wrongness of the overworld air in Stages 4-5. */
    private static float stageWrongness() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD || !ClientCorruptionState.synced()) {
            return 0.0F;
        }
        CorruptionStage stage = ClientCorruptionState.stage();
        if (stage == CorruptionStage.ABOVE_MIDDLE) {
            return 0.08F + 0.06F * ClientCorruptionState.stageProgress();
        }
        if (stage == CorruptionStage.NULL) {
            return 0.18F;
        }
        return 0.0F;
    }
}
