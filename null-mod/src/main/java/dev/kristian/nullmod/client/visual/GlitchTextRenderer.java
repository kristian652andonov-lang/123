package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kristian.nullmod.client.ClientLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * Big centred corrupted text ("WORLD NOT FOUND"): letters jitter and are swapped for wrong
 * glyphs for a frame or two, red and cyan copies drift beside the white text, and slices of it
 * tear sideways. The text may also be a translation key ({@code null.*}).
 */
final class GlitchTextRenderer {
    private static final String GLYPHS = "#%&@$*+=?/\\|<>_~^01789█▓▒░▄▀■□";
    private static final String[] CHARS = new String[256];
    private static final String[] GLYPH_STRINGS = new String[GLYPHS.length()];

    private static String preparedSource;
    private static String prepared = "";
    private static String[] preparedGlyphs = new String[0];
    private static float[] preparedAdvance = new float[0];
    private static int preparedLength;
    private static float preparedWidth;

    // State for the managed draw (kept static so the Runnable below captures nothing).
    private static GuiGraphics drawGraphics;
    private static float drawAlpha;
    private static long drawBucketSeed;
    private static float drawCorruption;
    private static float drawGhost;
    private static float drawTearShift;
    private static int drawTearChar;
    private static final Runnable DRAW = GlitchTextRenderer::drawLetters;

    static {
        for (int i = 0; i < CHARS.length; i++) {
            CHARS[i] = String.valueOf((char) i);
        }
        for (int i = 0; i < GLYPHS.length(); i++) {
            GLYPH_STRINGS[i] = String.valueOf(GLYPHS.charAt(i));
        }
    }

    private GlitchTextRenderer() {
    }

    static void render(GuiGraphics graphics, float width, float height) {
        String source = VisualDistortionManager.glitchText();
        if (source == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        prepare(font, source);
        if (preparedLength == 0 || preparedWidth <= 0.0F) {
            return;
        }
        double age = VisualDistortionManager.glitchAge();
        int duration = VisualDistortionManager.glitchDuration();
        float flashing = SmoothNoise.clamp01(ClientLimits.flashing());
        boolean strobe = ClientLimits.intenseAllowed() && flashing >= 0.6F;
        float fadeIn = strobe ? 2.0F : 10.0F;
        float fadeOut = strobe ? 5.0F : 16.0F;
        float alpha = Math.min(SmoothNoise.smoothstep(0.0F, fadeIn, (float) age),
                SmoothNoise.smoothstep(0.0F, fadeOut, (float) (duration - age)));
        if (alpha <= 0.02F) {
            return;
        }
        long seed = VisualDistortionManager.glitchSeed();
        double bucketLength = strobe ? 2.0 : 5.0;
        long bucket = (long) Math.floor(age / bucketLength);
        boolean large = VisualDistortionManager.glitchLarge();
        float scale = large
                ? Math.min(width * 0.8F / preparedWidth, height * 0.2F / font.lineHeight)
                : Math.min(width * 0.55F / preparedWidth, height * 0.08F / font.lineHeight);
        // Whole-number scales keep the bitmap font crisp.
        scale = scale >= 2.0F ? (float) Math.floor(scale) : Math.max(1.0F, scale);
        float textW = preparedWidth * scale;
        float jitterX = SmoothNoise.hashSigned(seed ^ bucket, 11L) * (strobe ? 3.0F : 1.0F);
        float jitterY = SmoothNoise.hashSigned(seed ^ bucket, 12L) * (strobe ? 2.0F : 0.6F);
        float x = (width - textW) * 0.5F + jitterX;
        float y = height * 0.42F - font.lineHeight * scale * 0.5F + jitterY;

        drawGraphics = graphics;
        drawAlpha = alpha;
        drawBucketSeed = SmoothNoise.mix(seed + bucket * 0x9E3779B97F4A7C15L);
        drawCorruption = 0.12F + 0.45F * SmoothNoise.clamp01((float) (age / duration));
        drawGhost = 0.6F + 1.2F * SmoothNoise.hash01(drawBucketSeed, 3L);
        // Every few buckets one slice of the text is torn sideways.
        boolean tear = SmoothNoise.hash01(drawBucketSeed, 4L) < (strobe ? 0.45F : 0.2F);
        drawTearShift = tear ? SmoothNoise.hashSigned(drawBucketSeed, 5L) * 6.0F : 0.0F;
        drawTearChar = tear ? (int) (SmoothNoise.hash01(drawBucketSeed, 6L) * preparedLength) : -1;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 50.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawManaged(DRAW);
        pose.popPose();
        drawGraphics = null;
    }

    private static void drawLetters() {
        GuiGraphics g = drawGraphics;
        Font font = Minecraft.getInstance().font;
        int mainAlpha = Math.max(8, Math.round(drawAlpha * 255.0F));
        int ghostAlpha = Math.max(8, Math.round(drawAlpha * 150.0F));
        int white = (mainAlpha << 24) | 0xE8E8E8;
        int red = (ghostAlpha << 24) | 0xFF2A2A;
        int cyan = (ghostAlpha << 24) | 0x22F0FF;
        float cursor = 0.0F;
        for (int i = 0; i < preparedLength; i++) {
            char c = prepared.charAt(i);
            float advance = preparedAdvance[i];
            if (Character.isWhitespace(c)) {
                cursor += advance;
                continue;
            }
            String glyph = preparedGlyphs[i];
            float h1 = SmoothNoise.hash01(drawBucketSeed, i * 7L);
            if (h1 < drawCorruption * 0.35F) {
                glyph = GLYPH_STRINGS[(int) (SmoothNoise.hash01(drawBucketSeed, i * 7L + 1L) * GLYPH_STRINGS.length) % GLYPH_STRINGS.length];
            }
            float jx = 0.0F;
            float jy = 0.0F;
            if (SmoothNoise.hash01(drawBucketSeed, i * 7L + 2L) < drawCorruption * 0.6F) {
                jx = SmoothNoise.hashSigned(drawBucketSeed, i * 7L + 3L) * 1.2F;
                jy = SmoothNoise.hashSigned(drawBucketSeed, i * 7L + 4L) * 1.0F;
            }
            if (i >= drawTearChar && drawTearChar >= 0 && i < drawTearChar + 3) {
                jx += drawTearShift;
            }
            // A dropped letter now and then.
            if (SmoothNoise.hash01(drawBucketSeed, i * 7L + 5L) < drawCorruption * 0.08F) {
                cursor += advance;
                continue;
            }
            float gx = cursor + jx;
            g.drawString(font, glyph, gx - drawGhost, jy, red, false);
            g.drawString(font, glyph, gx + drawGhost, jy + 0.5F, cyan, false);
            g.drawString(font, glyph, gx, jy, white, false);
            cursor += advance;
        }
    }

    private static void prepare(Font font, String source) {
        if (source.equals(preparedSource)) {
            return;
        }
        preparedSource = source;
        String text = source;
        if (text.startsWith("null.") && I18n.exists(text)) {
            text = I18n.get(text);
        }
        prepared = text;
        preparedLength = text.length();
        preparedGlyphs = new String[preparedLength];
        preparedAdvance = new float[preparedLength];
        float width = 0.0F;
        for (int i = 0; i < preparedLength; i++) {
            char c = text.charAt(i);
            String glyph = c < CHARS.length ? CHARS[c] : String.valueOf(c);
            preparedGlyphs[i] = glyph;
            preparedAdvance[i] = font.width(glyph);
            width += preparedAdvance[i];
        }
        preparedWidth = width;
    }
}
