package dev.kristian.nullmod.core;

/** Tick arithmetic helpers. One second is 20 ticks. */
public final class Ticks {
    public static final int PER_SECOND = 20;
    public static final int PER_MINUTE = 20 * 60;

    private Ticks() {
    }

    public static int seconds(double seconds) {
        return (int) Math.round(seconds * PER_SECOND);
    }

    public static int minutes(double minutes) {
        return (int) Math.round(minutes * PER_MINUTE);
    }

    /** Formats a tick count as h:mm:ss, or m:ss when under an hour. */
    public static String format(double ticks) {
        long total = Math.max(0L, (long) (ticks / PER_SECOND));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }
}
