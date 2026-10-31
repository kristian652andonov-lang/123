package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventPlace;
import dev.kristian.nullmod.event.NullEventDefinition;

/**
 * The {@code stalker_*} random events (Stages 1-4; the breaking window from Stage 2). Each one starts
 * an encounter for the player it happens to, or reports that it could not happen there (wrong
 * situation, the player already has one, a quiet spell). They come on top of the figure's own
 * background schedule ({@link StalkerManager}); the stalkers frequency setting scales both.
 */
public final class StalkerEvents {
    public static final String TREE = "stalker_tree";
    public static final String WINDOW = "stalker_window";
    public static final String DOORS = "stalker_doors";
    public static final String WINDOW_BREAK = "stalker_window_break";
    public static final String MINER = "stalker_miner";

    private StalkerEvents() {
    }

    public static void register() {
        NullEventDefinition.builder(TREE)
                .stages(CorruptionStage.START, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.UNCOMMON).stageWeights(60, 100, 120, 130, 0)
                .cooldown(Ticks.minutes(5)).duration(20)
                .place(EventPlace.OUTSIDE_NULL)
                .action(ctx -> start(ctx, StalkerKind.TREE_WATCHER))
                .register();
        NullEventDefinition.builder(WINDOW)
                .stages(CorruptionStage.START, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.UNCOMMON).stageWeights(40, 90, 120, 140, 0)
                .cooldown(Ticks.minutes(8)).duration(20)
                .place(EventPlace.OUTSIDE_NULL)
                .action(ctx -> start(ctx, StalkerKind.WINDOW_WATCHER))
                .register();
        NullEventDefinition.builder(DOORS)
                .stages(CorruptionStage.START, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.UNCOMMON).stageWeights(40, 90, 120, 140, 0)
                .cooldown(Ticks.minutes(10)).globalCooldown(Ticks.minutes(3)).duration(20)
                .place(EventPlace.OUTSIDE_NULL)
                .action(ctx -> start(ctx, StalkerKind.DOOR_OPENER))
                .register();
        NullEventDefinition.builder(WINDOW_BREAK)
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.RARE).stageWeights(100, 100, 130, 160, 0)
                .cooldown(Ticks.minutes(12)).globalCooldown(Ticks.minutes(2)).duration(20)
                .place(EventPlace.OUTSIDE_NULL)
                .action(ctx -> start(ctx, StalkerKind.WINDOW_BREAKER))
                .register();
        NullEventDefinition.builder(MINER)
                .stages(CorruptionStage.START, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.UNCOMMON).stageWeights(60, 100, 120, 140, 0)
                .cooldown(Ticks.minutes(6)).duration(20)
                .place(EventPlace.OUTSIDE_NULL)
                .action(ctx -> start(ctx, StalkerKind.MINER))
                .register();
    }

    private static boolean start(EventContext ctx, StalkerKind kind) {
        if (!ctx.forced()) {
            // The frequency setting thins these out like the background schedule (above 1 only the schedule speeds up).
            double frequency = StalkerSettings.frequency();
            if (frequency <= 0.0 || (frequency < 1.0 && ctx.random().nextDouble() >= frequency)) {
                return false;
            }
        }
        return StalkerManager.INSTANCE.begin(ctx.player(), kind, ctx.forced(), ctx.showcase(), ctx.corruption(), ctx.random());
    }
}
