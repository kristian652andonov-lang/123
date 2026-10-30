package dev.kristian.nullmod.client.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * An ON/OFF switch in NULL's style. It behaves like any vanilla button (mouse, Enter/Space,
 * narration "Name: ON"); the listener may refuse a change, which the desktop interference switch
 * does until the player has agreed to the explanation.
 */
final class NullToggleButton extends AbstractButton {
    /** Called before the value flips; return false to keep the old value. */
    @FunctionalInterface
    interface Listener {
        boolean onToggle(NullToggleButton button, boolean newValue);
    }

    private final Component label;
    private final Listener listener;
    private boolean value;

    NullToggleButton(int x, int y, int width, int height, Component label, boolean value, Listener listener) {
        super(x, y, width, height, valueText(value));
        this.label = label;
        this.listener = listener;
        this.value = value;
    }

    private static Component valueText(boolean value) {
        return value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
    }

    boolean value() {
        return value;
    }

    void setValue(boolean value) {
        this.value = value;
        setMessage(valueText(value));
    }

    @Override
    public void onPress() {
        boolean next = !value;
        if (listener.onToggle(this, next)) {
            setValue(next);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hot = active && isHoveredOrFocused();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        NullStyle.frame(graphics, x, y, w, h, active, hot);
        int color = NullStyle.text(active, hot);

        // a small box on the left, filled while the switch is on
        int box = 7;
        int boxX = x + 6;
        int boxY = y + (h - box) / 2;
        graphics.renderOutline(boxX, boxY, box, box, color);
        if (value) {
            graphics.fill(boxX + 2, boxY + 2, boxX + box - 2, boxY + box - 2, color);
        }

        Font font = Minecraft.getInstance().font;
        int textColor = value ? color : (active ? NullStyle.TEXT_DIM : NullStyle.TEXT_DISABLED);
        AbstractWidget.renderScrollingString(graphics, font, getMessage(), boxX + box + 4, y, x + w - 4, y + h, textColor);
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return AbstractWidget.wrapDefaultNarrationMessage(CommonComponents.optionNameValue(label, valueText(value)));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
