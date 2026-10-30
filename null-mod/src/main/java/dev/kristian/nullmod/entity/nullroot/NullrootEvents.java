package dev.kristian.nullmod.entity.nullroot;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventPlace;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * The NULLROOT random events (Stages 3-5): something grows near the player, a whole colony
 * grows, a tendril slides into view at the edge of the screen, the ground pulses.
 */
public final class NullrootEvents {
    /** Client effect: a black tendril slides in from a screen edge for a moment. */
    public static final String EFFECT_GLIMPSE = "nullroot.glimpse";
    /** Client effect: an organic thump from below and a short darkening. {@code pos}: where it comes from. */
    public static final String EFFECT_PULSE = "nullroot.pulse";

    private NullrootEvents() {
    }

    public static void register() {
        NullEventDefinition.builder("nullroot_sprout")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.COMMON)
                .stageWeights(100, 100, 100, 110, 90)
                .cooldown(Ticks.minutes(3))
                .duration(20)
                .tags(EventTag.NULLROOT)
                .place(EventPlace.OUTSIDE_NULL)
                .action(ctx -> NullrootManager.INSTANCE.sprout(ctx.player(), ctx.corruption(), ctx.random(), ctx.forced()).isPresent())
                .register();

        NullEventDefinition.builder("nullroot_colony")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .stageWeights(100, 100, 70, 100, 120)
                .cooldown(Ticks.minutes(15))
                .globalCooldown(Ticks.minutes(6))
                .duration(20)
                .tags(EventTag.NULLROOT)
                .place(EventPlace.OUTSIDE_NULL)
                .action(ctx -> NullrootManager.INSTANCE.colony(ctx.player(), ctx.corruption(), ctx.random(), ctx.forced()).isPresent())
                .register();

        NullEventDefinition.builder("nullroot_glimpse")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(2.5))
                .duration(36)
                .tags(EventTag.VISUAL, EventTag.NULLROOT)
                .clientEffect(EFFECT_GLIMPSE)
                .register();

        NullEventDefinition.builder("ground_pulse")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(2))
                .duration(30)
                .tags(EventTag.AUDIO, EventTag.VISUAL, EventTag.NULLROOT)
                .action(NullrootEvents::groundPulse)
                .register();
    }

    /**
     * The pulse comes from the nearest organism if one grows close by (which visibly pulses for
     * everyone around it too), else from the ground under the player.
     */
    private static boolean groundPulse(EventContext ctx) {
        ServerPlayer player = ctx.player();
        List<NullrootEntity> near = NullrootManager.INSTANCE.near(player, 32.0);
        BlockPos source;
        if (near.isEmpty()) {
            source = player.blockPosition().below(2);
        } else {
            NullrootEntity organism = near.get(0);
            source = organism.blockPosition();
            for (int i = 0; i < Math.min(3, near.size()); i++) {
                near.get(i).pulseNow();
            }
        }
        ctx.sendEffect(EFFECT_PULSE, Optional.of(source), near.isEmpty() ? 0 : 1, "");
        return true;
    }
}
