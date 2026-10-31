package dev.kristian.nullmod.field;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.Ticks;

/**
 * Every tunable of the field in one place. All durations are in server ticks. The ones an
 * administrator would change are in the server config (section {@code field}) and read through the
 * methods below at use; the constants of the same name are their defaults.
 */
public final class FieldTuning {
    /** Default of {@code field.daySeconds}: the day, while the sun slowly cracks (3.5 minutes). */
    public static final int DAY_TICKS = Ticks.seconds(210);
    /** The sun shattering and the light dying. */
    public static final int SHATTER_TICKS = Ticks.seconds(5);
    /** Default of {@code field.countdownSeconds}: the countdown "TIME UNTIL "IT" COMES". */
    public static final int NIGHT_TICKS = Ticks.seconds(75);
    /** Default of {@code field.huntSeconds}: how long IT hunts ({@code ItEntity.summon(..., huntTicks)}). */
    public static final int HUNT_TICKS = Ticks.seconds(100);
    /** Dawn, while the light returns and the sun re-forms. */
    public static final int DAWN_TICKS = Ticks.seconds(10);
    /** If IT is still hunting this long after its hunt should have ended, dawn comes anyway. */
    public static final int HUNT_WATCHDOG_TICKS = Ticks.seconds(60);

    /** Default of {@code field.itDistance}: IT appears this far away, towards the sun (closer if the server simulates less). */
    public static final int IT_DISTANCE = 120;
    /** IT never appears closer than this. */
    public static final int IT_MIN_DISTANCE = 48;

    /** Someone who comes back into an empty field at night gets at least this much countdown. */
    public static final int REJOIN_MIN_COUNTDOWN = Ticks.seconds(20);
    /** The cycle is re-sent to everyone in the field this often (clients interpolate in between). */
    public static final int RESYNC_TICKS = Ticks.seconds(10);
    /** A player who joins while the field is where they belong is moved this long after joining. */
    public static final int JOIN_DELAY_TICKS = 60;
    /** Moves that failed (chunk not ready, another mod refused) are retried this often... */
    public static final int RETRY_DELAY_TICKS = 20;
    /** ...this many times. */
    public static final int MAX_ATTEMPTS = 6;
    /** How long a move waits for the NULL dimension's own transition of the same player to finish. */
    public static final int MAX_WAIT_FOR_OTHER_TRANSITION = Ticks.seconds(30);

    private FieldTuning() {
    }

    /** The day, from the server config ({@code field.daySeconds}). */
    public static int dayTicks() {
        return Ticks.seconds(NullConfig.get(NullConfig.FIELD_DAY_SECONDS));
    }

    /** The countdown, from the server config ({@code field.countdownSeconds}). */
    public static int nightTicks() {
        return Ticks.seconds(NullConfig.get(NullConfig.FIELD_COUNTDOWN_SECONDS));
    }

    /** How long IT hunts, from the server config ({@code field.huntSeconds}). */
    public static int huntTicks() {
        return Ticks.seconds(NullConfig.get(NullConfig.FIELD_HUNT_SECONDS));
    }

    /** How far away IT appears, from the server config ({@code field.itDistance}); never below {@link #IT_MIN_DISTANCE}. */
    public static int itDistance() {
        return Math.max(IT_MIN_DISTANCE, NullConfig.get(NullConfig.FIELD_IT_DISTANCE));
    }
}
