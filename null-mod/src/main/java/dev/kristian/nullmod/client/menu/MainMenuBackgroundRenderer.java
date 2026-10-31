package dev.kristian.nullmod.client.menu;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the almost black, grayscale background: drifting darkness, the user's faces (their pupils
 * shifted towards the mouse cursor, glints in their eyes), the generated eyes, mouths and
 * silhouettes, a darker band behind the logo and the buttons, the vignette, darkening and (over
 * everything, including the buttons) a thin layer of static, rare torn scan lines and dim flashes.
 * A handful of batched quads per frame; nothing is allocated while drawing.
 */
final class MainMenuBackgroundRenderer {
    private static final int GRAIN_SIZE = 256;
    /**
     * A pupil patch is drawn more opaque than its face (and darkened to match), so that it fully
     * covers the filled-in hole it was cut from even while the face is faint.
     */
    private static final float PATCH_OPACITY = 3.5F;
    /** Below this share of its full resolution a face is drawn from its half-size copy (no mipmaps). */
    private static final float HALF_SIZE_BELOW = 0.62F;

    /** Everything below the logo and the buttons of the main menu. {@code scrimRight}: fraction of the width. */
    void render(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation, MenuMood mood,
                int width, int height, float time, float darken, float scrimRight) {
        graphics.fill(0, 0, width, height, 0xFF000000 | mood.baseColor);
        if (art.fog != null) {
            drawFog(graphics, batch, art.fog, mood, width, height, time);
        }
        if (art.hasApparitions()) {
            MainMenuAnimationManager.Apparition[] extras = animation.extras();
            if (art.atlas != null && batch.begin(graphics, art.atlas, false)) {
                drawGroup(batch, art.silhouettes, animation.silhouettes(), width, height);
                drawExtras(batch, art, extras, MainMenuAnimationManager.SILHOUETTE, width, height);
                batch.end();
            }
            drawPortraits(graphics, batch, art, animation, mood, width, height, true);
            if (art.atlas != null && batch.begin(graphics, art.atlas, false)) {
                drawGroup(batch, art.mouths, animation.mouths(), width, height);
                drawExtras(batch, art, extras, MainMenuAnimationManager.MOUTH, width, height);
                batch.end();
            }
            drawGlow(graphics, batch, art, animation, width, height);
            drawGlints(graphics, batch, art, animation, mood, width, height, true);
        }
        drawScrim(graphics, mood.scrim, width, height, scrimRight);
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

    /**
     * The same darkness behind every other screen outside a world: fog, a few faces and eye pairs
     * at the edges, the vignette. Quieter than the main menu (no mouths, silhouettes or events),
     * so lists and text stay easy to read.
     */
    void renderBackdrop(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation, MenuMood mood,
                        int width, int height, float time) {
        graphics.fill(0, 0, width, height, 0xFF000000 | mood.baseColor);
        if (art.fog != null) {
            drawFog(graphics, batch, art.fog, mood, width, height, time);
        }
        if (art.hasApparitions()) {
            drawPortraits(graphics, batch, art, animation, mood, width, height, false);
            drawGlow(graphics, batch, art, animation, width, height);
            drawGlints(graphics, batch, art, animation, mood, width, height, false);
        }
        if (art.vignette != null) {
            batch.begin(graphics, art.vignette, false);
            batch.quad(0.0F, 0.0F, width, height, 0.0F, 0.0F, 1.0F, 1.0F, MenuMath.argb(0xFFFFFF, mood.vignette));
            batch.end();
        }
    }

    /** Static, tears and flashes over the whole screen, buttons included. */
    void renderOverlay(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MenuMood mood, MainMenuEventManager events,
                       int width, int height, float time) {
        drawGrain(graphics, batch, art, mood.grainAlpha + events.grainBoost(), mood.animated, width, height, time);
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

    /** A thin layer of film grain over the whole screen (one grain texel is about 1.5 real pixels). */
    static void drawGrain(GuiGraphics graphics, SpriteBatch batch, MenuArt art, float alpha, boolean animated,
                          int width, int height, float time) {
        if (art.grain == null || alpha <= 0.003F) {
            return;
        }
        double scale = MenuDraw.guiScale();
        float texel = (float) (1.5 / scale);
        float uSpan = width / (GRAIN_SIZE * texel);
        float vSpan = height / (GRAIN_SIZE * texel);
        long frame = animated ? (long) (time * 24.0F) : 0L;
        float u = MenuMath.hash01(frame * 3L + 1L);
        float v = MenuMath.hash01(frame * 3L + 2L);
        if (batch.begin(graphics, art.grain, false)) {
            batch.quad(0.0F, 0.0F, width, height, u, v, u + uSpan, v + vSpan, MenuMath.argb(0xFFFFFF, Math.min(1.0F, alpha)));
            batch.end();
        }
    }

    private static void drawFog(GuiGraphics graphics, SpriteBatch batch, MenuTexture fog, MenuMood mood, int width, int height, float time) {
        float drift = mood.animated ? time * 0.010F * (0.3F + 0.7F * mood.motion) : 0.0F;
        float tile = Math.max(1.0F, height * 0.9F);
        float tile2 = Math.max(1.0F, height * 1.6F);
        if (!batch.begin(graphics, fog, false)) {
            return;
        }
        batch.quad(0.0F, 0.0F, width, height, drift, drift * 0.3F, drift + width / tile, drift * 0.3F + height / tile,
                MenuMath.argb(0xA8A8A8, mood.fogAlpha));
        batch.quad(0.0F, 0.0F, width, height, 0.37F - drift * 0.6F, 0.21F + drift * 0.2F,
                0.37F - drift * 0.6F + width / tile2, 0.21F + drift * 0.2F + height / tile2,
                MenuMath.argb(0x8C8C8C, mood.fogAlpha * 0.8F));
        batch.end();
    }

    /** Darker towards the left edge, so the logo and the buttons stay readable over any face. */
    private static void drawScrim(GuiGraphics graphics, float strength, int width, int height, float scrimRight) {
        if (strength <= 0.003F || scrimRight <= 0.0F) {
            return;
        }
        float right = Math.min(1.0F, scrimRight) * width;
        float solid = right * 0.35F;
        graphics.fill(0, 0, Math.round(solid), height, MenuMath.argb(0x000000, strength));
        // a smooth step from full strength to nothing, in four linear pieces
        float previous = Math.round(solid);
        float previousAlpha = strength;
        for (int i = 1; i <= 4; i++) {
            float t = i / 4.0F;
            float x = solid + (right - solid) * t;
            float alpha = strength * (1.0F - MenuMath.smooth(t));
            MenuDraw.hgradient(graphics, previous, 0.0F, x, height, MenuMath.argb(0x000000, previousAlpha), MenuMath.argb(0x000000, alpha));
            previous = x;
            previousAlpha = alpha;
        }
    }

    private static void drawRollingBand(GuiGraphics graphics, MenuMood mood, int width, int height, float time) {
        float period = 11.0F;
        float t = (time % period) / period;
        float bandHeight = height * 0.18F;
        float centre = -bandHeight + t * (height + bandHeight * 2.0F);
        int alpha = (int) (255.0F * (0.012F + 0.006F * Math.min(5, mood.stage)) * (0.4F + 0.6F * mood.motion));
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

    // ---------------------------------------------------------------------------------------
    // the user's faces

    /** The dominant face (behind everything), the regular faces, then the faces summoned by events, close-ups last. */
    private static void drawPortraits(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation,
                                      MenuMood mood, int width, int height, boolean withExtras) {
        if (!art.hasPortraits() || !batch.begin(graphics, art.portraits.texture, false)) {
            return;
        }
        float guiScale = (float) MenuDraw.guiScale();
        float gazeX = animation.gazeX() * width;
        float gazeY = animation.gazeY() * height;
        MenuArt.Portraits portraits = art.portraits;
        drawPortrait(batch, portraits, animation.dominant(), width, height, guiScale, gazeX, gazeY, mood.follow);
        for (MainMenuAnimationManager.Apparition a : animation.faces()) {
            drawPortrait(batch, portraits, a, width, height, guiScale, gazeX, gazeY, mood.follow);
        }
        if (withExtras) {
            for (MainMenuAnimationManager.Apparition a : animation.extras()) {
                if (a.kind == MainMenuAnimationManager.FACE && !a.closeUp) {
                    drawPortrait(batch, portraits, a, width, height, guiScale, gazeX, gazeY, mood.follow);
                }
            }
            for (MainMenuAnimationManager.Apparition a : animation.extras()) {
                if (a.kind == MainMenuAnimationManager.FACE && a.closeUp) {
                    drawPortrait(batch, portraits, a, width, height, guiScale, gazeX, gazeY, mood.follow);
                }
            }
        }
        batch.end();
    }

    private static void drawPortrait(SpriteBatch batch, MenuArt.Portraits portraits, MainMenuAnimationManager.Apparition a,
                                     int width, int height, float guiScale, float gazeX, float gazeY, float follow) {
        if (a.kind != MainMenuAnimationManager.FACE || !a.visible()) {
            return;
        }
        MenuArt.Portrait portrait = portraits.get(a.sprite);
        float h = a.size * a.scale * height;
        float w = h * portrait.aspect();
        boolean half = h * guiScale < portrait.full.height * HALF_SIZE_BELOW;
        MenuArt.Sprite sprite = half ? portrait.half : portrait.full;
        float x0 = a.x * width - w * 0.5F;
        float y0 = a.y * height - h * 0.5F;
        float u0 = a.mirror ? sprite.u1 : sprite.u0;
        float u1 = a.mirror ? sprite.u0 : sprite.u1;
        drawTorn(batch, a, x0, y0, x0 + w, y0 + h, u0, sprite.v0, u1, sprite.v1, MenuMath.argb(0xFFFFFF, a.alpha), w);

        // the pupils of pale eyes, shifted towards the mouse cursor
        float opacity = Math.min(1.0F, a.alpha * PATCH_OPACITY);
        if (opacity <= 0.003F) {
            return;
        }
        int grey = Math.round(MenuMath.clamp01(a.alpha / opacity) * 255.0F);
        int color = MenuMath.argb((grey << 16) | (grey << 8) | grey, opacity);
        for (MenuArt.Eye eye : portrait.eyes) {
            if (eye.patch == null) {
                continue;
            }
            MenuArt.Sprite patch = half ? eye.halfPatch : eye.patch;
            float ex = a.mirror ? 1.0F - eye.x : eye.x;
            float px = x0 + ex * w + tearShift(a, eye.y, w);
            float py = y0 + eye.y * h;
            float reach = reach(eye, w, height, follow, px, py, gazeX, gazeY);
            float dx = gazeX - px;
            float dy = gazeY - py;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float ox = dist > 0.001F ? dx / dist * reach : 0.0F;
            float oy = dist > 0.001F ? dy / dist * reach : 0.0F;
            float size = eye.patchSize * w;
            float sizeY = size * patch.height / Math.max(1.0F, patch.width);
            float cx = a.mirror ? 1.0F - eye.patchX : eye.patchX;
            float qx = px + ox - cx * size;
            float qy = py + oy - eye.patchY * sizeY;
            float pu0 = a.mirror ? patch.u1 : patch.u0;
            float pu1 = a.mirror ? patch.u0 : patch.u1;
            batch.quad(qx, qy, qx + size, qy + sizeY, pu0, patch.v0, pu1, patch.v1, color);
        }
    }

    /** How far a pupil moves towards the cursor: at most its travel, less when the cursor is close. */
    private static float reach(MenuArt.Eye eye, float faceWidth, int height, float follow, float px, float py, float gazeX, float gazeY) {
        if (follow <= 0.0F || eye.travel <= 0.0F) {
            return 0.0F;
        }
        float dx = gazeX - px;
        float dy = gazeY - py;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return eye.travel * faceWidth * follow * Math.min(1.0F, dist / Math.max(1.0F, 0.3F * height));
    }

    /** Additive pass: a glint in every eye of every face, moving with the pupil. */
    private static void drawGlints(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation,
                                   MenuMood mood, int width, int height, boolean withExtras) {
        if (!art.hasPortraits() || !batch.begin(graphics, art.portraits.texture, true)) {
            return;
        }
        float gazeX = animation.gazeX() * width;
        float gazeY = animation.gazeY() * height;
        MenuArt.Portraits portraits = art.portraits;
        drawFaceGlints(batch, portraits, animation.dominant(), mood, width, height, gazeX, gazeY);
        for (MainMenuAnimationManager.Apparition a : animation.faces()) {
            drawFaceGlints(batch, portraits, a, mood, width, height, gazeX, gazeY);
        }
        if (withExtras) {
            for (MainMenuAnimationManager.Apparition a : animation.extras()) {
                drawFaceGlints(batch, portraits, a, mood, width, height, gazeX, gazeY);
            }
        }
        batch.end();
    }

    private static void drawFaceGlints(SpriteBatch batch, MenuArt.Portraits portraits, MainMenuAnimationManager.Apparition a,
                                       MenuMood mood, int width, int height, float gazeX, float gazeY) {
        if (a.kind != MainMenuAnimationManager.FACE || !a.visible() || a.glow <= 0.0F) {
            return;
        }
        float strength = Math.min(1.0F, a.alpha * mood.glint * 1.6F * a.glow) * a.open;
        if (strength <= 0.003F) {
            return;
        }
        MenuArt.Portrait portrait = portraits.get(a.sprite);
        MenuArt.Sprite glint = portraits.glint;
        float h = a.size * a.scale * height;
        float w = h * portrait.aspect();
        float x0 = a.x * width - w * 0.5F;
        float y0 = a.y * height - h * 0.5F;
        int color = MenuMath.argb(0xFFFFFF, strength);
        for (MenuArt.Eye eye : portrait.eyes) {
            float ex = a.mirror ? 1.0F - eye.x : eye.x;
            float px = x0 + ex * w + tearShift(a, eye.y, w);
            float py = y0 + eye.y * h;
            float reach = reach(eye, w, height, mood.follow, px, py, gazeX, gazeY) * 1.15F;
            float dx = gazeX - px;
            float dy = gazeY - py;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float gx = px + (dist > 0.001F ? dx / dist * reach : 0.0F);
            float gy = py + (dist > 0.001F ? dy / dist * reach : 0.0F);
            float size = Math.max(1.5F, eye.glint * w);
            // the highlight sits a little up and to the left of the pupil's centre
            batch.quad(gx - size * 0.62F, gy - size * 0.62F, gx + size * 0.38F, gy + size * 0.38F,
                    glint.u0, glint.v0, glint.u1, glint.v1, color);
        }
    }

    // ---------------------------------------------------------------------------------------
    // the generated apparitions

    private static void drawExtras(SpriteBatch batch, MenuArt art, MainMenuAnimationManager.Apparition[] extras, int kind, int width, int height) {
        List<MenuArt.Sprite> sprites = kind == MainMenuAnimationManager.MOUTH ? art.mouths : art.silhouettes;
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
        drawTorn(batch, a, x0, y0, x1, y1, u0, sprite.v0, u1, sprite.v1, MenuMath.argb(0xFFFFFF, a.alpha), w);
    }

    /** Draws the quad whole, or torn into three uneven horizontal strips that slide apart. */
    private static void drawTorn(SpriteBatch batch, MainMenuAnimationManager.Apparition a, float x0, float y0, float x1, float y1,
                                 float u0, float v0, float u1, float v1, int color, float w) {
        if (a.tear <= 0.01F) {
            batch.quad(x0, y0, x1, y1, u0, v0, u1, v1, color);
            return;
        }
        float t0 = 0.0F;
        for (int k = 0; k < 3; k++) {
            float t1 = tearEdge(a, k);
            if (t1 <= t0) {
                continue;
            }
            float shift = MenuMath.hashSigned(a.tearSeed * 7L + k) * a.tear * w * 0.06F;
            float vy0 = v0 + (v1 - v0) * t0;
            float vy1 = v0 + (v1 - v0) * t1;
            batch.quad(x0 + shift, y0 + (y1 - y0) * t0, x1 + shift, y0 + (y1 - y0) * t1, u0, vy0, u1, vy1, color);
            t0 = t1;
        }
    }

    /** Lower edge (0..1 of the height) of torn strip k. */
    private static float tearEdge(MainMenuAnimationManager.Apparition a, int k) {
        return k == 2 ? 1.0F : (k + 0.5F + 0.8F * MenuMath.hash01(a.tearSeed + k)) / 3.0F;
    }

    /** How far the strip containing height fraction {@code at} slid sideways (0 when not torn). */
    private static float tearShift(MainMenuAnimationManager.Apparition a, float at, float w) {
        if (a.tear <= 0.01F) {
            return 0.0F;
        }
        float t0 = 0.0F;
        for (int k = 0; k < 3; k++) {
            float t1 = tearEdge(a, k);
            if (t1 <= t0) {
                continue;
            }
            if (at < t1 || k == 2) {
                return MenuMath.hashSigned(a.tearSeed * 7L + k) * a.tear * w * 0.06F;
            }
            t0 = t1;
        }
        return 0.0F;
    }

    /** Additive pass: eye pairs and the pupils of silhouettes. */
    private static void drawGlow(GuiGraphics graphics, SpriteBatch batch, MenuArt art, MainMenuAnimationManager animation, int width, int height) {
        if (art.atlas == null || !batch.begin(graphics, art.atlas, true)) {
            return;
        }
        for (MainMenuAnimationManager.Apparition a : animation.eyes()) {
            drawEyePair(batch, art, a, width, height);
        }
        for (MainMenuAnimationManager.Apparition a : animation.silhouettes()) {
            drawPupils(batch, art, art.silhouettes, a, width, height, 1.2F, 7.0F);
        }
        for (MainMenuAnimationManager.Apparition a : animation.extras()) {
            switch (a.kind) {
                case MainMenuAnimationManager.EYES -> drawEyePair(batch, art, a, width, height);
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
        float tear = tearShift(a, 0.0F, w);
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
