package dev.kristian.nullmod.client.settings;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Colours and small drawing helpers of NULL's settings screens: near-black panels, thin white
 * outlines, light grey text. Every colour keeps a high contrast to the background so the screens
 * stay readable.
 */
final class NullStyle {
    static final int SCREEN = 0xFF060606;
    static final int SCREEN_IN_WORLD = 0xD8000000;
    static final int LIST = 0x8C000000;
    static final int LINE = 0x80FFFFFF;
    static final int LINE_FAINT = 0x33FFFFFF;
    static final int TEXT = 0xFFE6E6E6;
    static final int TEXT_DIM = 0xFF9C9C9C;
    static final int TEXT_DISABLED = 0xFF5C5C5C;
    static final int TEXT_WARNING = 0xFFE05050;
    static final int TEXT_GOOD = 0xFFC8C8C8;

    private static final int BODY = 0xE00B0B0B;
    private static final int BODY_HOT = 0xF01E1E1E;
    private static final int BODY_DISABLED = 0xC0080808;
    private static final int OUTLINE = 0xFF7C7C7C;
    private static final int OUTLINE_HOT = 0xFFFFFFFF;
    private static final int OUTLINE_DISABLED = 0xFF343434;

    private NullStyle() {
    }

    /** Body and one-pixel outline of a widget. */
    static void frame(GuiGraphics graphics, int x, int y, int width, int height, boolean active, boolean hot) {
        int body = !active ? BODY_DISABLED : hot ? BODY_HOT : BODY;
        int outline = !active ? OUTLINE_DISABLED : hot ? OUTLINE_HOT : OUTLINE;
        graphics.fill(x, y, x + width, y + height, body);
        graphics.renderOutline(x, y, width, height, outline);
    }

    /** Text colour of a widget label. */
    static int text(boolean active, boolean hot) {
        return !active ? TEXT_DISABLED : hot ? 0xFFFFFFFF : TEXT;
    }
}
