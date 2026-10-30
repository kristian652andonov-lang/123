package dev.kristian.nullmod.client.menu;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * A main menu button: thin white outline, near-black body, white pixel-font label. Hovering
 * (or keyboard focus) brightens it and slides the label in a little; pressing it glitches for
 * a moment before the action runs. It can be told to show the wrong words or to flicker for a
 * while; it keeps working the whole time and is always narrated with its real label.
 */
final class NullMenuButton extends AbstractButton {
    private static final long PRESS_GLITCH_MS = 260L;
    private static final long WRONG_EDGE_MS = 70L;

    private final CorruptedMainMenu menu;
    private final Runnable action;
    private final Component wrongText;

    private float hover;
    private boolean wasHot;
    private long lastFrameMs;
    private long pressedAtMs;
    private boolean pressedOnce;
    private long pressSeed;
    private long wrongFromMs;
    private long wrongUntilMs;
    private long flickerUntilMs;
    private long flickerSeed;
    private Component cachedComponent;
    private FormattedCharSequence cachedSequence;

    NullMenuButton(int x, int y, int width, int height, Component message, Component wrongText, CorruptedMainMenu menu, Runnable action) {
        super(x, y, width, height, message);
        this.menu = menu;
        this.action = action;
        this.wrongText = wrongText;
    }

    @Override
    public void onPress() {
        long now = Util.getMillis();
        pressedAtMs = now;
        pressedOnce = true;
        pressSeed = now * 31L + getY();
        menu.requestAction(action, CorruptedMainMenu.PRESS_DELAY_MS);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        if (!menu.audio().click()) {
            super.playDownSound(soundManager);
        }
    }

    /** Shows the wrong label for a moment; the button keeps doing what it always does. */
    void showWrongText(float seconds) {
        long now = Util.getMillis();
        wrongFromMs = now;
        wrongUntilMs = now + (long) (seconds * 1000.0F);
    }

    void flicker(float seconds) {
        long now = Util.getMillis();
        flickerUntilMs = now + (long) (seconds * 1000.0F);
        flickerSeed = now;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrameMs == 0L ? 0.0F : MenuMath.clamp((now - lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        lastFrameMs = now;

        float ui = menu.uiAlpha();
        boolean hot = active && isHoveredOrFocused();
        if (hot && !wasHot && ui > 0.5F) {
            menu.onButtonHovered();
        }
        wasHot = hot;
        hover = MenuMath.approach(hover, hot ? 1.0F : 0.0F, dt * 6.5F);
        float ease = MenuMath.smooth(hover);

        float alpha = ui * flickerFactor(now);
        if (alpha <= 0.01F) {
            return;
        }
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        float pressed = pressedOnce ? MenuMath.clamp01(1.0F - (now - pressedAtMs) / (float) PRESS_GLITCH_MS) : 0.0F;
        boolean wrong = now < wrongUntilMs;
        float wrongEdge = wrong ? Math.max(MenuMath.clamp01(1.0F - (now - wrongFromMs) / (float) WRONG_EDGE_MS),
                MenuMath.clamp01(1.0F - (wrongUntilMs - now) / (float) WRONG_EDGE_MS)) : 0.0F;
        float glitch = Math.max(pressed, wrongEdge);
        long frame = now / 35L;

        // body
        int body = MenuMath.lerpRgb(ease, 0x050505, 0x161616);
        graphics.fill(x, y, x + w, y + h, MenuMath.argb(body, (0.55F + 0.25F * ease) * alpha));

        // outline
        int lineRgb = active ? MenuMath.lerpRgb(ease, 0x8C8C8C, 0xF2F2F2) : 0x4A4A4A;
        float lineAlpha = (active ? 0.62F + 0.38F * ease : 0.55F) * alpha;
        MenuDraw.outline(graphics, x, y, w, h, MenuMath.argb(lineRgb, lineAlpha), glitch, pressSeed + frame);

        // a short marker on the left that grows while hovered
        if (ease > 0.01F) {
            float barHeight = (h - 8) * ease;
            float barTop = y + (h - barHeight) * 0.5F;
            MenuDraw.vbar(graphics, x + 3.0F, barTop, barTop + barHeight, MenuDraw.thickness(MenuDraw.guiScale()) + 1,
                    MenuMath.argb(0xFFFFFF, 0.85F * ease * alpha));
        }

        // click glitch: thin bright scan lines inside the button, very rarely one of them red
        if (glitch > 0.0F) {
            for (int i = 0; i < 2; i++) {
                float ly = y + 2.0F + MenuMath.hash01(pressSeed + frame * 3L + i) * (h - 4.0F);
                float lx0 = x + MenuMath.hash01(pressSeed + frame * 5L + i) * w * 0.5F;
                float lx1 = Math.min(x + w, lx0 + w * (0.2F + 0.5F * MenuMath.hash01(pressSeed + frame * 7L + i)));
                int rgb = i == 0 && MenuMath.hash01(pressSeed) < 0.15F ? 0x9A1414 : 0xD8D8D8;
                MenuDraw.hline(graphics, lx0, lx1, ly, MenuMath.argb(rgb, 0.35F * glitch * alpha));
            }
        }

        // label
        Font font = Minecraft.getInstance().font;
        Component text = wrong ? wrongText : getMessage();
        int textRgb = active ? MenuMath.lerpRgb(ease, 0xC4C4C4, 0xFFFFFF) : 0x5E5E5E;
        int color = MenuMath.argb(textRgb, alpha);
        if (!MenuMath.visible(color)) {
            return;
        }
        float textX = x + 8.0F + 3.0F * ease;
        float textY = y + (h - 8) / 2.0F;
        int textWidth = font.width(text);
        if (textX + textWidth > x + w - 4) {
            AbstractWidget.renderScrollingString(graphics, font, text, x + 6, y, x + w - 6, y + h, color);
            return;
        }
        FormattedCharSequence sequence = sequence(text);
        if (glitch > 0.0F) {
            float jitter = MenuMath.hashSigned(pressSeed + frame) * 2.5F * glitch;
            int ghost = MenuMath.argb(0x9A9A9A, 0.4F * glitch * alpha);
            if (MenuMath.visible(ghost)) {
                graphics.drawString(font, sequence, textX + jitter + 2.0F, textY, ghost, false);
            }
            textX += jitter;
        }
        graphics.drawString(font, sequence, textX, textY, color, false);
    }

    private float flickerFactor(long now) {
        if (now >= flickerUntilMs) {
            return 1.0F;
        }
        float h = MenuMath.hash01(flickerSeed + now / 45L);
        return 1.0F - (1.0F - (0.1F + 0.9F * h)) * menu.flashing();
    }

    private FormattedCharSequence sequence(Component text) {
        if (text != cachedComponent) {
            cachedComponent = text;
            cachedSequence = text.getVisualOrderText();
        }
        return cachedSequence;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
