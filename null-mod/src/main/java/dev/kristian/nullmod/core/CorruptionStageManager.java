package dev.kristian.nullmod.core;

import dev.kristian.nullmod.config.NullConfig;

/** Stage thresholds and progress maths. Thresholds are read from the world's config. */
public final class CorruptionStageManager {
    private static final double MIN_GAP_TICKS = Ticks.seconds(30);

    private CorruptionStageManager() {
    }

    /** Corruption ticks at which a stage begins. Always strictly increasing with the stage. */
    public static double threshold(CorruptionStage stage) {
        double t2 = Ticks.PER_MINUTE * NullConfig.get(NullConfig.STAGE_2_MINUTES);
        double t3 = Math.max(t2 + MIN_GAP_TICKS, Ticks.PER_MINUTE * NullConfig.get(NullConfig.STAGE_3_MINUTES));
        double t4 = Math.max(t3 + MIN_GAP_TICKS, Ticks.PER_MINUTE * NullConfig.get(NullConfig.STAGE_4_MINUTES));
        double t5 = Math.max(t4 + MIN_GAP_TICKS, Ticks.PER_MINUTE * NullConfig.get(NullConfig.STAGE_5_MINUTES));
        return switch (stage) {
            case START -> 0.0;
            case UNDER_MIDDLE -> t2;
            case MIDDLE -> t3;
            case ABOVE_MIDDLE -> t4;
            case NULL -> t5;
        };
    }

    /** The stage the clock has reached. */
    public static CorruptionStage stageFor(double corruptionTicks) {
        CorruptionStage reached = CorruptionStage.START;
        for (CorruptionStage stage : CorruptionStage.values()) {
            if (corruptionTicks >= threshold(stage)) {
                reached = stage;
            }
        }
        return reached;
    }

    /**
     * 0..1 progress inside a stage. Stage 5 has no end, so its progress runs over the
     * configured saturation time instead.
     */
    public static float progressWithin(CorruptionStage stage, double corruptionTicks) {
        double start = threshold(stage);
        double end = stage.isFinal()
                ? start + Ticks.PER_MINUTE * NullConfig.get(NullConfig.NULL_SATURATION_MINUTES)
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

    /** Corruption ticks left until the next stage, or -1 in the final stage. */
    public static double ticksUntilNext(CorruptionStage stage, double corruptionTicks) {
        if (stage.isFinal()) {
            return -1.0;
        }
        return Math.max(0.0, threshold(stage.next()) - corruptionTicks);
    }
}
