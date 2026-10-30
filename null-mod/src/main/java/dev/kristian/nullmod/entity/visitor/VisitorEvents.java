package dev.kristian.nullmod.entity.visitor;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.entity.PlayerLikeSpawns;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;

/** The visitor_* random events (Stages 4-5). Each spawns one Visitor, or reports that it could not. */
public final class VisitorEvents {
    private VisitorEvents() {
    }

    public static void register() {
        NullEventDefinition.builder("visitor_distant")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.COMMON)
                .cooldown(Ticks.minutes(4))
                .duration(20)
                .tags(EventTag.VISITOR)
                .action(ctx -> spawn(ctx, ctx.random().nextFloat() < 0.55F ? VisitorPersonality.OBSERVER : VisitorPersonality.DISAPPEARING,
                        VisitorManager.Placement.EDGE_OF_VIEW))
                .register();

        NullEventDefinition.builder("visitor_behind")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.VISITOR)
                .action(ctx -> spawn(ctx, VisitorPersonality.STALKER, VisitorManager.Placement.BEHIND))
                .register();

        NullEventDefinition.builder("visitor_cave")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.VISITOR)
                .action(ctx -> (ctx.forced() || PlayerLikeSpawns.isUnderground(ctx.player()))
                        && spawn(ctx, VisitorPersonality.UNDERGROUND, VisitorManager.Placement.CAVE))
                .register();

        NullEventDefinition.builder("visitor_forest")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.VISITOR)
                .action(ctx -> spawn(ctx, ctx.random().nextFloat() < 0.6F ? VisitorPersonality.OBSERVER : VisitorPersonality.DISAPPEARING,
                        VisitorManager.Placement.FOREST))
                .register();

        NullEventDefinition.builder("visitor_structure")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(10))
                .duration(20)
                .tags(EventTag.VISITOR)
                .action(ctx -> spawn(ctx, PlayerLikeSpawns.isUnderground(ctx.player()) ? VisitorPersonality.UNDERGROUND : VisitorPersonality.OBSERVER,
                        VisitorManager.Placement.STRUCTURE))
                .register();

        NullEventDefinition.builder("visitor_unusual")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(10))
                .duration(20)
                .tags(EventTag.VISITOR)
                .action(ctx -> spawn(ctx, ctx.random().nextBoolean() ? VisitorPersonality.OBSERVER : VisitorPersonality.DISAPPEARING,
                        VisitorManager.Placement.UNUSUAL))
                .register();

        NullEventDefinition.builder("visitor_follower")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(6))
                .duration(20)
                .tags(EventTag.VISITOR)
                .action(ctx -> spawn(ctx, VisitorPersonality.FOLLOWER, VisitorManager.Placement.FOLLOW))
                .register();
    }

    private static boolean spawn(EventContext ctx, VisitorPersonality personality, VisitorManager.Placement placement) {
        return VisitorManager.INSTANCE.spawn(ctx.player(), personality, placement, ctx.forced(), ctx.corruption(), ctx.random())
                .isPresent();
    }
}
