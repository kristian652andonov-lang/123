package dev.kristian.nullmod.chat;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;

/**
 * The CHAT random events. Each one sends its first line at once (more may follow over the next
 * seconds) and reports false when nothing fitted, so the scheduler can try something else.
 * Their weights already scale with the chat frequency setting through {@link EventTag#CHAT}.
 */
public final class ChatEvents {
    private ChatEvents() {
    }

    public static void register() {
        NullEventDefinition.builder("corrupted_chat")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.COMMON)
                .stageWeights(100, 45, 80, 100, 120)
                .cooldown(Ticks.seconds(90))
                .duration(20)
                .tags(EventTag.CHAT)
                .action(ctx -> ChatCorruptionManager.INSTANCE.ambient(ctx.player(), ctx.corruption(), stage(ctx), ctx.random(),
                        ctx.random().nextFloat() < 0.6F))
                .register();

        NullEventDefinition.builder("chat_burst")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .stageWeights(100, 100, 100, 70, 130)
                .cooldown(Ticks.minutes(4))
                .globalCooldown(Ticks.seconds(90))
                .duration(60)
                .tags(EventTag.CHAT)
                .action(ctx -> ChatCorruptionManager.INSTANCE.burst(ctx.player(), ctx.corruption(), stage(ctx), ctx.random(),
                        ctx.random().nextFloat() < 0.35F))
                .register();

        NullEventDefinition.builder("name_whisper")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.CHAT)
                .action(ctx -> ChatCorruptionManager.INSTANCE.nameWhisper(ctx.player(), ctx.corruption(), stage(ctx), ctx.random()))
                .register();

        NullEventDefinition.builder("null_system_message")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .stageWeights(100, 100, 60, 100, 120)
                .cooldown(Ticks.minutes(4))
                .duration(20)
                .tags(EventTag.CHAT)
                .action(ctx -> ChatCorruptionManager.INSTANCE.nullSystem(ctx.player(), ctx.corruption(), stage(ctx), ctx.random()))
                .register();

        NullEventDefinition.builder("fake_join_message")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(15))
                .globalCooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.CHAT)
                .action(ctx -> ChatCorruptionManager.INSTANCE.fakeJoin(ctx.player(), ctx.corruption(), stage(ctx), ctx.random()))
                .register();

        NullEventDefinition.builder("echo_player")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(10))
                .duration(20)
                .tags(EventTag.CHAT)
                .action(ctx -> ChatCorruptionManager.INSTANCE.echo(ctx.player(), ctx.corruption(), stage(ctx), ctx.random()))
                .register();

        // Not in the original table: a vanilla death message for someone who did not die.
        NullEventDefinition.builder("fake_death_message")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(15))
                .duration(20)
                .tags(EventTag.CHAT)
                .action(ctx -> ChatCorruptionManager.INSTANCE.fakeDeath(ctx.player(), ctx.corruption(), stage(ctx), ctx.random()))
                .register();
    }

    /**
     * The stage lines are chosen for: the world's stage, or the event's first stage when an
     * administrator forces it earlier than it could happen on its own.
     */
    public static CorruptionStage stage(EventContext ctx) {
        CorruptionStage current = ctx.corruption().stage();
        CorruptionStage first = ctx.definition().minStage();
        return ctx.forced() && current.number() < first.number() ? first : current;
    }
}
