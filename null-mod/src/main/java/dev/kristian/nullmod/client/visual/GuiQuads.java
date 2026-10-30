package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Batched screen-space quads for the overlay: one buffer per primitive type per pass, so a
 * frame full of glitch bars is still a handful of draw calls and no garbage.
 */
final class GuiQuads {
    private GuiQuads() {
    }

    static BufferBuilder beginColor() {
        return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    }

    static BufferBuilder beginTextured() {
        return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    /** Axis-aligned rectangle; vertex order matches vanilla GUI quads (front facing). */
    static void rect(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1, int r, int g, int bl, int a) {
        b.addVertex(m, x0, y0, 0.0F).setColor(r, g, bl, a);
        b.addVertex(m, x0, y1, 0.0F).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, 0.0F).setColor(r, g, bl, a);
        b.addVertex(m, x1, y0, 0.0F).setColor(r, g, bl, a);
    }

    static void texRect(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1,
                        float u0, float v0, float u1, float v1, int r, int g, int bl, int a) {
        b.addVertex(m, x0, y0, 0.0F).setUv(u0, v0).setColor(r, g, bl, a);
        b.addVertex(m, x0, y1, 0.0F).setUv(u0, v1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, 0.0F).setUv(u1, v1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y0, 0.0F).setUv(u1, v0).setColor(r, g, bl, a);
    }

    static void drawColor(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(mesh);
        }
    }

    static void drawTextured(BufferBuilder b, ResourceLocation texture) {
        MeshData mesh = b.build();
        if (mesh != null) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, texture);
            BufferUploader.drawWithShader(mesh);
        }
    }

    static int alpha(float a) {
        return Math.max(0, Math.min(255, Math.round(a * 255.0F)));
    }

    /** One full-screen rectangle in a single call. */
    static void fill(Matrix4f m, float width, float height, int r, int g, int bl, float a) {
        int alpha = alpha(a);
        if (alpha <= 0) {
            return;
        }
        BufferBuilder b = beginColor();
        rect(b, m, 0.0F, 0.0F, width, height, r, g, bl, alpha);
        drawColor(b);
    }
}
