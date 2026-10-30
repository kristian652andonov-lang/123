package dev.kristian.nullmod.client.desktop;

/**
 * The kinds of window desktop interference can show. Which switch gates each kind:
 * {@code DESKTOP_NOTIFICATIONS} gates {@link #NOTIFICATION} and {@link #DISTORTED};
 * {@code DESKTOP_ERROR_WINDOWS} gates {@link #ERROR}, {@link #REPORT} and {@link #FINAL}.
 * <p>
 * Plain Java: no Minecraft classes, so it can be used from the Swing event thread.
 */
public enum DesktopWindowType {
    /** A: a small corner notification ("ERROR 804 / INFORMATION : NULL."). */
    NOTIFICATION(false),
    /** B: a small error window ("NULL.EXE / CONNECTION LOST."). */
    ERROR(true),
    /** C: a distorted message that appears, flickers and disappears ("YOU ARE NOT ALONE."). */
    DISTORTED(false),
    /** D: a larger NULL report with several status lines. */
    REPORT(true),
    /** E: Stage 5 final interference, in mixed styles. */
    FINAL(true);

    private static final DesktopWindowType[] VALUES = values();

    private final boolean errorWindow;

    DesktopWindowType(boolean errorWindow) {
        this.errorWindow = errorWindow;
    }

    /** True for the kinds gated by the "desktop error windows" switch. */
    public boolean errorWindow() {
        return errorWindow;
    }

    /** The kind with this ordinal, or {@code null} when out of range. */
    public static DesktopWindowType byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : null;
    }
}
