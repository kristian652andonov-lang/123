package dev.kristian.nullmod.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Thin lines drawn in real screen pixels instead of GUI units, so outlines stay hair-thin at
 * every GUI scale (1 pixel up to GUI scale 2, 2 pixels at 3-4, ...), and horizontal gradients.
 */
final class MenuDraw {
    private MenuDraw() {
    }

    /** Rectangle whose colour runs from {@code argbLeft} at x0 to {@code argbRight} at x1 (GUI units). */
    static void hgradient(GuiGraphics graphics, float x0, float y0, float x1, float y1, int argbLeft, int argbRight) {
        if (x1 <= x0 || y1 <= y0 || ((argbLeft >>> 24) == 0 && (argbRight >>> 24) == 0)) {
            return;
        }
        graphics.flush();
        Matrix4f matrix = graphics.pose().last().pose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        builder.addVertex(matrix, x0, y0, 0.0F).setColor(argbLeft);
        builder.addVertex(matrix, x0, y1, 0.0F).setColor(argbLeft);
        builder.addVertex(matrix, x1, y1, 0.0F).setColor(argbRight);
        builder.addVertex(matrix, x1, y0, 0.0F).setColor(argbRight);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.disableBlend();
    }

    static double guiScale() {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return scale > 0.0 ? scale : 1.0;
    }

    /** Line thickness in real pixels for the current GUI scale. */
    static int thickness(double scale) {
        return Math.max(1, (int) Math.round(scale / 2.0));
    }

    /**
     * Rectangle outline in GUI units. When {@code broken} is above zero the edges lose random
     * segments and shift slightly (click glitch); {@code seed} picks the pattern.
     */
    static void outline(GuiGraphics graphics, float x, float y, float w, float h, int argb, float broken, long seed) {
        if ((argb >>> 24) == 0) {
            return;
        }
        double scale = guiScale();
        int t = thickness(scale);
        int x0 = (int) Math.round(x * scale);
        int y0 = (int) Math.round(y * scale);
        int x1 = (int) Math.round((x + w) * scale);
        int y1 = (int) Math.round((y + h) * scale);
        graphics.pose().pushPose();
        try {
            float inverse = (float) (1.0 / scale);
            graphics.pose().scale(inverse, inverse, 1.0F);
            if (broken <= 0.0F) {
                graphics.fill(x0, y0, x1, y0 + t, argb);
                graphics.fill(x0, y1 - t, x1, y1, argb);
                graphics.fill(x0, y0 + t, x0 + t, y1 - t, argb);
                graphics.fill(x1 - t, y0 + t, x1, y1 - t, argb);
            } else {
                brokenEdge(graphics, x0, x1, y0, t, argb, broken, seed, true);
                brokenEdge(graphics, x0, x1, y1 - t, t, argb, broken, seed + 17L, true);
                brokenEdge(graphics, y0 + t, y1 - t, x0, t, argb, broken, seed + 31L, false);
                brokenEdge(graphics, y0 + t, y1 - t, x1 - t, t, argb, broken, seed + 47L, false);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void brokenEdge(GuiGraphics graphics, int from, int to, int at, int t, int argb, float broken, long seed, boolean horizontal) {
        int length = to - from;
        if (length <= 0) {
            return;
        }
        int segments = 4;
        int start = from;
        for (int i = 0; i < segments; i++) {
            int end = i == segments - 1 ? to : from + (int) (length * (i + 0.5F + MenuMath.hash01(seed + i) * 0.5F) / segments);
            end = Math.max(start, Math.min(to, end));
            boolean gap = MenuMath.hash01(seed * 3L + i) < 0.35F * broken;
            int shift = (int) (MenuMath.hashSigned(seed * 5L + i) * 2.0F * broken * t);
            if (!gap && end > start) {
                if (horizontal) {
                    graphics.fill(start + shift, at, end + shift, at + t, argb);
                } else {
                    graphics.fill(at + shift, start, at + t + shift, end, argb);
                }
            }
            start = end;
        }
    }

    /** Horizontal line of the current thickness, given in GUI units. */
    static void hline(GuiGraphics graphics, float x0, float x1, float y, int argb) {
        if ((argb >>> 24) == 0 || x1 <= x0) {
            return;
        }
        double scale = guiScale();
        int t = thickness(scale);
        graphics.pose().pushPose();
        try {
            float inverse = (float) (1.0 / scale);
            graphics.pose().scale(inverse, inverse, 1.0F);
            int py = (int) Math.round(y * scale);
            graphics.fill((int) Math.round(x0 * scale), py, (int) Math.round(x1 * scale), py + t, argb);
        } finally {
            graphics.pose().popPose();
        }
    }

    /** Vertical bar given in GUI units, pixel exact. */
    static void vbar(GuiGraphics graphics, float x, float y0, float y1, int widthPixels, int argb) {
        if ((argb >>> 24) == 0 || y1 <= y0) {
            return;
        }
        double scale = guiScale();
        graphics.pose().pushPose();
        try {
            float inverse = (float) (1.0 / scale);
            graphics.pose().scale(inverse, inverse, 1.0F);
            int px = (int) Math.round(x * scale);
            graphics.fill(px, (int) Math.round(y0 * scale), px + Math.max(1, widthPixels), (int) Math.round(y1 * scale), argb);
        } finally {
            graphics.pose().popPose();
        }
    }
}
