package dev.kristian.nullmod.client.menu;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A small, dim text link in a corner of the menu (Mods, NULL, the copyright line). */
final class NullMenuLink extends AbstractButton {
    private final CorruptedMainMenu menu;
    private final Runnable action;
    private final int baseRgb;
    private final FormattedCharSequence sequence;
    private float hover;
    private boolean wasHot;
    private long lastFrameMs;

    NullMenuLink(int x, int y, Component text, Font font, int baseRgb, CorruptedMainMenu menu, Runnable action) {
        super(x, y, font.width(text) + 2, font.lineHeight + 1, text);
        this.menu = menu;
        this.action = action;
        this.baseRgb = baseRgb;
        this.sequence = text.getVisualOrderText();
    }

    @Override
    public void onPress() {
        menu.requestAction(action, 0L);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        if (!menu.audio().click()) {
            super.playDownSound(soundManager);
        }
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
        hover = MenuMath.approach(hover, hot ? 1.0F : 0.0F, dt * 7.0F);
        float ease = MenuMath.smooth(hover);
        int color = MenuMath.argb(MenuMath.lerpRgb(ease, baseRgb, 0xE8E8E8), ui * (0.85F + 0.15F * ease));
        if (!MenuMath.visible(color)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, sequence, getX() + 1.0F, getY() + 1.0F, color, false);
        if (ease > 0.02F) {
            MenuDraw.hline(graphics, getX() + 1.0F, getX() + 1.0F + (getWidth() - 2) * ease, getY() + getHeight(),
                    MenuMath.argb(0xE8E8E8, 0.7F * ease * ui));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
