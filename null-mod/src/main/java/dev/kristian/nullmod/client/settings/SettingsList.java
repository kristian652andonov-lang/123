package dev.kristian.nullmod.client.settings;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The scrolling body of {@link NullSettingsScreen}: section headings, short notes, "label ...
 * control" rows, a status line and button rows, on a dark panel with thin white rules.
 */
final class SettingsList extends ContainerObjectSelectionList<SettingsList.Entry> {
    static final int ROW_HEIGHT = 24;
    static final int CONTROL_WIDTH = 124;
    static final int CONTROL_HEIGHT = 20;

    private final Screen screen;
    private final int rowWidth;

    SettingsList(Minecraft minecraft, Screen screen, int width, int height, int y, int rowWidth) {
        super(minecraft, width, height, y, ROW_HEIGHT);
        this.screen = screen;
        this.rowWidth = rowWidth;
    }

    @Override
    public int getRowWidth() {
        return rowWidth;
    }

    /** Just right of the rows, so it never covers the controls at their right end. */
    @Override
    protected int getScrollbarPosition() {
        return Math.min(getRowRight() + 6, getRight() - 7);
    }

    void addHeader(Component title) {
        addEntry(new HeaderEntry(title));
    }

    void addOption(Component label, AbstractWidget control, Tooltip tooltip, BooleanSupplier enabled) {
        addEntry(new OptionEntry(label, control, tooltip, enabled));
    }

    void addText(Supplier<Component> text, IntSupplier color) {
        addEntry(new TextEntry(text, color));
    }

    /** A short explanation, wrapped to the row width over as many rows as it needs (two lines per row). */
    void addNote(Component text, int color) {
        List<FormattedCharSequence> lines = Minecraft.getInstance().font.split(text, Math.max(40, rowWidth - 8));
        for (int i = 0; i < lines.size(); i += 2) {
            addEntry(new NoteEntry(lines.get(i), i + 1 < lines.size() ? lines.get(i + 1) : null, color));
        }
    }

    void addButton(AbstractWidget button, BooleanSupplier enabled) {
        addEntry(new ButtonEntry(button, enabled));
    }

    /** Re-evaluates which controls are usable (master switches turn their dependants off). */
    void refreshActive() {
        for (Entry entry : children()) {
            entry.refresh();
        }
    }

    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        graphics.fill(getX(), getY(), getRight(), getBottom(), NullStyle.LIST);
    }

    @Override
    protected void renderListSeparators(GuiGraphics graphics) {
        graphics.hLine(getX(), getRight() - 1, getY() - 1, NullStyle.LINE);
        graphics.hLine(getX(), getRight() - 1, getBottom(), NullStyle.LINE);
    }

    // ------------------------------------------------------------------------------------------

    abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        void refresh() {
        }
    }

    /** A section title between two thin rules. */
    private static final class HeaderEntry extends Entry {
        private final Component title;

        HeaderEntry(Component title) {
            this.title = title;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                boolean hovering, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int textWidth = font.width(title);
            int centre = left + width / 2;
            int textY = top + (height - font.lineHeight) / 2 + 2;
            graphics.drawString(font, title, centre - textWidth / 2, textY, NullStyle.TEXT, false);
            int ruleY = textY + font.lineHeight / 2 - 1;
            int gap = 7;
            if (centre - textWidth / 2 - gap > left) {
                graphics.hLine(left, centre - textWidth / 2 - gap, ruleY, NullStyle.LINE);
            }
            if (centre + textWidth / 2 + gap < left + width - 1) {
                graphics.hLine(centre + textWidth / 2 + gap, left + width - 1, ruleY, NullStyle.LINE);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** A label on the left, a control on the right; hovering either shows the explanation. */
    private final class OptionEntry extends Entry {
        private final Component label;
        private final AbstractWidget control;
        private final Tooltip tooltip;
        private final BooleanSupplier enabled;

        OptionEntry(Component label, AbstractWidget control, Tooltip tooltip, BooleanSupplier enabled) {
            this.label = label;
            this.control = control;
            this.tooltip = tooltip;
            this.enabled = enabled;
            refresh();
        }

        @Override
        void refresh() {
            control.active = enabled.getAsBoolean();
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                boolean hovering, float partialTick) {
            Font font = Minecraft.getInstance().font;
            control.setX(left + width - control.getWidth());
            control.setY(top + (height - control.getHeight()) / 2);
            int labelRight = control.getX() - 8;
            int color = control.active ? NullStyle.TEXT : NullStyle.TEXT_DISABLED;
            // left-aligned; scrolls when it is too long for the space next to the control
            graphics.drawScrollingString(font, label, left + 4, labelRight, top + (height - font.lineHeight) / 2 + 1, color);
            control.render(graphics, mouseX, mouseY, partialTick);
            if (hovering && tooltip != null && mouseX >= left && mouseX < labelRight && mouseY >= top && mouseY < top + height) {
                screen.setTooltipForNextRenderPass(tooltip.toCharSequence(Minecraft.getInstance()));
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(control);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(control);
        }
    }

    /** One line of status text. */
    private static final class TextEntry extends Entry {
        private final Supplier<Component> text;
        private final IntSupplier color;

        TextEntry(Supplier<Component> text, IntSupplier color) {
            this.text = text;
            this.color = color;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                boolean hovering, float partialTick) {
            Font font = Minecraft.getInstance().font;
            graphics.drawScrollingString(font, text.get(), left + 4, left + width - 4, top + (height - font.lineHeight) / 2 + 1,
                    color.getAsInt());
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** One or two lines of a note. */
    private static final class NoteEntry extends Entry {
        private final FormattedCharSequence first;
        private final FormattedCharSequence second;
        private final int color;

        NoteEntry(FormattedCharSequence first, FormattedCharSequence second, int color) {
            this.first = first;
            this.second = second;
            this.color = color;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                boolean hovering, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int lineHeight = font.lineHeight + 1;
            int lines = second == null ? 1 : 2;
            int y = top + (height - lines * lineHeight) / 2 + 1;
            graphics.drawString(font, first, left + 4, y, color, false);
            if (second != null) {
                graphics.drawString(font, second, left + 4, y + lineHeight, color, false);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** A single button centred in its row. */
    private static final class ButtonEntry extends Entry {
        private final AbstractWidget button;
        private final BooleanSupplier enabled;

        ButtonEntry(AbstractWidget button, BooleanSupplier enabled) {
            this.button = button;
            this.enabled = enabled;
            refresh();
        }

        @Override
        void refresh() {
            button.active = enabled.getAsBoolean();
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                boolean hovering, float partialTick) {
            button.setX(left + (width - button.getWidth()) / 2);
            button.setY(top + (height - button.getHeight()) / 2);
            button.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }
}
