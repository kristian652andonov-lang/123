package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * NULL's own post-processing chain ({@code null:shaders/post/corruption.json}): RGB separation,
 * hue rotation, desaturation, darkening with vignette, grain, scanlines, displacement bands and
 * warp, all in one pass plus a copy back. It runs at the start of the HUD pass, so the world and
 * the held item are distorted while the HUD stays readable, exactly where vanilla runs its own
 * post effects.
 * <p>
 * The chain is created lazily, resized with the window, recreated after resource reloads and
 * skipped entirely while every level is zero or shader effects are switched off. If it cannot
 * be loaded, that is logged once and the overlay falls back to its shader-less effects.
 */
final class CorruptionPostEffect {
    private static final ResourceLocation CHAIN = NullMod.id("shaders/post/corruption.json");

    private static PostChain chain;
    private static boolean failed;
    private static boolean warned;
    private static int width = -1;
    private static int height = -1;
    private static float seed;
    private static boolean ranThisFrame;

    private CorruptionPostEffect() {
    }

    /** True if the shader exists and is allowed, i.e. the overlay need not imitate it. */
    static boolean available() {
        return !failed && ClientLimits.shaderEffects();
    }

    /** True if the corruption pass ran for the frame being drawn. */
    static boolean ranThisFrame() {
        return ranThisFrame;
    }

    static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        ranThisFrame = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || failed || !ClientLimits.shaderEffects() || !VisualDistortionManager.anyShaderLevel()) {
            return;
        }
        if (chain == null && !create(mc)) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        if (main.width != width || main.height != height) {
            chain.resize(main.width, main.height);
            width = main.width;
            height = main.height;
        }
        double time = VisualDistortionManager.time();
        chain.setUniform("NullTime", (float) ((time % 72000.0) / 20.0));
        chain.setUniform("NullSeed", seed);
        chain.setUniform("RgbSplit", VisualDistortionManager.level(VisualDistortionManager.RGB_SPLIT));
        chain.setUniform("HueShift", VisualDistortionManager.hueShift());
        chain.setUniform("Desaturate", VisualDistortionManager.level(VisualDistortionManager.DESATURATE));
        chain.setUniform("Darken", VisualDistortionManager.level(VisualDistortionManager.DARKEN));
        chain.setUniform("Grain", VisualDistortionManager.level(VisualDistortionManager.NOISE));
        chain.setUniform("Scanlines", VisualDistortionManager.level(VisualDistortionManager.SCANLINES));
        chain.setUniform("BlockShift", VisualDistortionManager.level(VisualDistortionManager.CH_BANDS));
        chain.setUniform("Warp", VisualDistortionManager.level(VisualDistortionManager.WARP));

        // Whatever the GUI batched so far belongs under the distortion.
        event.getGuiGraphics().flush();
        try {
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(event.getPartialTick().getGameTimeDeltaTicks());
            ranThisFrame = true;
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            // Back to the state the HUD expects: main target bound with its viewport, depth
            // test on (Gui#render enabled it before the layers), default blending.
            main.bindWrite(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
        }
    }

    /** Resource reload: the next frame builds the chain again from the new resources. */
    static void onResourcesReloaded() {
        close();
        failed = false;
        warned = false;
    }

    static void close() {
        if (chain != null) {
            try {
                chain.close();
            } catch (RuntimeException e) {
                NullMod.LOGGER.debug("Closing NULL's post chain failed", e);
            }
            chain = null;
        }
        width = -1;
        height = -1;
    }

    private static boolean create(Minecraft mc) {
        try {
            chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(), CHAIN);
            RenderTarget main = mc.getMainRenderTarget();
            chain.resize(main.width, main.height);
            width = main.width;
            height = main.height;
            seed = VisualDistortionManager.random().nextFloat();
            return true;
        } catch (IOException | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    private static void fail(Exception e) {
        close();
        failed = true;
        if (!warned) {
            warned = true;
            NullMod.LOGGER.warn("NULL's corruption shader could not be used; falling back to overlay-only effects", e);
        }
    }
}
