package dev.kristian.combatlog.text;

import java.util.Locale;

/** How a remaining-time value is rendered to the player. */
public enum TimeStyle {

    /** {@code 12} - always rounded up so it never shows a bare "0". */
    SECONDS {
        @Override
        public String format(long millis) {
            return Long.toString(Math.max(0L, (millis + 999L) / 1000L));
        }
    },

    /** {@code 12.4} */
    TENTHS {
        @Override
        public String format(long millis) {
            return String.format(Locale.US, "%.1f", Math.max(0L, millis) / 1000.0D);
        }
    },

    /** {@code 00:12} */
    MINUTES_SECONDS {
        @Override
        public String format(long millis) {
            long total = Math.max(0L, (millis + 999L) / 1000L);
            return String.format(Locale.US, "%02d:%02d", total / 60L, total % 60L);
        }
    },

    /** {@code 1m 12s} above a minute, {@code 12.4s} below it. */
    SMART {
        @Override
        public String format(long millis) {
            long total = Math.max(0L, (millis + 999L) / 1000L);
            if (total >= 60L) {
                return total / 60L + "m " + total % 60L + "s";
            }
            return TENTHS.format(millis) + "s";
        }
    };

    public abstract String format(long millis);

    public static TimeStyle parse(String name, TimeStyle fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
