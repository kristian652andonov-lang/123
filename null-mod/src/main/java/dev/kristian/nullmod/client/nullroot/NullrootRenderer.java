package dev.kristian.nullmod.client.nullroot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.entity.nullroot.NullrootAnimation;
import dev.kristian.nullmod.entity.nullroot.NullrootEntity;
import dev.kristian.nullmod.entity.nullroot.NullrootState;
import dev.kristian.nullmod.entity.nullroot.NullrootVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Draws NULLROOT organisms: the variant's model grown out of its face (scaled up from nothing as
 * it grows, sinking back when it withers or dies), turned toward whoever it watches, swaying and
 * pulsing; the hunting tendril reaching for its target; and, while it holds somebody, the
 * tendril fastened to them and coils winding up their body, so every player nearby sees the
 * victim being wrapped. A colony's centre sends tendrils of its own to the player it pulls in.
 */
public final class NullrootRenderer extends EntityRenderer<NullrootEntity> {
    private final NullrootModel[] models;
    private final ModelPart segment;

    public NullrootRenderer(EntityRendererProvider.Context context) {
        super(context);
        NullrootVariant[] variants = NullrootVariant.values();
        this.models = new NullrootModel[variants.length];
        for (NullrootVariant variant : variants) {
            models[variant.ordinal()] = new NullrootModel(variant, context.bakeLayer(NullrootModels.layer(variant)), NullrootModels.built(variant));
        }
        this.segment = context.bakeLayer(NullrootModels.SEGMENT).getChild("segment");
        this.shadowRadius = 0.4F;
        this.shadowStrength = 0.7F;
    }

    @Override
    public ResourceLocation getTextureLocation(NullrootEntity entity) {
        return NullrootTextures.BLACK;
    }

    @Override
    protected float getShadowRadius(NullrootEntity entity) {
        if (entity.face() != Direction.UP) {
            return 0.0F;
        }
        return entity.variant().width() * 0.55F * entity.animation().growth;
    }

    @Override
    public void render(NullrootEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        NullrootAnimation anim = entity.animation();
        if (!anim.started) {
            return;
        }
        NullrootVariant variant = entity.variant();
        Direction face = entity.face();
        float growth = anim.growth(partialTick);
        if (entity.deathTime > 0) {
            growth *= Math.max(0.0F, 1.0F - (entity.deathTime + partialTick) / NullrootEntity.DEATH_TICKS);
        }
        float scale = NullrootGeometry.smooth(growth);
        ResourceLocation texture = NullrootTextures.pick(entity, anim);
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        int overlay = OverlayTexture.pack(0.0F, entity.hurtTime > 0 || entity.deathTime > 0);
        float time = anim.age + partialTick;
        float pulse = anim.pulse(partialTick);
        float jolt = anim.jolt(partialTick);
        NullrootState state = entity.state();
        Vec3 offset = NullrootEntity.attachOffset(face, variant);

        if (scale > 0.01F) {
            float tension = state == NullrootState.REACHING || state.holding() ? 1.0F
                    : state == NullrootState.RETRACTING || state == NullrootState.RECOIL ? 0.5F : 0.0F;
            float headTurn = variant == NullrootVariant.SMALL
                    ? Mth.clamp(NullrootGeometry.wrap(anim.targetYaw - anim.yaw(partialTick)), -0.9F, 0.9F) : 0.0F;
            NullrootModel model = models[variant.ordinal()];
            poseStack.pushPose();
            poseStack.translate(offset.x, offset.y, offset.z);
            NullrootGeometry.rotateToFace(poseStack, face);
            NullrootGeometry.rotateY(poseStack, anim.yaw(partialTick));
            NullrootGeometry.rotateX(poseStack, anim.leanX(partialTick));
            NullrootGeometry.rotateZ(poseStack, anim.leanZ(partialTick));
            poseStack.scale(scale, scale, scale);
            model.pose(time, anim.swayShift, pulse, jolt, headTurn, tension);
            model.render(poseStack, consumer, packedLight, overlay, -1);
            float glow = veinGlow(variant, state, pulse) * scale;
            if (glow > 0.02F) {
                int v = Mth.clamp((int) (glow * 255.0F), 0, 255);
                VertexConsumer veins = buffers.getBuffer(RenderType.eyes(NullrootTextures.VEINS));
                model.render(poseStack, veins, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(255, v, v, v));
            }
            poseStack.popPose();
        }

        renderTendrils(entity, anim, variant, face, offset, scale, state, partialTick, time, poseStack, consumer, packedLight, overlay);
        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    /** Faint purple veins: the centre of a colony glows with every pulse, the others only while they hunt. */
    private static float veinGlow(NullrootVariant variant, NullrootState state, float pulse) {
        if (variant == NullrootVariant.CENTRAL) {
            return 0.1F + 0.55F * pulse;
        }
        boolean active = state == NullrootState.REACHING || state.holding();
        return pulse * (active ? 0.45F : 0.18F);
    }

    private void renderTendrils(NullrootEntity entity, NullrootAnimation anim, NullrootVariant variant, Direction face, Vec3 offset,
                                float scale, NullrootState state, float partialTick, float time, PoseStack poseStack,
                                VertexConsumer consumer, int packedLight, int overlay) {
        Entity focus = entity.focus();
        Vec3 origin = entity.getPosition(partialTick);
        float thick = variant == NullrootVariant.CENTRAL ? 1.6F : variant == NullrootVariant.LARGE ? 1.25F : 1.0F;
        double nx = face.getStepX();
        double ny = face.getStepY();
        double nz = face.getStepZ();

        if (state == NullrootState.ANCHORING && focus != null && scale > 0.5F) {
            // The centre's own tendrils pull the captive into the mass.
            Vec3 victim = focus.getPosition(partialTick).subtract(origin);
            float width = variant.width();
            float height = variant.height();
            for (int k = 0; k < 3; k++) {
                float angle = k * (float) (Math.PI * 2.0 / 3.0) + time * 0.01F;
                double sx = offset.x + Mth.cos(angle) * width * 0.3;
                double sy = offset.y + height * 0.5;
                double sz = offset.z + Mth.sin(angle) * width * 0.3;
                double ex = victim.x + Mth.cos(angle + 1.0F) * 0.15;
                double ey = victim.y + focus.getBbHeight() * (0.35 + 0.15 * k);
                double ez = victim.z + Mth.sin(angle + 1.0F) * 0.15;
                NullrootTendrils.chain(poseStack, consumer, segment, sx, sy, sz, nx, ny, nz, ex, ey, ez,
                        0.22F * scale, 0.07F, 0.07F, time, anim.seed + k * 977L, packedLight, overlay, -1);
            }
        }

        boolean grabbing = state == NullrootState.GRABBING;
        float reach = anim.reach(partialTick);
        if (!state.tendrilOut() || (!grabbing && reach < 0.03F)) {
            return;
        }
        double off = variant.rootOffset() * scale;
        double sx = offset.x + nx * off;
        double sy = offset.y + ny * off;
        double sz = offset.z + nz * off;
        double ex;
        double ey;
        double ez;
        Vec3 legs = null;
        if (focus != null) {
            Vec3 feet = focus.getPosition(partialTick).subtract(origin);
            legs = feet.add(0.0, Math.min(0.5, focus.getBbHeight() * 0.3), 0.0);
        }
        if (grabbing && legs != null) {
            ex = legs.x;
            ey = legs.y;
            ez = legs.z;
        } else {
            double dx;
            double dy;
            double dz;
            double max;
            if (legs != null) {
                dx = legs.x - sx;
                dy = legs.y - sy;
                dz = legs.z - sz;
                max = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (max < 1.0E-3) {
                    return;
                }
                dx /= max;
                dy /= max;
                dz /= max;
            } else {
                dx = anim.reachDirX;
                dy = anim.reachDirY;
                dz = anim.reachDirZ;
                max = Double.MAX_VALUE;
            }
            double length = Math.min(Math.max(reach, grabbing ? anim.lastReach : reach), max);
            ex = sx + dx * length;
            ey = sy + dy * length;
            ez = sz + dz * length;
        }
        float wobble = grabbing ? 0.05F : 0.1F + 0.04F * reach;
        NullrootTendrils.chain(poseStack, consumer, segment, sx, sy, sz, nx, ny, nz, ex, ey, ez,
                0.15F * thick * Math.max(0.4F, scale), 0.05F * thick, wobble, time, anim.seed, packedLight, overlay, -1);

        if (grabbing && focus != null) {
            Vec3 feet = focus.getPosition(partialTick).subtract(origin);
            float top = focus.getBbHeight() * (0.32F + 0.68F * anim.capture(partialTick));
            Minecraft minecraft = Minecraft.getInstance();
            if (focus == minecraft.player && minecraft.getCameraEntity() == focus && minecraft.options.getCameraType().isFirstPerson()) {
                // Seen from inside: the coils stop below the camera; the screen overlay shows the rest.
                top = Math.min(top, 0.9F);
            }
            int light = this.entityRenderDispatcher.getPackedLightCoords(focus, partialTick);
            NullrootTendrils.coils(poseStack, consumer, segment, feet.x, feet.y, feet.z, focus.getBbWidth() * 0.62F, top, time,
                    anim.seed, light, overlay, -1);
        }
    }
}
