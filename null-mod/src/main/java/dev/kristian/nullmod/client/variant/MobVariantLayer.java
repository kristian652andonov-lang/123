package dev.kristian.nullmod.client.variant;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kristian.nullmod.NullMod;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * Added to every living entity renderer (vanilla and modded, see {@link MobVariantClientModule}).
 * Does nothing for ordinary mobs; for a variant mob it draws the variant's look on the mob's own
 * model, over its normal render (same geometry, so the depth test lets it through, like
 * vanilla's eye layers). If anything about an unusual renderer makes it fail, it switches itself
 * off for that renderer instead of breaking the game's rendering.
 */
final class MobVariantLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private boolean broken;

    MobVariantLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, T entity, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (broken || ClientMobVariants.size() == 0 || entity.isInvisible()) {
            return;
        }
        ClientMobVariants.State state = ClientMobVariants.get(entity);
        if (state == null) {
            return;
        }
        try {
            ResourceLocation texture = getTextureLocation(entity);
            if (texture != null) {
                VariantRendering.render(getParentModel(), texture, poseStack, buffers, packedLight, entity, state, partialTick);
            }
        } catch (RuntimeException e) {
            broken = true;
            NullMod.LOGGER.error("NULL cannot draw variant mobs of {}; their variant look is off for this session",
                    entity.getType(), e);
        }
    }
}
