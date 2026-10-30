package dev.kristian.nullmod.client.menu;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the almost black, grayscale background: drifting darkness, the apparitions with their
 * glowing pupils, the vignette, darkening and (over everything, including the buttons) a thin
 * layer of static, rare torn scan lines and dim flashes. A handful of batched quads per frame;
 * nothing is allocated while drawing.
 */
final class MainMenuBackgroundRenderer {
    private static final int GRAIN_SIZE = 256;

    /** Everything below the logo and the buttons. */
    void render(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation, MenuMood mood,
                int width, int height, float time, float darken) {
        graphics.fill(0, 0, width, height, 0xFF000000 | mood.baseColor);
        if (art.fog != null) {
            drawFog(graphics, batch, art.fog, mood, width, height, time);
        }
        if (art.hasApparitions()) {
            drawApparitions(graphics, batch, art, animation, width, height);
            drawGlow(graphics, batch, art, animation, width, height);
        }
        if (art.vignette != null) {
            batch.begin(graphics, art.vignette, false);
            batch.quad(0.0F, 0.0F, width, height, 0.0F, 0.0F, 1.0F, 1.0F, MenuMath.argb(0xFFFFFF, mood.vignette));
            batch.end();
        }
        if (mood.animated) {
            drawRollingBand(graphics, mood, width, height, time);
        }
        if (darken > 0.003F) {
            graphics.fill(0, 0, width, height, MenuMath.argb(0x000000, darken));
        }
    }

    /** Static, tears and flashes over the whole screen, buttons included. */
    void renderOverlay(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MenuMood mood, MainMenuEventManager events,
                       int width, int height, float time) {
        float grain = mood.grainAlpha + events.grainBoost();
        if (art.grain != null && grain > 0.003F) {
            double scale = MenuDraw.guiScale();
            // one grain texel is about 1.5 real pixels
            float texel = (float) (1.5 / scale);
            float uSpan = width / (GRAIN_SIZE * texel);
            float vSpan = height / (GRAIN_SIZE * texel);
            long frame = mood.animated ? (long) (time * 24.0F) : 0L;
            float u = MenuMath.hash01(frame * 3L + 1L);
            float v = MenuMath.hash01(frame * 3L + 2L);
            batch.begin(graphics, art.grain, false);
            batch.quad(0.0F, 0.0F, width, height, u, v, u + uSpan, v + vSpan, MenuMath.argb(0xFFFFFF, grain));
            batch.end();
        }
        for (int i = 0; i < MainMenuEventManager.TEARS; i++) {
            float alpha = events.tearAlpha(i);
            if (alpha <= 0.003F) {
                continue;
            }
            float y = events.tearY(i) * height;
            float x0 = events.tearX(i) * width;
            float x1 = Math.min(width, x0 + events.tearWidth(i) * width);
            MenuDraw.hline(graphics, x0, x1, y, MenuMath.argb(0xC8C8C8, alpha));
            MenuDraw.hline(graphics, x0 + 3.0F, x1 + 3.0F, y + 1.0F, MenuMath.argb(0x000000, Math.min(1.0F, alpha * 2.0F)));
        }
        float flash = events.flash();
        if (flash > 0.002F) {
            graphics.fill(0, 0, width, height, MenuMath.argb(0x9A9A9A, flash));
        }
    }

    private static void drawFog(GuiGraphics graphics, SpriteBatch batch, MenuTexture fog, MenuMood mood, int width, int height, float time) {
        float drift = mood.animated ? time * 0.010F * (0.3F + 0.7F * mood.motion) : 0.0F;
        float tile = Math.max(1.0F, height * 0.9F);
        float tile2 = Math.max(1.0F, height * 1.6F);
        batch.begin(graphics, fog, false);
        batch.quad(0.0F, 0.0F, width, height, drift, drift * 0.3F, drift + width / tile, drift * 0.3F + height / tile,
                MenuMath.argb(0xA8A8A8, mood.fogAlpha));
        batch.quad(0.0F, 0.0F, width, height, 0.37F - drift * 0.6F, 0.21F + drift * 0.2F,
                0.37F - drift * 0.6F + width / tile2, 0.21F + drift * 0.2F + height / tile2,
                MenuMath.argb(0x8C8C8C, mood.fogAlpha * 0.8F));
        batch.end();
    }

    private static void drawRollingBand(GuiGraphics graphics, MenuMood mood, int width, int height, float time) {
        float period = 11.0F;
        float t = (time % period) / period;
        float bandHeight = height * 0.18F;
        float centre = -bandHeight + t * (height + bandHeight * 2.0F);
        int alpha = (int) (255.0F * (0.012F + 0.006F * mood.stage) * (0.4F + 0.6F * mood.motion));
        if (alpha <= 0) {
            return;
        }
        int top = (int) (centre - bandHeight * 0.5F);
        int mid = (int) centre;
        int bottom = (int) (centre + bandHeight * 0.5F);
        int light = (alpha << 24) | 0xB0B0B0;
        graphics.fillGradient(0, top, width, mid, 0x00B0B0B0, light);
        graphics.fillGradient(0, mid, width, bottom, light, 0x00B0B0B0);
    }

    private static void drawApparitions(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation,
                                        int width, int height) {
        batch.begin(graphics, art.atlas, false);
        MainMenuAnimationManager.Apparition[] extras = animation.extras();
        drawGroup(batch, art.silhouettes, animation.silhouettes(), width, height);
        drawExtras(batch, art, extras, MainMenuAnimationManager.SILHOUETTE, width, height);
        drawGroup(batch, art.faces, animation.faces(), width, height);
        drawExtras(batch, art, extras, MainMenuAnimationManager.FACE, width, height);
        drawGroup(batch, art.mouths, animation.mouths(), width, height);
        drawExtras(batch, art, extras, MainMenuAnimationManager.MOUTH, width, height);
        batch.end();
    }

    private static void drawExtras(SpriteBatch batch, MenuArt art, MainMenuAnimationManager.Apparition[] extras, int kind, int width, int height) {
        List<MenuArt.Sprite> sprites = sprites(art, kind);
        for (MainMenuAnimationManager.Apparition a : extras) {
            if (a.kind == kind && a.visible() && a.sprite < sprites.size()) {
                drawSprite(batch, sprites.get(a.sprite), a, width, height);
            }
        }
    }

    private static void drawGroup(SpriteBatch batch, List<MenuArt.Sprite> sprites, MainMenuAnimationManager.Apparition[] group, int width, int height) {
        for (MainMenuAnimationManager.Apparition a : group) {
            if (a.visible() && a.sprite < sprites.size()) {
                drawSprite(batch, sprites.get(a.sprite), a, width, height);
            }
        }
    }

    private static List<MenuArt.Sprite> sprites(MenuArt art, int kind) {
        return switch (kind) {
            case MainMenuAnimationManager.FACE -> art.faces;
            case MainMenuAnimationManager.EYES -> art.eyes;
            case MainMenuAnimationManager.MOUTH -> art.mouths;
            default -> art.silhouettes;
        };
    }

    private static void drawSprite(SpriteBatch batch, MenuArt.Sprite sprite, MainMenuAnimationManager.Apparition a, int width, int height) {
        float h = a.size * height;
        float w = h * sprite.aspect();
        float vertical = a.kind == MainMenuAnimationManager.MOUTH ? 0.35F + 0.65F * a.open : 1.0F;
        float cx = a.x * width;
        float cy = a.y * height;
        float x0 = cx - w * 0.5F;
        float x1 = cx + w * 0.5F;
        float y0 = cy - h * vertical * 0.5F;
        float y1 = cy + h * vertical * 0.5F;
        float u0 = a.mirror ? sprite.u1 : sprite.u0;
        float u1 = a.mirror ? sprite.u0 : sprite.u1;
        int color = MenuMath.argb(0xFFFFFF, a.alpha);
        if (a.tear <= 0.01F) {
            batch.quad(x0, y0, x1, y1, u0, sprite.v0, u1, sprite.v1, color);
            return;
        }
        // torn into uneven horizontal strips that slide apart
        float t0 = 0.0F;
        for (int k = 0; k < 3; k++) {
            float t1 = k == 2 ? 1.0F : (k + 0.5F + 0.8F * MenuMath.hash01(a.tearSeed + k)) / 3.0F;
            if (t1 <= t0) {
                continue;
            }
            float shift = MenuMath.hashSigned(a.tearSeed * 7L + k) * a.tear * w * 0.06F;
            float vy0 = sprite.v0 + (sprite.v1 - sprite.v0) * t0;
            float vy1 = sprite.v0 + (sprite.v1 - sprite.v0) * t1;
            batch.quad(x0 + shift, y0 + (y1 - y0) * t0, x1 + shift, y0 + (y1 - y0) * t1, u0, vy0, u1, vy1, color);
            t0 = t1;
        }
    }

    /** Additive pass: eye pairs and the pupils of faces and silhouettes. */
    private static void drawGlow(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation, int width, int height) {
        batch.begin(graphics, art.atlas, true);
        for (MainMenuAnimationManager.Apparition a : animation.eyes()) {
            drawEyePair(batch, art, a, width, height);
        }
        for (MainMenuAnimationManager.Apparition a : animation.faces()) {
            drawPupils(batch, art, art.faces, a, width, height, 2.8F, 5.0F);
        }
        for (MainMenuAnimationManager.Apparition a : animation.silhouettes()) {
            drawPupils(batch, art, art.silhouettes, a, width, height, 1.2F, 7.0F);
        }
        for (MainMenuAnimationManager.Apparition a : animation.extras()) {
            switch (a.kind) {
                case MainMenuAnimationManager.EYES -> drawEyePair(batch, art, a, width, height);
                case MainMenuAnimationManager.FACE -> drawPupils(batch, art, art.faces, a, width, height, 2.8F, 5.0F);
                case MainMenuAnimationManager.SILHOUETTE -> drawPupils(batch, art, art.silhouettes, a, width, height, 1.2F, 7.0F);
                default -> {
                }
            }
        }
        batch.end();
    }

    private static void drawEyePair(SpriteBatch batch, MenuArt art, MainMenuAnimationManager.Apparition a, int width, int height) {
        if (a.kind != MainMenuAnimationManager.EYES || !a.visible() || a.sprite >= art.eyes.size()) {
            return;
        }
        MenuArt.Sprite sprite = art.eyes.get(a.sprite);
        float h = a.size * height;
        float w = h * sprite.aspect();
        float open = Math.max(0.06F, a.open);
        float cx = a.x * width;
        float cy = a.y * height;
        float u0 = a.mirror ? sprite.u1 : sprite.u0;
        float u1 = a.mirror ? sprite.u0 : sprite.u1;
        batch.quad(cx - w * 0.5F, cy - h * open * 0.5F, cx + w * 0.5F, cy + h * open * 0.5F, u0, sprite.v0, u1, sprite.v1,
                MenuMath.argb(0xFFFFFF, a.alpha * (0.5F + 0.5F * a.open)));
    }

    private static void drawPupils(SpriteBatch batch, MenuArt art, List<MenuArt.Sprite> sprites, MainMenuAnimationManager.Apparition a,
                                   int width, int height, float brightness, float spread) {
        if (!a.visible() || a.glow <= 0.0F || a.sprite >= sprites.size() || art.glints.isEmpty()) {
            return;
        }
        MenuArt.Sprite sprite = sprites.get(a.sprite);
        if (sprite.eyes.length < 2) {
            return;
        }
        float h = a.size * height;
        float w = h * sprite.aspect();
        float x0 = a.x * width - w * 0.5F;
        float y0 = a.y * height - h * 0.5F;
        float alpha = Math.min(1.0F, a.alpha * brightness) * a.glow * a.open;
        if (alpha <= 0.003F) {
            return;
        }
        MenuArt.Sprite glint = art.glints.get((a.sprite * 7 + (a.mirror ? 1 : 0)) % art.glints.size());
        float size = Math.max(1.5F, sprite.eyeSize * w * spread);
        float tear = a.tear > 0.01F ? MenuMath.hashSigned(a.tearSeed * 7L) * a.tear * w * 0.06F : 0.0F;
        int color = MenuMath.argb(0xFFFFFF, alpha);
        for (int i = 0; i + 1 < sprite.eyes.length; i += 2) {
            float ex = a.mirror ? 1.0F - sprite.eyes[i] : sprite.eyes[i];
            float px = x0 + ex * w + tear;
            float py = y0 + sprite.eyes[i + 1] * h;
            batch.quad(px - size * 0.5F, py - size * 0.5F, px + size * 0.5F, py + size * 0.5F,
                    glint.u0, glint.v0, glint.u1, glint.v1, color);
        }
    }
}
