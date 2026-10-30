package dev.kristian.nullmod.client.menu;

import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Draws "THE / CORRUPTED / DIRECTORY" from the distressed glyph sheet as individual quads, so
 * every letter can drift, bleed, tear or turn red on its own. Falls back to the vanilla font,
 * scaled up, when the glyph sheet is unavailable. Always readable: every distortion is small
 * and brief.
 */
final class CorruptedLogoRenderer {
    static final String[] ALTERNATES = {"NULL", "WORLD NOT FOUND", "CONNECTION LOST"};
    static final String WAITING = "WE HAVE BEEN WAITING.";

    private static final int LINE_THE = 0;
    private static final int LINE_CORRUPTED = 1;
    private static final int LINE_DIRECTORY = 2;
    private static final int LINE_ALTERNATE = 3;
    private static final int LINE_WAITING = 4;
    private static final int RED = 0x8E1212;
    private static final float THE_SIZE = 0.38F;
    private static final float DIRECTORY_SIZE = 0.40F;
    private static final float GAP_ABOVE = 0.18F;
    private static final float GAP_BELOW = 0.22F;

    /** One line of glyphs with its layout in sheet pixels and its placement on screen. */
    static final class Run {
        final String text;
        final int[] glyph;
        final float[] pen;
        float width;
        float x;
        float capTop;
        float scale;

        Run(String text) {
            this.text = text;
            this.glyph = new int[text.length()];
            this.pen = new float[text.length()];
        }

        void build(MenuArt.Glyphs glyphs, float tracking) {
            float position = 0.0F;
            float inkEnd = 0.0F;
            for (int i = 0; i < text.length(); i++) {
                int index = glyphs.index(text.charAt(i));
                glyph[i] = index;
                pen[i] = position;
                if (index < 0) {
                    position += glyphs.space + tracking;
                    continue;
                }
                position += glyphs.advance[index];
                inkEnd = position;
                position += tracking;
            }
            width = inkEnd;
        }

        void place(float x, float capTop, float scale) {
            this.x = x;
            this.capTop = capTop;
            this.scale = scale;
        }
    }

    private final Run the = new Run("THE");
    private final Run corrupted = new Run("CORRUPTED");
    private final Run directory = new Run("DIRECTORY");
    private final Run[] alternates = {new Run(ALTERNATES[0]), new Run(ALTERNATES[1]), new Run(ALTERNATES[2])};
    private final Run waiting = new Run(WAITING);

    private MenuArt.Glyphs glyphs;
    private Font font;
    private boolean visible;
    private float left;
    private float top;
    private float right;
    private float bottom;
    private int screenWidth;
    private int screenHeight;

    // vanilla-font fallback layout
    private float fallbackScale;
    private float fallbackRight;
    private float fallbackBottom;
    private float fallbackTheY;
    private float fallbackCorruptedY;
    private float fallbackDirectoryY;

    // effect state
    private float time;
    private int redLine = -1;
    private int redIndex = -1;
    private float redTime;
    private float redLength;
    private float glitchTime;
    private float glitchLength;
    private float glitchStrength;
    private long glitchSeed;
    private float distortTime;
    private float distortLength;
    private int swapIndex = -1;
    private float swapTime;
    private float swapLength;
    private int microLine = -1;
    private int microIndex = -1;
    private float microTime;
    private float microTimer = 3.0F;

    void layout(MenuArt.Glyphs glyphs, Font font, float x, float y, float maxWidth, float maxHeight, int screenWidth, int screenHeight) {
        this.glyphs = glyphs;
        this.font = font;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.visible = maxWidth >= 20.0F && maxHeight >= 8.0F;
        this.left = x;
        this.top = y;
        if (!visible) {
            right = x;
            bottom = y;
            return;
        }
        // the fallback is always laid out too: the glyph texture may still fail to load later
        layoutFallback(font, x, y, maxWidth, maxHeight);
        if (glyphs != null) {
            layoutGlyphs(glyphs, x, y, maxWidth, maxHeight);
        } else {
            right = fallbackRight;
            bottom = fallbackBottom;
        }
    }

    private void layoutGlyphs(MenuArt.Glyphs glyphs, float x, float y, float maxWidth, float maxHeight) {
        float cap = glyphs.capHeight();
        the.build(glyphs, cap * 0.16F);
        corrupted.build(glyphs, cap * 0.035F);
        directory.build(glyphs, 0.0F);
        // "DIRECTORY" is letter-spaced out to the width of "CORRUPTED"
        int gaps = directory.text.length() - 1;
        float tracking = Math.max(cap * 0.12F, (corrupted.width / DIRECTORY_SIZE - directory.width) / gaps);
        directory.build(glyphs, tracking);

        float blockWidth = Math.max(corrupted.width, Math.max(the.width * THE_SIZE, directory.width * DIRECTORY_SIZE));
        float blockHeight = cap * (THE_SIZE + GAP_ABOVE + 1.0F + GAP_BELOW + DIRECTORY_SIZE);
        float s = Math.min(maxWidth / Math.max(1.0F, blockWidth), maxHeight / blockHeight);

        float yy = y;
        the.place(x, yy, s * THE_SIZE);
        yy += cap * s * (THE_SIZE + GAP_ABOVE);
        corrupted.place(x, yy, s);
        yy += cap * s * (1.0F + GAP_BELOW);
        directory.place(x, yy, s * DIRECTORY_SIZE);
        yy += cap * s * DIRECTORY_SIZE;
        right = x + blockWidth * s;
        bottom = yy;

        float capCorrupted = cap * s;
        for (Run alternate : alternates) {
            alternate.build(glyphs, cap * 0.05F);
            float scale = Math.min(s * 1.05F, (right - left) / Math.max(1.0F, alternate.width));
            alternate.place(x, corrupted.capTop + (capCorrupted - cap * scale) * 0.5F, scale);
        }
        waiting.build(glyphs, cap * 0.10F);
        float ws = Math.min(screenHeight * 0.07F / cap, screenWidth * 0.82F / Math.max(1.0F, waiting.width));
        waiting.place((screenWidth - waiting.width * ws) * 0.5F, screenHeight * 0.5F - cap * ws * 0.5F, ws);
    }

    private void layoutFallback(Font font, float x, float y, float maxWidth, float maxHeight) {
        float wordWidth = Math.max(1, font.width("CORRUPTED"));
        // THE (0.5) + gap + CORRUPTED (1) + gap + DIRECTORY (0.5), in units of the scaled line height
        float blockHeight = 9.0F * (0.5F + 0.2F + 1.0F + 0.2F + 0.5F);
        fallbackScale = Math.max(0.5F, Math.min(maxWidth / wordWidth, maxHeight / blockHeight));
        float line = 9.0F * fallbackScale;
        fallbackTheY = y;
        fallbackCorruptedY = y + line * 0.7F;
        fallbackDirectoryY = fallbackCorruptedY + line * 1.2F;
        fallbackRight = x + wordWidth * fallbackScale;
        fallbackBottom = fallbackDirectoryY + line * 0.5F;
    }

    float left() {
        return left;
    }

    float top() {
        return top;
    }

    float right() {
        return right;
    }

    float bottom() {
        return visible ? bottom : top;
    }

    boolean visible() {
        return visible;
    }

    /** False when the logo is drawn with the vanilla font because the glyph sheet is unavailable. */
    boolean usesGlyphs() {
        return glyphs != null;
    }

    // ---------------------------------------------------------------------------------------
    // effects

    void reset() {
        redLine = -1;
        redIndex = -1;
        glitchTime = 0.0F;
        distortTime = 0.0F;
        swapIndex = -1;
        swapTime = 0.0F;
        microTime = 0.0F;
        microTimer = 3.0F;
    }

    /** One letter slowly turns red, stays a moment and fades back. */
    void triggerRedLetter(Random random) {
        int line = random.nextFloat() < 0.7F ? LINE_CORRUPTED : (random.nextBoolean() ? LINE_THE : LINE_DIRECTORY);
        Run run = run(line);
        redLine = line;
        redIndex = random.nextInt(run.text.length());
        redTime = 0.0F;
        redLength = MenuMath.range(random, 0.9F, 2.4F);
    }

    /** The title glitches: letters jump, tear and briefly become other letters. */
    void triggerGlitch(Random random, float strength, float seconds) {
        glitchStrength = MenuMath.clamp01(strength);
        glitchLength = Math.max(0.05F, seconds);
        glitchTime = glitchLength;
        glitchSeed = random.nextLong();
    }

    /** The whole logo distorts (wavy slices, stronger misprint). */
    void triggerDistort(Random random, float seconds) {
        distortLength = Math.max(0.1F, seconds);
        distortTime = distortLength;
        glitchSeed = random.nextLong();
    }

    /** The title briefly becomes NULL / WORLD NOT FOUND / CONNECTION LOST. */
    void triggerSwap(Random random, float seconds) {
        swapIndex = random.nextInt(alternates.length);
        swapLength = Math.max(0.2F, seconds);
        swapTime = swapLength;
        triggerGlitch(random, 0.9F, 0.12F);
    }

    void update(float dt, MenuMood mood, Random random) {
        time += dt;
        if (redLine >= 0) {
            redTime += dt;
            if (redTime > redLength) {
                redLine = -1;
            }
        }
        glitchTime = Math.max(0.0F, glitchTime - dt);
        distortTime = Math.max(0.0F, distortTime - dt);
        if (swapTime > 0.0F) {
            float before = swapTime;
            swapTime = Math.max(0.0F, swapTime - dt);
            if (before > 0.09F && swapTime <= 0.09F) {
                triggerGlitch(random, 0.9F, 0.12F);
            }
            if (swapTime <= 0.0F) {
                swapIndex = -1;
            }
        }
        microTime = Math.max(0.0F, microTime - dt);
        if (mood.glitch > 0.01F) {
            microTimer -= dt;
            if (microTimer <= 0.0F) {
                microTimer = MenuMath.range(random, 2.5F, 7.0F) / (0.4F + mood.glitch);
                microLine = random.nextInt(3);
                microIndex = random.nextInt(run(microLine).text.length());
                microTime = MenuMath.range(random, 0.06F, 0.16F);
            }
        }
    }

    private Run run(int line) {
        return switch (line) {
            case LINE_THE -> the;
            case LINE_DIRECTORY -> directory;
            default -> corrupted;
        };
    }

    private float redAmount() {
        if (redLine < 0) {
            return 0.0F;
        }
        float in = MenuMath.smooth(redTime / 0.35F);
        float out = MenuMath.smooth((redLength - redTime) / 0.6F);
        return Math.min(in, out);
    }

    private float glitchAmount() {
        if (glitchTime <= 0.0F) {
            return 0.0F;
        }
        return glitchStrength * MenuMath.smooth(glitchTime / Math.min(0.08F, glitchLength));
    }

    private float distortAmount() {
        if (distortTime <= 0.0F) {
            return 0.0F;
        }
        float t = 1.0F - distortTime / distortLength;
        return MenuMath.smooth(t / 0.15F) * MenuMath.smooth((1.0F - t) / 0.3F);
    }

    private float swapAmount() {
        if (swapIndex < 0 || swapTime <= 0.0F) {
            return 0.0F;
        }
        float shown = swapLength - swapTime;
        return Math.min(MenuMath.clamp01(shown / 0.07F), MenuMath.clamp01(swapTime / 0.07F));
    }

    // ---------------------------------------------------------------------------------------
    // drawing

    void render(GuiGraphics graphics, SpriteBatch batch, float alpha, MenuMood mood) {
        if (!visible || alpha <= 0.004F) {
            return;
        }
        if (glyphs == null || !batch.begin(graphics, glyphs.texture, false)) {
            renderFallback(graphics, alpha);
            return;
        }
        float swap = swapAmount();
        float normal = 1.0F - swap;
        if (normal > 0.01F) {
            drawRun(batch, the, LINE_THE, 0xB8B8B8, alpha * 0.92F * normal, mood);
            drawRun(batch, corrupted, LINE_CORRUPTED, 0xE8E8E8, alpha * normal, mood);
            drawRun(batch, directory, LINE_DIRECTORY, 0xA8A8A8, alpha * 0.90F * normal, mood);
        }
        if (swap > 0.01F && swapIndex >= 0) {
            drawRun(batch, alternates[swapIndex], LINE_ALTERNATE, 0xDCDCDC, alpha * swap, mood);
        }
        batch.end();
    }

    /** "WE HAVE BEEN WAITING." centred on the screen. */
    void renderWaiting(GuiGraphics graphics, SpriteBatch batch, float alpha, MenuMood mood) {
        if (alpha <= 0.004F) {
            return;
        }
        if (glyphs == null || !batch.begin(graphics, glyphs.texture, false)) {
            float scale = Math.max(1.0F, Math.min(screenWidth * 0.8F / Math.max(1, font.width(WAITING)), screenHeight * 0.06F / 9.0F));
            float x = (screenWidth - font.width(WAITING) * scale) * 0.5F;
            drawScaled(graphics, WAITING, x, screenHeight * 0.5F - 4.5F * scale, scale, 0xD0D0D0, alpha);
            return;
        }
        drawRun(batch, waiting, LINE_WAITING, 0xD4D4D4, alpha, mood);
        batch.end();
    }

    private void drawRun(SpriteBatch batch, Run run, int line, int rgb, float alpha, MenuMood mood) {
        MenuArt.Glyphs g = glyphs;
        float s = run.scale;
        float cap = g.capHeight() * s;
        float glitch = glitchAmount();
        float distort = distortAmount();
        float red = line == redLine ? redAmount() : 0.0F;
        long frame = (long) (time * 20.0F);
        for (int i = 0; i < run.glyph.length; i++) {
            int index = run.glyph[i];
            if (index < 0) {
                continue;
            }
            long key = ((long) line * 131L + i) * 0x9E3779B1L + frame * 0x632BE5ABL + glitchSeed;
            int drawn = index;
            float dx = 0.0F;
            float dy = 0.0F;
            int slices = 1;
            float sliceAmp = 0.0F;
            // a slow, uneven breathing of each letter's ink
            float bright = 0.90F + 0.10F * Mth.sin(time * 0.8F + i * 1.7F + line * 2.3F);
            int color = MenuMath.scaleRgb(rgb, bright);
            if (red > 0.0F && i == redIndex) {
                color = MenuMath.lerpRgb(red, color, RED);
            }
            if (glitch > 0.0F) {
                if (MenuMath.hash01(key) < glitch * 0.45F) {
                    dx = MenuMath.hashSigned(key + 1) * cap * 0.08F * glitch;
                    dy = MenuMath.hashSigned(key + 2) * cap * 0.03F * glitch;
                    slices = 3;
                    sliceAmp = cap * 0.10F * glitch;
                }
                if (glitch > 0.5F && MenuMath.hash01(key + 3) < glitch * 0.10F) {
                    drawn = Math.min(g.chars.length() - 1, (int) (MenuMath.hash01(key + 4) * g.chars.length()));
                }
            }
            if (distort > 0.0F) {
                slices = 4;
                sliceAmp = Math.max(sliceAmp, cap * 0.12F * distort);
                dx += Mth.sin(time * 23.0F + i * 0.9F) * cap * 0.03F * distort * (0.3F + 0.7F * mood.shake);
            }
            if (microTime > 0.0F && line == microLine && i == microIndex) {
                slices = Math.max(slices, 2);
                sliceAmp = Math.max(sliceAmp, cap * 0.05F);
            }
            float x0 = run.x + (run.pen[i] - g.left[drawn]) * s + dx;
            float y0 = run.capTop - g.capTop * s + dy;
            float w = g.cellWidth * s;
            float h = g.cellHeight * s;
            emit(batch, drawn, x0, y0, w, h, slices, sliceAmp, key, MenuMath.argb(color, alpha));
            // misregistered second print: a faint offset copy, stronger while glitching
            float ghost = (0.07F + 0.25F * glitch + 0.20F * distort) * alpha;
            if (ghost > 0.01F) {
                emit(batch, drawn, x0 + cap * (0.018F + 0.05F * glitch), y0 + cap * 0.008F, w, h, 1, 0.0F, key,
                        MenuMath.argb(color, ghost));
            }
        }
    }

    private void emit(SpriteBatch batch, int index, float x0, float y0, float w, float h, int slices, float amplitude, long key, int argb) {
        MenuArt.Glyphs g = glyphs;
        float u0 = g.u0(index);
        float v0 = g.v0(index);
        float u1 = g.u1(index);
        float v1 = g.v1(index);
        if (slices <= 1) {
            batch.quad(x0, y0, x0 + w, y0 + h, u0, v0, u1, v1, argb);
            return;
        }
        float t0 = 0.0F;
        for (int k = 0; k < slices; k++) {
            float t1 = k == slices - 1 ? 1.0F : (k + 0.6F + 0.8F * MenuMath.hash01(key * 7L + k)) / slices;
            if (t1 <= t0) {
                continue;
            }
            float offset = MenuMath.hashSigned(key * 13L + k) * amplitude;
            batch.quad(x0 + offset, y0 + h * t0, x0 + w + offset, y0 + h * t1, u0, v0 + (v1 - v0) * t0, u1, v0 + (v1 - v0) * t1, argb);
            t0 = t1;
        }
    }

    private void renderFallback(GuiGraphics graphics, float alpha) {
        float red = redAmount();
        int corruptedColor = redLine == LINE_CORRUPTED ? MenuMath.lerpRgb(red * 0.6F, 0xE8E8E8, RED) : 0xE8E8E8;
        if (swapAmount() > 0.5F && swapIndex >= 0) {
            String text = ALTERNATES[swapIndex];
            float scale = Math.min(fallbackScale, (fallbackRight - left) / Math.max(1, font.width(text)));
            drawScaled(graphics, text, left, fallbackCorruptedY, scale, 0xDCDCDC, alpha);
            return;
        }
        drawScaled(graphics, "THE", left, fallbackTheY, fallbackScale * 0.5F, 0xB8B8B8, alpha * 0.92F);
        drawScaled(graphics, "CORRUPTED", left, fallbackCorruptedY, fallbackScale, corruptedColor, alpha);
        drawScaled(graphics, "DIRECTORY", left, fallbackDirectoryY, fallbackScale * 0.5F, 0xA8A8A8, alpha * 0.9F);
    }

    private void drawScaled(GuiGraphics graphics, String text, float x, float y, float scale, int rgb, float alpha) {
        int color = MenuMath.argb(rgb, alpha);
        if (!MenuMath.visible(color)) {
            return;
        }
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.drawString(font, text, 0, 0, color, false);
        } finally {
            graphics.pose().popPose();
        }
    }
}
