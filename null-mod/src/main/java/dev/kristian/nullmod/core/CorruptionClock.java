package dev.kristian.nullmod.core;

import net.minecraft.world.level.Level;

/**
 * Turns the overworld's day time into steps of the corruption clock. The clock counts in-game time
 * the way the world lives it: the day passing, a night slept through, {@code /time add}. Time that
 * is set back never moves the clock back. Because {@code /time set} sets an absolute time (in a world
 * older than one day every {@code /time set day} is a step back), a step back is judged by the time
 * of day, the shorter way round: setting the morning in the middle of the night skips the rest of the
 * night and counts it, as sleeping would; setting an earlier hour of the same day counts nothing, and
 * the clock then waits until the day has caught up again, so it keeps counting its nights at dawn.
 * <p>
 * Pure arithmetic without a world, so it can be tested on its own.
 */
public final class CorruptionClock {
    /** Ticks in one day and night of the overworld. */
    public static final long DAY = Level.TICKS_PER_DAY;
    /** The clock never waits for more than half a day. */
    private static final long MAX_WAIT = DAY / 2;
    private static final long UNSET = Long.MIN_VALUE;

    private long last = UNSET;
    private long wait;

    /**
     * Takes the overworld's day time for this tick and returns how many day-time ticks the clock may
     * count (0 or more). The first value after a {@link #reset()} only sets the reference.
     */
    public long step(long dayTime) {
        long previous = last;
        last = dayTime;
        if (previous == UNSET) {
            return 0L;
        }
        long advance;
        if (dayTime >= previous) {
            advance = dayTime - previous;
        } else {
            long from = Math.floorMod(previous, DAY);
            long to = Math.floorMod(dayTime, DAY);
            long forward = Math.floorMod(to - from, DAY);
            long backward = Math.floorMod(from - to, DAY);
            if (forward <= backward) {
                advance = forward;
            } else {
                advance = 0L;
                wait = Math.min(MAX_WAIT, wait + backward);
            }
        }
        long paid = Math.min(advance, wait);
        wait -= paid;
        return advance - paid;
    }

    /** Forgets the reference and anything it was waiting for (server start or stop). */
    public void reset() {
        last = UNSET;
        wait = 0L;
    }

    /** The clock was just placed at the world's time of day (a stage set by command): nothing to wait for. */
    public void clearWait() {
        wait = 0L;
    }

    /** Day-time ticks the clock still waits for after the time was set back. */
    public long waiting() {
        return wait;
    }
}
