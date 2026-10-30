package dev.kristian.nullmod.client.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A stepped slider in NULL's style. Mouse and keyboard work like vanilla sliders (left/right
 * arrows move one step). The value is saved through the listener as soon as the player lets go
 * of the slider, presses an arrow key, or moves on, so dragging does not rewrite the config file
 * on every pixel.
 */
final class NullSliderButton extends AbstractSliderButton {
    @FunctionalInterface
    interface Formatter {
        Component format(double value);
    }

    @FunctionalInterface
    interface Listener {
        void onCommit(double value);
    }

    private final Component label;
    private final double min;
    private final double max;
    private final double step;
    private final int steps;
    private final Formatter formatter;
    private final Listener listener;
    private int index;
    private int committedIndex;

    NullSliderButton(int x, int y, int width, int height, Component label, double min, double max, double step, double initial,
            Formatter formatter, Listener listener) {
        super(x, y, width, height, Component.empty(), 0.0);
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.steps = Math.max(1, (int) Math.round((max - min) / step));
        this.formatter = formatter;
        this.listener = listener;
        this.index = indexOf(initial);
        this.committedIndex = index;
        this.value = index / (double) steps;
        updateMessage();
    }

    private int indexOf(double raw) {
        int i = (int) Math.round((raw - min) / step);
        return Math.max(0, Math.min(steps, i));
    }

    /** The value currently shown (which may not be saved yet while dragging). */
    double current() {
        return Math.max(min, Math.min(max, min + index * step));
    }

    private void moveTo(int newIndex) {
        int clamped = Math.max(0, Math.min(steps, newIndex));
        value = clamped / (double) steps;
        if (clamped != index) {
            index = clamped;
            updateMessage();
        }
    }

    private void moveToMouse(double mouseX) {
        double fraction = (mouseX - (getX() + 4)) / (double) Math.max(1, width - 8);
        moveTo((int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * steps));
    }

    /** Saves the shown value if it differs from the last saved one. */
    void commit() {
        if (index != committedIndex) {
            committedIndex = index;
            listener.onCommit(current());
        }
    }

    boolean hasPendingValue() {
        return index != committedIndex;
    }

    @Override
    protected void updateMessage() {
        if (formatter != null) {
            setMessage(formatter.format(current()));
        }
    }

    @Override
    protected void applyValue() {
        // vanilla paths that set the fraction directly: snap it to a step
        moveTo((int) Math.round(Math.max(0.0, Math.min(1.0, value)) * steps));
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        moveToMouse(mouseX);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        moveToMouse(mouseX);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        commit();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_RIGHT) {
            if (!active) {
                return false;
            }
            moveTo(index + (keyCode == InputConstants.KEY_LEFT ? -1 : 1));
            commit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            commit();
        }
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return Component.translatable("gui.narrate.slider", CommonComponents.optionNameValue(label, getMessage()));
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hot = active && isHoveredOrFocused();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        NullStyle.frame(graphics, x, y, w, h, active, hot);

        // filled part of the track
        int inner = w - 4;
        int handleWidth = 3;
        int handleX = x + 2 + (int) Math.round(value * (inner - handleWidth));
        if (handleX > x + 2) {
            graphics.fill(x + 2, y + 2, handleX, y + h - 2, active ? 0x38FFFFFF : 0x14FFFFFF);
        }
        // the handle
        int handleColor = !active ? 0xFF4A4A4A : hot ? 0xFFFFFFFF : 0xFFCFCFCF;
        graphics.fill(handleX, y + 1, handleX + handleWidth, y + h - 1, handleColor);

        Font font = Minecraft.getInstance().font;
        AbstractWidget.renderScrollingString(graphics, font, getMessage(), x + 4, y, x + w - 4, y + h, NullStyle.text(active, hot));
    }
}
