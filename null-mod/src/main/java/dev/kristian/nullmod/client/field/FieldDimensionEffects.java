package dev.kristian.nullmod.client.field;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Client-side special effects of the field ({@code "effects": "null:field"} in its dimension type):
 * the static, cracking sun and the hazy sky, no clouds, no weather, and day and night through the
 * lightmap and the fog rather than the dimension's (fixed) time.
 */
public final class FieldDimensionEffects extends DimensionSpecialEffects {
    private final float[] fog = new float[3];

    public FieldDimensionEffects() {
        super(Float.NaN, true, SkyType.NORMAL, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        FieldAtmosphere.fogColor(0.0F, fog);
        return new Vec3(fog[0], fog[1], fog[2]);
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }

    /** No sunrise or sunset ever tints the fog: the sun does not move. */
    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        return null;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        FieldSkyRenderer.render(partialTick, modelViewMatrix, projectionMatrix, camera, setupFog);
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
        FieldAtmosphere.adjustLight(partialTicks, skyDarken, skyLight, colors);
    }
}
