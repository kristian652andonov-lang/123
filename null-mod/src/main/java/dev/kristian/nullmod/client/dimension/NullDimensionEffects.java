package dev.kristian.nullmod.client.dimension;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Client-side special effects of the NULL dimension ({@code "effects": "null:null"} in its
 * dimension type): NULL's own sky, no clouds, no weather, dark fog and the lightmap hooks of the
 * blackout and the RGB wave. The sky type stays {@link SkyType#NORMAL} so the screen-space sky
 * tint and flicker of the visual system also apply here.
 */
public final class NullDimensionEffects extends DimensionSpecialEffects {
    public NullDimensionEffects() {
        super(Float.NaN, false, SkyType.NORMAL, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return NullFog.baseColor();
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }

    /** No sunrise or sunset ever tints the fog here. */
    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        return null;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        NullSkyRenderer.render(partialTick, modelViewMatrix, projectionMatrix, camera, setupFog);
        return true;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX, double camY,
                                double camZ, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        return true;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture, double camX,
                                     double camY, double camZ) {
        return true;
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return true;
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker,
                                     float skyLight, int pixelX, int pixelY, Vector3f colors) {
        DimensionAtmosphere.adjustLight(partialTicks, colors);
    }
}
