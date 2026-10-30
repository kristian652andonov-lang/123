package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * The visual freeze: the first frame of a freeze is copied out of the main render target into a
 * texture of our own, and that picture is drawn over the screen until the freeze ends, so the
 * game looks hung while it keeps running underneath.
 */
final class FreezeFrame {
    private static TextureTarget target;
    private static boolean captured;

    private FreezeFrame() {
    }

    static boolean captured() {
        return captured;
    }

    /** Copies the current contents of the main render target (call while it is bound). */
    static void capture() {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (target == null) {
            target = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, target.width, target.height,
                GlConst.GL_COLOR_BUFFER_BIT, GlConst.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, 0);
        main.bindWrite(true);
        captured = true;
    }

    /** Draws the frozen picture over the whole GUI area. */
    static void draw(Matrix4f pose, float width, float height) {
        if (!captured || target == null) {
            return;
        }
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Render targets are stored bottom-up, so the top of the screen is v = 1.
        builder.addVertex(pose, 0.0F, 0.0F, 0.0F).setUv(0.0F, 1.0F);
        builder.addVertex(pose, 0.0F, height, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(pose, width, height, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(pose, width, 0.0F, 0.0F).setUv(1.0F, 1.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    /** Stops showing the frozen picture; the next freeze captures a new one. */
    static void release() {
        captured = false;
    }

    /** Frees the texture (logout). */
    static void destroy() {
        captured = false;
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
