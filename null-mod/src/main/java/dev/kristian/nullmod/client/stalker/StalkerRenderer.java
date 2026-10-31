package dev.kristian.nullmod.client.stalker;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.entity.stalker.StalkerEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * Draws the figure: a black, matte body lit like any mob (so in the dark it is only a deeper
 * darkness), and on top of it two pin-prick eyes that need no light at all. No name, no fall when
 * it goes, a faint narrow shadow.
 */
public final class StalkerRenderer extends MobRenderer<StalkerEntity, StalkerModel> {
    static final ResourceLocation TEXTURE = NullMod.id("textures/entity/stalker/stalker.png");
    static final ResourceLocation EYES = NullMod.id("textures/entity/stalker/stalker_eyes.png");

    public StalkerRenderer(EntityRendererProvider.Context context) {
        super(context, new StalkerModel(context.bakeLayer(StalkerClientModule.LAYER)), 0.3F);
        this.shadowStrength = 0.55F;
        this.addLayer(new EyesLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(StalkerEntity entity) {
        return TEXTURE;
    }

    /** It does not fall over when it goes; it is simply not there. */
    @Override
    protected float getFlipDegrees(StalkerEntity entity) {
        return 0.0F;
    }

    /** The eyes: additive and full bright, steady, flaring a little when its head twitches. */
    private static final class EyesLayer extends RenderLayer<StalkerEntity, StalkerModel> {
        EyesLayer(StalkerRenderer parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, StalkerEntity entity, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (entity.isInvisible()) {
                return;
            }
            float twitch = Mth.lerp(partialTick, entity.animation.twitchO, entity.animation.twitch);
            int v = Mth.clamp((int) ((0.86F + 0.14F * twitch) * 255.0F), 0, 255);
            VertexConsumer consumer = buffers.getBuffer(RenderType.eyes(EYES));
            this.getParentModel().renderToBuffer(poseStack, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    FastColor.ARGB32.color(255, v, v, v));
        }
    }
}
