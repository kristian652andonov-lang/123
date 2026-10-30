package dev.kristian.nullmod.client.menu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Collects textured, individually tinted quads for one texture and draws them in a single call.
 * Between {@link #begin} and {@link #end} nothing else may be drawn through {@link GuiGraphics}
 * (it shares the {@link Tesselator}). Quads are always emitted with the same winding as vanilla
 * GUI quads; mirroring is done by swapping UVs.
 */
final class SpriteBatch {
    private BufferBuilder builder;
    private Matrix4f matrix;

    /**
     * Starts a batch for a menu texture. Returns false (and starts nothing) when the texture is
     * unavailable; {@link #quad} and {@link #end} are then harmless no-ops.
     */
    boolean begin(GuiGraphics graphics, MenuTexture texture, boolean additive) {
        if (builder != null) {
            abort();
        }
        int id = texture == null ? -1 : texture.id();
        if (id < 0) {
            return false;
        }
        graphics.flush();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, id);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        if (additive) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        matrix = graphics.pose().last().pose();
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        return true;
    }

    boolean active() {
        return builder != null;
    }

    /** Axis-aligned quad from (x0, y0) to (x1, y1); x1 > x0 and y1 > y0. */
    void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
        if (builder == null || (argb >>> 24) == 0 || x1 <= x0 || y1 <= y0) {
            return;
        }
        int a = argb >>> 24;
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        builder.addVertex(matrix, x0, y0, 0.0F).setUv(u0, v0).setColor(r, g, b, a);
        builder.addVertex(matrix, x0, y1, 0.0F).setUv(u0, v1).setColor(r, g, b, a);
        builder.addVertex(matrix, x1, y1, 0.0F).setUv(u1, v1).setColor(r, g, b, a);
        builder.addVertex(matrix, x1, y0, 0.0F).setUv(u1, v0).setColor(r, g, b, a);
    }

    void end() {
        if (builder == null) {
            return;
        }
        MeshData mesh = builder.build();
        builder = null;
        matrix = null;
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        restoreState();
    }

    /** Discards a half-built batch (used when drawing failed part way). */
    void abort() {
        BufferBuilder pending = builder;
        builder = null;
        matrix = null;
        if (pending != null) {
            try {
                MeshData mesh = pending.build();
                if (mesh != null) {
                    mesh.close();
                }
            } catch (RuntimeException ignored) {
                // nothing left to clean up
            }
        }
        restoreState();
    }

    private static void restoreState() {
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
