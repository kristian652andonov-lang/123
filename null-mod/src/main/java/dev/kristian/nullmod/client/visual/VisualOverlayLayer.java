package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.joml.Matrix4f;

/**
 * NULL's screen overlay, registered above every other HUD layer: the frozen frame, tearing bars
 * and glitch rectangles, blackouts and flashes, the NULLROOT capture tendrils, glitch text and
 * the shader-less fallbacks. The transition fade is drawn after the whole HUD (and again over
 * any open screen) so nothing can ever show through it.
 */
final class VisualOverlayLayer {
    static final ResourceLocation ID = NullMod.id("visual_overlay");

    private static final ResourceLocation TENDRILS = NullMod.id("textures/gui/visual/capture_tendrils.png");
    private static final ResourceLocation NOISE_TEXTURE = NullMod.id("textures/gui/visual/noise.png");
    private static final ResourceLocation VIGNETTE = NullMod.id("textures/gui/visual/vignette.png");
    private static final int TENDRIL_FRAMES = 6;
    private static final float TENDRIL_HALF_TEXEL = 0.5F / (288.0F * TENDRIL_FRAMES);

    private VisualOverlayLayer() {
    }

    // ------------------------------------------------------------------ HUD layer

    static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        boolean frozen = VisualDistortionManager.frozen();
        float blackout = VisualDistortionManager.level(VisualDistortionManager.BLACKOUT);
        float flash = VisualDistortionManager.level(VisualDistortionManager.FLASH);
        float capture = VisualDistortionManager.captureOverlay();
        float glitch = glitchAmount();
        boolean text = VisualDistortionManager.glitchText() != null;
        boolean fallback = !CorruptionPostEffect.available() && VisualDistortionManager.anyShaderLevel();
        if (!frozen && blackout <= 0.0F && flash <= 0.0F && capture <= 0.0F && glitch <= 0.0F && !text && !fallback) {
            return;
        }
        graphics.flush();
        Matrix4f pose = graphics.pose().last().pose();
        float width = graphics.guiWidth();
        float height = graphics.guiHeight();
        double time = VisualDistortionManager.time();

        if (frozen) {
            if (!FreezeFrame.captured()) {
                // Everything below this layer (world and HUD) is in the main target right now.
                FreezeFrame.capture();
            }
            FreezeFrame.draw(pose, width, height);
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            if (fallback && !frozen) {
                drawFallback(pose, width, height, time);
            }
            if (glitch > 0.0F) {
                drawGlitches(pose, width, height, time, glitch);
            }
            if (capture > 0.0F) {
                drawCapture(pose, width, height, time, capture);
            }
            if (blackout > 0.0F) {
                GuiQuads.fill(pose, width, height, 0, 0, 0, blackout);
            }
            if (flash > 0.0F) {
                GuiQuads.fill(pose, width, height, 232, 236, 242, flash * 0.9F);
            }
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
        }
        if (text) {
            GlitchTextRenderer.render(graphics, width, height);
        }
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ transition fade

    /** After every HUD layer: the transition fade covers everything when no screen is open. */
    static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            drawTransitionFade(event.getGuiGraphics());
        }
    }

    /** Over any open screen, so menus and loading screens are covered by the fade too. */
    static void onRenderScreenPost(ScreenEvent.Render.Post event) {
        drawTransitionFade(event.getGuiGraphics());
    }

    private static void drawTransitionFade(GuiGraphics graphics) {
        float fade = VisualDistortionManager.transitionFade();
        if (fade <= 0.001F) {
            return;
        }
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        GuiQuads.fill(graphics.pose().last().pose(), graphics.guiWidth(), graphics.guiHeight(), 0, 0, 0, fade);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ pieces

    /** How much tearing and rectangle glitching to draw this frame. */
    private static float glitchAmount() {
        float tear = VisualDistortionManager.level(VisualDistortionManager.CH_TEAR);
        float noise = Math.max(0.0F, VisualDistortionManager.level(VisualDistortionManager.NOISE) - 0.45F) * 0.8F;
        float rgb = Math.max(0.0F, VisualDistortionManager.level(VisualDistortionManager.RGB_SPLIT) - 0.5F) * 0.7F;
        float bands = VisualDistortionManager.level(VisualDistortionManager.CH_BANDS) * 0.35F;
        return Math.min(1.0F, Math.max(Math.max(tear, noise), Math.max(rgb, bands)));
    }

    private static void drawGlitches(Matrix4f pose, float width, float height, double time, float amount) {
        float flashing = SmoothNoise.clamp01(ClientLimits.flashing());
        boolean strobe = ClientLimits.intenseAllowed() && flashing >= 0.6F;
        // The layout only changes every few frames; without strobing it changes slowly.
        double bucketLength = strobe ? 1.5 : (flashing >= 0.25F ? 4.0 : 9.0);
        long bucket = (long) Math.floor(time / bucketLength);
        long seed = SmoothNoise.mix(bucket * 0x2545F4914F6CDD1DL + 0x4E554C4CL);
        float alphaScale = 0.3F + 0.7F * flashing;
        int count = 2 + (int) (amount * 14.0F);
        BufferBuilder b = GuiQuads.beginColor();
        for (int i = 0; i < count; i++) {
            long k = i * 8L;
            if (SmoothNoise.hash01(seed, k) > 0.35F + amount * 0.65F) {
                continue;
            }
            float type = SmoothNoise.hash01(seed, k + 1L);
            float y = SmoothNoise.hash01(seed, k + 2L) * height;
            if (type < 0.55F) {
                // A tearing bar across the whole screen.
                float h = 1.0F + SmoothNoise.hash01(seed, k + 3L) * (2.0F + 8.0F * amount);
                float tone = SmoothNoise.hash01(seed, k + 4L);
                int a = GuiQuads.alpha((0.25F + 0.5F * amount) * alphaScale);
                if (tone < 0.55F) {
                    GuiQuads.rect(b, pose, 0.0F, y, width, y + h, 0, 0, 0, a);
                } else if (tone < 0.8F) {
                    GuiQuads.rect(b, pose, 0.0F, y, width, y + h, 120, 0, 140, a / 2);
                } else {
                    GuiQuads.rect(b, pose, 0.0F, y, width, y + h, 20, 160, 170, a / 2);
                }
            } else {
                // A displaced rectangle: a block of the picture that failed to draw.
                float w = 6.0F + SmoothNoise.hash01(seed, k + 3L) * width * (0.06F + 0.18F * amount);
                float h = 1.0F + SmoothNoise.hash01(seed, k + 4L) * (3.0F + 12.0F * amount);
                float x = SmoothNoise.hash01(seed, k + 5L) * (width - w);
                float tone = SmoothNoise.hash01(seed, k + 6L);
                int a = GuiQuads.alpha((0.35F + 0.55F * amount) * alphaScale);
                if (tone < 0.5F) {
                    GuiQuads.rect(b, pose, x, y, x + w, y + h, 0, 0, 0, a);
                } else if (tone < 0.7F) {
                    GuiQuads.rect(b, pose, x, y, x + w, y + h, 248, 0, 248, a / 2);
                } else if (tone < 0.88F) {
                    GuiQuads.rect(b, pose, x, y, x + w, y + h, 0, 230, 230, a / 2);
                } else if (strobe) {
                    GuiQuads.rect(b, pose, x, y, x + w, y + h, 235, 235, 235, a / 3);
                } else {
                    GuiQuads.rect(b, pose, x, y, x + w, y + h, 40, 40, 48, a / 2);
                }
            }
        }
        GuiQuads.drawColor(b);
    }

    private static void drawCapture(Matrix4f pose, float width, float height, double time, float amount) {
        float frames = amount * TENDRIL_FRAMES;
        int first = Mth.clamp((int) Math.floor(frames), 0, TENDRIL_FRAMES - 1);
        float blend = SmoothNoise.clamp01(frames - first);
        // The mass breathes and sways a little: it is alive.
        float breathe = 1.0F + 0.012F * Mth.sin((float) (time * 0.12)) + 0.008F * Mth.sin((float) (time * 0.31));
        float swayX = Mth.sin((float) (time * 0.07)) * width * 0.004F;
        float swayY = Mth.sin((float) (time * 0.053 + 1.7)) * height * 0.004F;
        float cx = width * 0.5F + swayX;
        float cy = height * 0.5F + swayY;
        float hw = width * 0.5F * breathe;
        float hh = height * 0.5F * breathe;

        // Darkness gathers behind the tendrils first.
        BufferBuilder vignette = GuiQuads.beginTextured();
        GuiQuads.texRect(vignette, pose, 0.0F, 0.0F, width, height, 0.0F, 0.0F, 1.0F, 1.0F, 255, 255, 255,
                GuiQuads.alpha(Math.min(1.0F, amount * 0.75F)));
        GuiQuads.drawTextured(vignette, VIGNETTE);

        BufferBuilder b = GuiQuads.beginTextured();
        if (first == 0) {
            tendrilFrame(b, pose, 0, cx, cy, hw, hh, blend);
        } else {
            tendrilFrame(b, pose, first - 1, cx, cy, hw, hh, 1.0F);
            tendrilFrame(b, pose, first, cx, cy, hw, hh, blend);
        }
        GuiQuads.drawTextured(b, TENDRILS);

        float closing = SmoothNoise.smoothstep(0.8F, 1.0F, amount);
        if (closing > 0.0F) {
            GuiQuads.fill(pose, width, height, 2, 0, 4, closing * 0.75F);
        }
    }

    private static void tendrilFrame(BufferBuilder b, Matrix4f pose, int frame, float cx, float cy, float hw, float hh, float alpha) {
        if (alpha <= 0.002F) {
            return;
        }
        float v0 = frame / (float) TENDRIL_FRAMES + TENDRIL_HALF_TEXEL;
        float v1 = (frame + 1) / (float) TENDRIL_FRAMES - TENDRIL_HALF_TEXEL;
        GuiQuads.texRect(b, pose, cx - hw, cy - hh, cx + hw, cy + hh, 0.0F, v0, 1.0F, v1, 255, 255, 255, GuiQuads.alpha(alpha));
    }

    /** Rough stand-ins for the shader effects when shaders are off or failed to load. */
    private static void drawFallback(Matrix4f pose, float width, float height, double time) {
        float darken = VisualDistortionManager.level(VisualDistortionManager.DARKEN);
        float desaturate = VisualDistortionManager.level(VisualDistortionManager.DESATURATE);
        float shift = Math.abs(VisualDistortionManager.hueShift());
        float noise = VisualDistortionManager.level(VisualDistortionManager.NOISE);
        float scan = VisualDistortionManager.level(VisualDistortionManager.SCANLINES);

        BufferBuilder fills = GuiQuads.beginColor();
        if (desaturate > 0.0F) {
            GuiQuads.rect(fills, pose, 0.0F, 0.0F, width, height, 128, 128, 132, GuiQuads.alpha(desaturate * 0.22F));
        }
        if (shift > 0.0F) {
            GuiQuads.rect(fills, pose, 0.0F, 0.0F, width, height, Math.round(VisualDistortionManager.skyTintR() * 255.0F),
                    Math.round(VisualDistortionManager.skyTintG() * 120.0F), 160, GuiQuads.alpha(Math.min(1.0F, shift) * 0.14F));
        }
        if (darken > 0.0F) {
            GuiQuads.rect(fills, pose, 0.0F, 0.0F, width, height, 0, 0, 0, GuiQuads.alpha(darken * 0.35F));
        }
        if (scan > 0.0F) {
            int a = GuiQuads.alpha(scan * 0.3F);
            for (float y = 0.0F; y < height; y += 3.0F) {
                GuiQuads.rect(fills, pose, 0.0F, y, width, y + 1.0F, 0, 0, 0, a);
            }
        }
        GuiQuads.drawColor(fills);

        if (darken > 0.0F) {
            BufferBuilder v = GuiQuads.beginTextured();
            GuiQuads.texRect(v, pose, 0.0F, 0.0F, width, height, 0.0F, 0.0F, 1.0F, 1.0F, 255, 255, 255, GuiQuads.alpha(darken * 0.8F));
            GuiQuads.drawTextured(v, VIGNETTE);
        }
        if (noise > 0.0F) {
            long frame = (long) Math.floor(time * 0.75);
            float u = SmoothNoise.hash01(frame, 1L);
            float vv = SmoothNoise.hash01(frame, 2L);
            float repeatX = width / 128.0F;
            float repeatY = height / 128.0F;
            BufferBuilder n = GuiQuads.beginTextured();
            GuiQuads.texRect(n, pose, 0.0F, 0.0F, width, height, u, vv, u + repeatX, vv + repeatY, 255, 255, 255,
                    GuiQuads.alpha(noise * 0.3F));
            GuiQuads.drawTextured(n, NOISE_TEXTURE);
        }
    }
}
