package dev.kristian.nullmod.client.stalker;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kristian.nullmod.NullMod;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * The figure drawn without an entity behind it, for client-only apparitions (the runner of the NULL
 * stage in {@code client.visual.SilhouetteRenderer}). Same model, texture and eyes as the entity,
 * placed exactly as {@code LivingEntityRenderer} places a mob. Render thread only.
 */
public final class StalkerFigure {
    @Nullable
    private static StalkerModel model;
    private static boolean failed;

    private StalkerFigure() {
    }

    /** The model, baked on first use; null if its layer could not be baked (then callers fall back). */
    @Nullable
    private static StalkerModel model() {
        if (model == null && !failed) {
            try {
                model = new StalkerModel(Minecraft.getInstance().getEntityModels().bakeLayer(StalkerClientModule.LAYER));
            } catch (RuntimeException e) {
                failed = true;
                NullMod.LOGGER.warn("NULL: the figure's model is not available; apparitions fall back to flat figures", e);
            }
        }
        return model;
    }

    public static boolean available() {
        return model() != null;
    }

    /**
     * Draws the figure with its feet at the origin of {@code poseStack}, facing {@code yawDegrees}
     * (Minecraft's yaw: 0 faces +Z).
     *
     * @param alpha 0..1, fading the whole figure out
     * @param eyes  0..1 brightness of the eyes
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffers, float yawDegrees, StalkerModel.Pose pose,
                              int packedLight, float alpha, float eyes) {
        StalkerModel figure = model();
        if (figure == null || alpha <= 0.0F) {
            return;
        }
        figure.apply(pose);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yawDegrees));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        VertexConsumer body = buffers.getBuffer(RenderType.entityTranslucent(StalkerRenderer.TEXTURE));
        figure.renderToBuffer(poseStack, body, packedLight, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
        if (eyes > 0.0F) {
            int v = Mth.clamp((int) (eyes * alpha * 255.0F), 0, 255);
            VertexConsumer glow = buffers.getBuffer(RenderType.eyes(StalkerRenderer.EYES));
            figure.renderToBuffer(poseStack, glow, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(255, v, v, v));
        }
        poseStack.popPose();
    }
}
