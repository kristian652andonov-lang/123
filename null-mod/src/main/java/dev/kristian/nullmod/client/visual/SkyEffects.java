package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.core.CorruptionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Sky tint and sky flicker. Right after the sky is drawn (and before any terrain) a full-screen
 * quad is blended over it, so only the sky changes colour: a multiplied tint for the slightly
 * dimmer, wrong-coloured sky of Stages 4-5, a coloured wash for {@code visual.sky_color} and
 * dark or inverted blinks for {@code visual.sky_flicker}.
 */
final class SkyEffects {
    private static final Matrix4f IDENTITY = new Matrix4f();

    private SkyEffects() {
    }

    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        float tint = VisualDistortionManager.level(VisualDistortionManager.SKY_TINT);
        float flicker = VisualDistortionManager.level(VisualDistortionManager.CH_SKY_FLICKER);
        float wrong = stageSkyWrongness(level);
        if (tint <= 0.0F && flicker <= 0.0F && wrong <= 0.0F) {
            return;
        }

        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(IDENTITY, VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            if (wrong > 0.0F) {
                // Multiply: the sky keeps its gradient, sun and stars, but dimmer and slightly off.
                RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
                float r = 1.0F - 0.30F * wrong;
                float g = 1.0F - 0.16F * wrong;
                float b = 1.0F - 0.38F * wrong;
                quad(r, g, b, 1.0F);
            }
            if (tint > 0.0F) {
                RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
                float strength = tint * 0.75F;
                quad(1.0F - strength * (1.0F - VisualDistortionManager.skyTintR()),
                        1.0F - strength * (1.0F - VisualDistortionManager.skyTintG()),
                        1.0F - strength * (1.0F - VisualDistortionManager.skyTintB()), 1.0F);
                RenderSystem.defaultBlendFunc();
                quad(VisualDistortionManager.skyTintR(), VisualDistortionManager.skyTintG(), VisualDistortionManager.skyTintB(),
                        tint * 0.28F);
            }
            if (flicker > 0.0F) {
                boolean invert = ClientLimits.intenseAllowed() && ClientLimits.flashing() >= 0.6F
                        && SmoothNoise.hash01((long) Math.floor(VisualDistortionManager.time() / 2.0), 99L) < 0.4F;
                if (invert) {
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                    quad(flicker, flicker, flicker, flicker);
                } else {
                    RenderSystem.defaultBlendFunc();
                    quad(0.0F, 0.0F, 0.0F, flicker * 0.95F);
                }
            }
        } finally {
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static void quad(float r, float g, float b, float a) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        builder.addVertex(-1.0F, -1.0F, 0.0F).setColor(r, g, b, a);
        builder.addVertex(1.0F, -1.0F, 0.0F).setColor(r, g, b, a);
        builder.addVertex(1.0F, 1.0F, 0.0F).setColor(r, g, b, a);
        builder.addVertex(-1.0F, 1.0F, 0.0F).setColor(r, g, b, a);
        MeshData mesh = builder.build();
        if (mesh != null) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(mesh);
        }
    }

    /** The overworld sky in Stages 4-5: slightly dimmer and the wrong colour. */
    private static float stageSkyWrongness(ClientLevel level) {
        if (level.dimension() != Level.OVERWORLD || !ClientCorruptionState.synced()) {
            return 0.0F;
        }
        CorruptionStage stage = ClientCorruptionState.stage();
        if (stage == CorruptionStage.ABOVE_MIDDLE) {
            return 0.25F + 0.2F * SmoothNoise.clamp01(ClientCorruptionState.stageProgress());
        }
        return stage == CorruptionStage.NULL ? 0.55F : 0.0F;
    }
}
