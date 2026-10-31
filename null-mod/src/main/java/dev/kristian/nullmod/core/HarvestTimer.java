package dev.kristian.nullmod.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * How long each player has survived in the NULL dimension without a break (Stage 5). When one of
 * them has survived {@link CorruptionStageManager#harvestSurvivalTicks()}, Stage 6 (HARVEST) begins.
 * Pure bookkeeping without a world: the NULL dimension's manager decides each tick who is surviving.
 * Times live in memory only, so a player who logs out (or a server restart) starts again.
 */
public final class HarvestTimer {
    /** Where a player stands this tick. */
    public enum State {
        /** Alive in the NULL dimension and the clock is counting: the time runs. */
        SURVIVING,
        /** In the NULL dimension, but the time stands still (a transition, the arrival, NULL paused). */
        HELD,
        /** Dead, or no longer in the NULL dimension: the time is lost. */
        GONE
    }

    private final Map<UUID, Double> times = new HashMap<>();

    /**
     * One tick for one player.
     *
     * @param step     how much time passes for a surviving player (ticks, already scaled by the speed)
     * @param required the time that has to be survived
     * @return true when this player has now survived long enough
     */
    public boolean tick(UUID player, State state, double step, double required) {
        return switch (state) {
            case GONE -> {
                times.remove(player);
                yield false;
            }
            case HELD -> false;
            case SURVIVING -> times.merge(player, Math.max(0.0, step), Double::sum) >= required;
        };
    }

    /** Ticks this player has survived so far (0 when they have no time). */
    public double time(UUID player) {
        return times.getOrDefault(player, 0.0);
    }

    /** Keeps only the players for which the test is true; everyone else starts again. */
    public void retain(Predicate<UUID> keep) {
        times.keySet().removeIf(keep.negate());
    }

    public void forget(UUID player) {
        times.remove(player);
    }

    public void clear() {
        times.clear();
    }

    public int size() {
        return times.size();
    }
}
