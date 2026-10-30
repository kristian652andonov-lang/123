package dev.kristian.nullmod.dimension;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventPlace;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.registry.NullSounds;

/** The random events that only happen inside the NULL dimension. */
public final class DimensionEvents {
    /** Client effect: the sky and every light of the dimension go dark for one to three seconds. */
    public static final String EFFECT_BLACKOUT = "dimension.blackout";
    /** Client effect: a wave of red, green and blue light runs through the sky and the structure. */
    public static final String EFFECT_PULSE = "dimension.pulse";

    private DimensionEvents() {
    }

    static void register() {
        NullEventDefinition.builder("geometry_blackout")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.seconds(100))
                .duration(60)
                .tags(EventTag.DIMENSION, EventTag.VISUAL)
                .place(EventPlace.INSIDE_NULL)
                .clientEffect(EFFECT_BLACKOUT)
                .sound(NullSounds.DIMENSION_BLACKOUT, 0.9F, 1.0F)
                .register();

        // The structure loses a piece somewhere in sight; it comes back minutes later.
        NullEventDefinition.builder("section_vanish")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.COMMON)
                .cooldown(Ticks.seconds(45))
                .globalCooldown(Ticks.seconds(12))
                .duration(40)
                .tags(EventTag.DIMENSION)
                .place(EventPlace.INSIDE_NULL)
                .action(ctx -> NullDimensionManager.INSTANCE.vanishNear(ctx.player(), ctx.random(), ctx.forced()))
                .register();

        NullEventDefinition.builder("null_pulse")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.seconds(70))
                .duration(50)
                .tags(EventTag.DIMENSION, EventTag.VISUAL)
                .place(EventPlace.INSIDE_NULL)
                .clientEffect(EFFECT_PULSE)
                .sound(NullSounds.DIMENSION_SHIFT, 0.65F, 0.6F)
                .register();

        // Far away, the geometry quietly rearranges itself, and later rearranges back.
        NullEventDefinition.builder("corridor_shift")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(3))
                .globalCooldown(Ticks.seconds(50))
                .duration(60)
                .tags(EventTag.DIMENSION)
                .place(EventPlace.INSIDE_NULL)
                .action(ctx -> NullDimensionManager.INSTANCE.shiftNear(ctx.player(), ctx.random(), ctx.forced()))
                .register();
    }
}
