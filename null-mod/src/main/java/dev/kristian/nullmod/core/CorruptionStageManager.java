package dev.kristian.nullmod.core;

import dev.kristian.nullmod.config.NullConfig;
import net.minecraft.util.Mth;

/**
 * Stage thresholds and progress maths.
 * <p>
 * The corruption clock counts in-game time ({@link CorruptionClock}): one tick of the overworld's
 * day time, times the speed settings, is one clock tick, so one day and night is
 * {@link #TICKS_PER_NIGHT} and "FRACTURE after 3 nights" means at three times that. The clock keeps
 * the world's time of day: it starts at the hour NULL began in the world, and a stage set by command
 * keeps the hour too, so at normal speed a night is counted at dawn, when it really ends (also when
 * everyone sleeps through it). The nights come from the world's config
 * ({@code progression.stageNNights}). HARVEST is not on the clock: it begins when a player has
 * survived {@link #harvestSurvivalTicks()} in the NULL dimension.
 */
public final class CorruptionStageManager {
    /** Clock ticks in one in-game day and night. */
    public static final double TICKS_PER_NIGHT = CorruptionClock.DAY;
    /** Stages set closer together than this are pushed apart, so every stage lasts at least a minute. */
    public static final double MIN_GAP_NIGHTS = 0.05;
    /** A clock placed inside a stage (a migrated world) leaves at least this much of it, or half a short stage. */
    private static final double MIN_LEFT_OF_STAGE = TICKS_PER_NIGHT / 4.0;
    /** Builds before the night clock counted minutes: their default stage starts... */
    private static final double[] LEGACY_STAGE_MINUTES = {0.0, 30.0, 90.0, 180.0, 300.0};
    /** ...and NULL was at full strength this many minutes after it began. */
    private static final double LEGACY_FULL_STRENGTH_MINUTES = 60.0;

    private CorruptionStageManager() {
    }

    /**
     * In-game nights after which a stage begins (0 for STATIC), from the config, each at least
     * {@link #MIN_GAP_NIGHTS} after the one before. HARVEST shares NULL's: the clock never reaches it.
     */
    public static double nights(CorruptionStage stage) {
        double n2 = NullConfig.get(NullConfig.STAGE_2_NIGHTS);
        double n3 = Math.max(n2 + MIN_GAP_NIGHTS, NullConfig.get(NullConfig.STAGE_3_NIGHTS));
        double n4 = Math.max(n3 + MIN_GAP_NIGHTS, NullConfig.get(NullConfig.STAGE_4_NIGHTS));
        double n5 = Math.max(n4 + MIN_GAP_NIGHTS, NullConfig.get(NullConfig.STAGE_5_NIGHTS));
        return switch (stage) {
            case START -> 0.0;
            case UNDER_MIDDLE -> n2;
            case MIDDLE -> n3;
            case ABOVE_MIDDLE -> n4;
            // HARVEST is never reached by the clock (see stageFor); it shares NULL's start.
            case NULL, HARVEST -> n5;
        };
    }

    /** Clock ticks at which a stage begins. Always strictly increasing from STATIC to NULL. */
    public static double threshold(CorruptionStage stage) {
        return nights(stage) * TICKS_PER_NIGHT;
    }

    /** The stage the clock has reached (at most NULL: HARVEST is not reached by the clock). */
    public static CorruptionStage stageFor(double corruptionTicks) {
        CorruptionStage reached = CorruptionStage.START;
        for (CorruptionStage stage : CorruptionStage.values()) {
            if (stage.atLeast(CorruptionStage.HARVEST)) {
                break;
            }
            if (corruptionTicks >= threshold(stage)) {
                reached = stage;
            }
        }
        return reached;
    }

    /** Clock ticks from the start of Stage 5 until NULL is at full strength. */
    public static double fullStrengthTicks() {
        return Ticks.PER_MINUTE * NullConfig.get(NullConfig.NULL_FULL_STRENGTH_MINUTES);
    }

    /** Ticks a player has to survive in the NULL dimension for HARVEST to begin (at speed 1). */
    public static double harvestSurvivalTicks() {
        return Ticks.PER_MINUTE * NullConfig.get(NullConfig.HARVEST_SURVIVAL_MINUTES);
    }

    /**
     * 0..1 progress inside a stage. Stage 5 has no end on the clock, so its progress (and
     * HARVEST's) runs over the configured time until NULL is at full strength instead.
     */
    public static float progressWithin(CorruptionStage stage, double corruptionTicks) {
        double start = threshold(stage);
        double end = stage.atLeast(CorruptionStage.NULL)
                ? start + fullStrengthTicks()
                : threshold(stage.next());
        if (end <= start) {
            return 1.0F;
        }
        return (float) Math.max(0.0, Math.min(1.0, (corruptionTicks - start) / (end - start)));
    }

    /** 0..1 progress from the start of Stage 1 to the start of Stage 5. */
    public static float overallProgress(double corruptionTicks) {
        double end = threshold(CorruptionStage.NULL);
        return (float) Math.max(0.0, Math.min(1.0, corruptionTicks / end));
    }

    /** Corruption ticks left until the next stage, or -1 when no clock-driven stage follows (NULL, HARVEST). */
    public static double ticksUntilNext(CorruptionStage stage, double corruptionTicks) {
        if (stage.atLeast(CorruptionStage.NULL)) {
            return -1.0;
        }
        return Math.max(0.0, threshold(stage.next()) - corruptionTicks);
    }

    /**
     * A new pace with stage {@code number} (2-5) beginning after {@code nights}. Stages after it that
     * would no longer begin later than the stage before them move later, keeping their distance to it
     * (one night where there was none). {@code pace} is indexed by stage number (2-5 are used) and is
     * not changed. The caller makes sure {@code nights} comes after the stage before.
     */
    public static double[] pushLater(double[] pace, int number, double nights) {
        double[] result = pace.clone();
        result[number] = nights;
        for (int n = number + 1; n <= CorruptionStage.NULL.number(); n++) {
            if (result[n] >= result[n - 1] + MIN_GAP_NIGHTS) {
                continue;
            }
            double gap = pace[n] - pace[n - 1];
            result[n] = result[n - 1] + (gap >= MIN_GAP_NIGHTS ? gap : 1.0);
        }
        return result;
    }

    /** Whole nights the clock has counted. */
    public static long nightsPassed(double corruptionTicks) {
        return (long) Math.floor(Math.max(0.0, corruptionTicks) / TICKS_PER_NIGHT);
    }

    /**
     * Where the clock is put when a stage is set by command (or reset) at this overworld day time:
     * the stage's start moved on to the world's hour (the stage began at the last dawn), so the
     * stages after it still begin at dawn. NULL and HARVEST start exactly at NULL's threshold: their
     * progress runs on minutes, not nights.
     */
    public static double stageStart(CorruptionStage stage, long dayTime) {
        double start = threshold(stage);
        if (stage.atLeast(CorruptionStage.NULL)) {
            return start;
        }
        return placeInStage(start, threshold(stage.next()), start, dayTime);
    }

    /**
     * The clock of a world saved by a build that counted minutes. The saved stage is kept; the clock
     * goes as far into it as the old clock was into the old stage (measured with the old default
     * times), at the world's hour, and always leaves some of the stage to play.
     */
    public static double migrateLegacyClock(CorruptionStage stage, double legacyTicks, long dayTime) {
        double minutes = Math.max(0.0, legacyTicks) / Ticks.PER_MINUTE;
        if (stage.atLeast(CorruptionStage.NULL)) {
            double into = Mth.clamp((minutes - LEGACY_STAGE_MINUTES[4]) / LEGACY_FULL_STRENGTH_MINUTES, 0.0, 1.0);
            return threshold(CorruptionStage.NULL) + into * fullStrengthTicks();
        }
        int index = stage.tableIndex();
        double from = LEGACY_STAGE_MINUTES[index];
        double to = LEGACY_STAGE_MINUTES[index + 1];
        double fraction = Mth.clamp((minutes - from) / (to - from), 0.0, 1.0);
        double start = threshold(stage);
        double end = threshold(stage.next());
        return placeInStage(start, end, start + fraction * (end - start), dayTime);
    }

    /**
     * The latest clock value at or before {@code target} that has the world's hour, kept inside the
     * stage {@code [start, end)} with some of the stage left.
     */
    static double placeInStage(double start, double end, double target, long dayTime) {
        double hour = Math.floorMod(dayTime, CorruptionClock.DAY);
        double placed = target - mod(target - hour, TICKS_PER_NIGHT);
        if (placed < start) {
            placed += TICKS_PER_NIGHT;
        }
        double latest = Math.max(start, end - Math.min(MIN_LEFT_OF_STAGE, (end - start) / 2.0));
        return Mth.clamp(placed, start, latest);
    }

    private static double mod(double value, double modulus) {
        return value - modulus * Math.floor(value / modulus);
    }
}
