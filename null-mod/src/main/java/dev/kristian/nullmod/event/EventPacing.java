package dev.kristian.nullmod.event;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;

/**
 * How much faster things happen when corruption time is sped up. NULL's random events and its
 * ambient schedulers (background world corruption, NULLROOT, Visitors, the chat, the simulated
 * players) share one rule: when an administrator makes corruption time pass faster
 * ({@code /null speed} times {@code progression.speed}), their waits shrink by the same factor,
 * but never below the normal pace (a slower clock keeps the normal pace), never more than
 * {@link #MAX_SPEED_FACTOR} times (or the lower {@code events.maxSpeedBoost} setting), and never
 * below each scheduler's own minimum gap. The frequency settings multiply on top of this.
 */
public final class EventPacing {
    /** The most the speed settings can ever multiply how often things happen. */
    public static final double MAX_SPEED_FACTOR = 6.0;

    private EventPacing() {
    }

    /** The factor for the world's current speed settings, between 1 and {@link #MAX_SPEED_FACTOR}. */
    public static double speedFactor(CorruptionContext ctx) {
        return speedFactor(ctx.data().speed());
    }

    /**
     * The factor for an administrator speed ({@code /null speed}) combined with the configured
     * {@code progression.speed}: 1 while progression is off or the clock runs at normal speed or
     * slower, then growing with the speed up to the configured limit (at most 6).
     */
    public static double speedFactor(double adminSpeed) {
        if (!NullConfig.get(NullConfig.PROGRESSION_ENABLED)) {
            return 1.0;
        }
        double speed = NullConfig.get(NullConfig.PROGRESSION_SPEED) * adminSpeed;
        return factorFor(speed, NullConfig.get(NullConfig.EVENT_MAX_SPEED_BOOST));
    }

    /** The pure rule: {@code speed} clamped to 1..min(limit, 6); NaN and negative speeds count as 1. */
    public static double factorFor(double speed, double limit) {
        double cap = Math.max(1.0, Math.min(MAX_SPEED_FACTOR, limit));
        if (!(speed > 1.0)) {
            return 1.0;
        }
        return Math.min(speed, cap);
    }

    /**
     * A wait in ticks divided by the speed factor and a frequency multiplier, never below
     * {@code minTicks}. A frequency of 0 (or less) means "never": the result is then
     * {@link Long#MAX_VALUE} / 4 (far enough away, and still safe to add to a game time).
     */
    public static long scale(double ticks, double frequency, double speedFactor, long minTicks) {
        if (!(frequency > 0.0)) {
            return Long.MAX_VALUE / 4L;
        }
        double divisor = frequency * Math.max(1.0, speedFactor);
        double scaled = ticks / divisor;
        if (!(scaled < Long.MAX_VALUE / 4.0)) {
            return Long.MAX_VALUE / 4L;
        }
        return Math.max(minTicks, (long) scaled);
    }
}
