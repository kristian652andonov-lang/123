package dev.kristian.nullmod.client.desktop;

import java.util.List;

/**
 * Everything a desktop window needs, decided on the render thread and handed to the Swing event
 * thread as immutable data. Plain Java on purpose: the Swing side never touches Minecraft.
 *
 * @param type        what kind of window this is (only used for bookkeeping and logging)
 * @param layout      how the content is arranged
 * @param header      small first line (error code, file name, report title); may be empty
 * @param lines       the message lines
 * @param footer      the always-visible label that says the window comes from the NULL mod
 * @param textScale   size of the message text relative to the default (1 = 14 pt)
 * @param placement   where on the primary screen it goes
 * @param lifetimeMs  how long it stays before it closes by itself (fades included)
 * @param typewriter  message characters appear one by one
 * @param flicker     0..1 strength of brightness flicker (already scaled by the flashing setting)
 * @param distortion  0..1 how often and how strongly characters glitch for a few frames
 * @param slowFades   fade in and out slowly (flashing turned off)
 * @param redAccent   the frame and brand use the dark red warning colour
 * @param test        shown from the settings screen's test button; adds a small TEST tag
 * @param seed        seed for the window's own glitch timing
 */
public record DesktopWindowSpec(
        DesktopWindowType type,
        Layout layout,
        String header,
        List<Line> lines,
        String footer,
        float textScale,
        Placement placement,
        int lifetimeMs,
        boolean typewriter,
        float flicker,
        float distortion,
        boolean slowFades,
        boolean redAccent,
        boolean test,
        long seed) {

    public DesktopWindowSpec {
        header = header == null ? "" : header;
        lines = List.copyOf(lines);
        footer = footer == null ? "" : footer;
        textScale = Math.max(0.6F, Math.min(3.5F, textScale));
        lifetimeMs = Math.max(1500, Math.min(30_000, lifetimeMs));
        flicker = clamp01(flicker);
        distortion = clamp01(distortion);
    }

    private static float clamp01(float value) {
        return value <= 0.0F ? 0.0F : Math.min(1.0F, value);
    }

    /** How a window arranges its text. */
    public enum Layout {
        /** Header, separator, one or two body lines. */
        CORNER,
        /** Title bar with a file-like name, then the message. */
        ERROR_BOX,
        /** One or two lines of large text. */
        BIG_TEXT,
        /** Report title, a dashed rule and "KEY: VALUE" lines. */
        REPORT
    }

    /** Where a window appears on the primary screen. */
    public enum Placement {
        /** One of the four corners of the usable screen area. */
        CORNER,
        /** Anywhere on the usable screen area. */
        RANDOM,
        /** Somewhere around the middle of the screen. */
        CENTRE
    }

    /** Colour of a line. */
    public enum Tone {
        NORMAL,
        DIM,
        WARNING
    }

    /**
     * One line of text. In {@link Layout#REPORT} windows {@code label} is the dim key column
     * ("WORLD STATUS:") and {@code text} the value; elsewhere the label is usually empty.
     */
    public record Line(String label, String text, Tone tone) {
        public Line {
            label = label == null ? "" : label;
            text = text == null ? "" : text;
            tone = tone == null ? Tone.NORMAL : tone;
        }

        public static Line of(String text) {
            return new Line("", text, Tone.NORMAL);
        }

        public static Line of(String text, Tone tone) {
            return new Line("", text, tone);
        }
    }
}
