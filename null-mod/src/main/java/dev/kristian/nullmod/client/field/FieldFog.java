package dev.kristian.nullmod.client.field;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The field's fog: by day a pale haze that swallows the endless field long before the horizon; as
 * the sun dies it closes in until the wheat right around you is all there is, and lifts again at
 * dawn. The distance is set after the visual system's fog effects (from what they left, also when
 * they cancelled the event to apply it), the colour before them, so both still apply in the field.
 * Water and lava keep their own fog.
 */
final class FieldFog {
    /** How far one can see at night. */
    private static final float NIGHT_FAR = 22.0F;
    private static final float[] COLOR = new float[3];

    private FieldFog() {
    }

    static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!FieldClientState.active() || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE) {
            return;
        }
        float night = FieldClientState.nightFog((float) event.getPartialTick());
        float far = event.getFarPlaneDistance();
        float dayNear = far * 0.3F;
        float nightFar = Math.min(far, NIGHT_FAR);
        float end = Mth.lerp(night, far, nightFar);
        float start = Mth.lerp(night, dayNear, 0.5F);
        event.setNearPlaneDistance(Math.max(0.0F, Math.min(start, end - 1.0F)));
        event.setFarPlaneDistance(Math.max(2.0F, end));
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!FieldClientState.active() || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        FieldAtmosphere.fogColor((float) event.getPartialTick(), COLOR);
        event.setRed(COLOR[0]);
        event.setGreen(COLOR[1]);
        event.setBlue(COLOR[2]);
    }
}
