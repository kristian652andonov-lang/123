package dev.kristian.nullmod.client.it;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.entity.it.ItAnimation;
import dev.kristian.nullmod.entity.it.ItEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * Draws IT at its full six blocks with a broad shadow: the cutout body (torn holes, jagged teeth,
 * both sides of every face drawn), never quite black even in total darkness, and an emissive layer
 * on top (the eyes and the dim red inside of the chest) that needs no light at all. After drawing
 * it remembers where the face was, for the kill camera and the sounds that come out of the mouth.
 */
public final class ItRenderer extends MobRenderer<ItEntity, ItModel> {
    static final ResourceLocation TEXTURE = NullMod.id("textures/entity/it/it.png");
    static final ResourceLocation GLOW = NullMod.id("textures/entity/it/it_glow.png");

    private final float[] face = new float[3];

    public ItRenderer(EntityRendererProvider.Context context) {
        super(context, new ItModel(context.bakeLayer(ItClientModule.LAYER)), 1.7F);
        this.shadowStrength = 0.9F;
        this.addLayer(new GlowLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(ItEntity entity) {
        return TEXTURE;
    }

    /** A shape, barely, even where no light reaches: block light never below 2. */
    @Override
    protected int getBlockLightLevel(ItEntity entity, BlockPos pos) {
        return Math.max(2, super.getBlockLightLevel(entity, pos));
    }

    /** It does not fall over when it dies; it is simply gone. */
    @Override
    protected float getFlipDegrees(ItEntity entity) {
        return 0.0F;
    }

    @Override
    public void render(ItEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
        rememberFace(entity, partialTick);
    }

    /** Model space back to the world: the same turn, flip and lift LivingEntityRenderer applies. */
    private void rememberFace(ItEntity entity, float partialTick) {
        this.model.faceCenter(face);
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float theta = (180.0F - bodyYaw) * Mth.DEG_TO_RAD;
        float cos = Mth.cos(theta);
        float sin = Mth.sin(theta);
        float x = -face[0] / 16.0F;
        float z = face[2] / 16.0F;
        ItAnimation anim = entity.animation();
        anim.faceX = Mth.lerp(partialTick, entity.xo, entity.getX()) + x * cos + z * sin;
        anim.faceY = Mth.lerp(partialTick, entity.yo, entity.getY()) + 1.501 - face[1] / 16.0F;
        anim.faceZ = Mth.lerp(partialTick, entity.zo, entity.getZ()) - x * sin + z * cos;
        anim.faceKnown = true;
    }

    /**
     * The eyes and the inside of the chest, full bright and additive (black adds nothing). The
     * glow breathes slowly and flares when it screams or feeds.
     */
    private static final class GlowLayer extends RenderLayer<ItEntity, ItModel> {
        GlowLayer(ItRenderer parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, ItEntity entity, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            ItAnimation anim = entity.animation();
            float scream = Mth.lerp(partialTick, anim.screamO, anim.scream);
            float breathe = 0.5F + 0.5F * Mth.sin(ageInTicks * 0.09F);
            float feeding = entity.killStage() != ItEntity.KILL_NONE ? 0.15F : 0.0F;
            float glow = Mth.clamp(0.72F + 0.12F * breathe + 0.2F * scream + feeding, 0.0F, 1.0F);
            int v = Mth.clamp((int) (glow * 255.0F), 0, 255);
            VertexConsumer consumer = buffers.getBuffer(RenderType.eyes(GLOW));
            this.getParentModel().renderToBuffer(poseStack, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    FastColor.ARGB32.color(255, v, v, v));
        }
    }
}
