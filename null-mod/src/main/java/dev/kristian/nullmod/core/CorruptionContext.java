package dev.kristian.nullmod.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * A read-only snapshot of the corruption state for one server tick, handed to every
 * {@link CorruptionSystem}.
 *
 * @param stageProgress   0..1 progress through the current stage
 * @param overallProgress 0..1 progress from Stage 1 to Stage 5
 * @param intensity       global strength in 0..1: grows through the stages and with the event intensity setting
 */
public record CorruptionContext(
        MinecraftServer server,
        CorruptionData data,
        CorruptionStage stage,
        float stageProgress,
        float overallProgress,
        float intensity,
        double corruptionTicks,
        boolean paused,
        long seed) {

    public boolean atLeast(CorruptionStage other) {
        return stage.atLeast(other);
    }

    /** How corrupted the area around a position is, 0..1. Regions corrupt at different speeds. */
    public float localCorruption(BlockPos pos) {
        return RegionCorruption.localCorruption(this, pos.getX(), pos.getZ());
    }

    /** Smooth 0..1 value from Stage 1 to the end of Stage 5's saturation period. */
    public float continuousStage() {
        return Math.min(1.0F, ((stage.number() - 1) + stageProgress) / 5.0F);
    }

    /** In-game nights the corruption clock has counted since NULL began (fractional). */
    public double nights() {
        return corruptionTicks / CorruptionStageManager.TICKS_PER_NIGHT;
    }
}
