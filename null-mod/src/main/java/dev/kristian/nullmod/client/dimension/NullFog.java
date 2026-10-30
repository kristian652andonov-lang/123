package dev.kristian.nullmod.client.dimension;

import com.mojang.blaze3d.shaders.FogShape;
import dev.kristian.nullmod.registry.NullDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * NULL's fog: almost black with a trace of violet, starting close and thinning out over the whole
 * view distance, so the structure fades gradually into the dark instead of stopping at a wall:
 * deep perspective. Registered with high priority so the visual system's fog effects apply on
 * top of it. Water and lava keep their own fog.
 */
final class NullFog {
    private static final Vec3 BASE = new Vec3(0.006, 0.004, 0.016);
    private static final float[] TINT = new float[3];

    private NullFog() {
    }

    static Vec3 baseColor() {
        return BASE;
    }

    static void onRenderFog(ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !NullDimensions.isNull(mc.level)
                || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE) {
            return;
        }
        float dark = DimensionAtmosphere.blackoutLevel((float) event.getPartialTick());
        float far = event.getFarPlaneDistance() * (1.0F - 0.45F * dark);
        float near = Math.min(far * 0.12F, far - 1.0F);
        event.setNearPlaneDistance(Math.max(0.0F, near));
        event.setFarPlaneDistance(Math.max(2.0F, far));
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !NullDimensions.isNull(mc.level) || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float partial = (float) event.getPartialTick();
        float wave = DimensionAtmosphere.pulseColor(partial, TINT);
        float light = 1.0F - DimensionAtmosphere.blackoutLevel(partial);
        float r = (float) BASE.x + TINT[0] * 0.07F * wave;
        float g = (float) BASE.y + TINT[1] * 0.07F * wave;
        float b = (float) BASE.z + TINT[2] * 0.07F * wave;
        event.setRed(r * light);
        event.setGreen(g * light);
        event.setBlue(b * light);
    }
}
