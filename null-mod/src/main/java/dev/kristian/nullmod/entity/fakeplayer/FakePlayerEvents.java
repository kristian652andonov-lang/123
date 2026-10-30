package dev.kristian.nullmod.entity.fakeplayer;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.entity.UsernameGenerator;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** The fake_player_* random events of the final stage. */
public final class FakePlayerEvents {
    private FakePlayerEvents() {
    }

    public static void register() {
        NullEventDefinition.builder("fake_player_join")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.COMMON)
                .cooldown(Ticks.seconds(40))
                .globalCooldown(Ticks.seconds(20))
                .duration(20)
                .tags(EventTag.FAKE_PLAYER)
                .action(FakePlayerEvents::join)
                .register();

        NullEventDefinition.builder("fake_player_leave")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.seconds(90))
                .globalCooldown(Ticks.seconds(45))
                .duration(20)
                .tags(EventTag.FAKE_PLAYER)
                .action(FakePlayerEvents::leave)
                .register();

        NullEventDefinition.builder("fake_player_rush")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(3))
                .duration(20)
                .tags(EventTag.FAKE_PLAYER)
                .action(ctx -> NullConfig.get(NullConfig.FAKE_PLAYER_BODIES) > 0
                        && FakePlayerManager.INSTANCE.spawnRush(ctx.player(), ctx.random(), ctx.forced()))
                .register();

        NullEventDefinition.builder("fake_player_crowd")
                .stages(CorruptionStage.NULL, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(10))
                .duration(20)
                .tags(EventTag.FAKE_PLAYER)
                .action(ctx -> NullConfig.get(NullConfig.FAKE_PLAYER_BODIES) > 0
                        && FakePlayerManager.INSTANCE.spawnCrowd(ctx.player(), ctx.random(), ctx.forced()) > 0)
                .register();
    }

    private static boolean join(EventContext ctx) {
        if (ctx.corruption().stage() == CorruptionStage.NULL) {
            if (!ctx.forced() && !FakePlayerManager.INSTANCE.belowTarget(ctx.server())) {
                return false;
            }
            return FakePlayerManager.INSTANCE.addSimulated(ctx.server(), ctx.random(), true).isPresent();
        }
        if (ctx.forced()) {
            // Before NULL there is no list to join; an administrator still gets the line.
            ctx.server().getPlayerList().broadcastSystemMessage(Component.translatable("multiplayer.player.joined",
                    UsernameGenerator.random(ctx.random())).withStyle(ChatFormatting.YELLOW), false);
            return true;
        }
        return false;
    }

    private static boolean leave(EventContext ctx) {
        if (ctx.corruption().stage() == CorruptionStage.NULL) {
            if (FakePlayerManager.INSTANCE.removeSimulated(ctx.server(), ctx.random(), true).isPresent()) {
                return true;
            }
        }
        if (ctx.forced()) {
            ctx.server().getPlayerList().broadcastSystemMessage(Component.translatable("multiplayer.player.left",
                    UsernameGenerator.random(ctx.random())).withStyle(ChatFormatting.YELLOW), false);
            return true;
        }
        return false;
    }
}
