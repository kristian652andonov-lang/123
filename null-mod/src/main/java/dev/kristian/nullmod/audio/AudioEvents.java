package dev.kristian.nullmod.audio;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The random events of the audio corruption. Each one only starts a client effect for the
 * player it was rolled for; the client decides where the sound comes from, so it can sit
 * anywhere around them (or right behind them) and honours their audio settings.
 */
public final class AudioEvents {
    private AudioEvents() {
    }

    public static void register() {
        audio("static_whisper", CorruptionStage.START, CorruptionStage.NULL, NullEventDefinition.COMMON, 40, 50, "audio.static")
                .stageWeights(60, 100, 100, 100, 100).register();
        audio("distant_footsteps", CorruptionStage.START, CorruptionStage.ABOVE_MIDDLE, NullEventDefinition.UNCOMMON, 180, 160, "audio.footsteps_distant")
                .register();
        audio("underground_noise", CorruptionStage.START, CorruptionStage.ABOVE_MIDDLE, NullEventDefinition.UNCOMMON, 180, 80, "audio.underground")
                .register();
        audio("digital_interference", CorruptionStage.START, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 120, 40, "audio.interference")
                .register();
        audio("low_hum", CorruptionStage.START, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 240, 300, "audio.hum")
                .register();
        audio("reversed_ambience", CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 180, 80, "audio.reversed")
                .register();
        audio("fake_explosion", CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL, NullEventDefinition.RARE, 600, 60, "audio.explosion")
                .register();
        audio("sudden_silence", CorruptionStage.MIDDLE, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 480, 90, "audio.silence")
                .register();
        audio("heartbeat", CorruptionStage.MIDDLE, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 300, 200, "audio.heartbeat")
                .register();
        audio("corrupted_music", CorruptionStage.MIDDLE, CorruptionStage.NULL, NullEventDefinition.RARE, 900, 100, "audio.corrupted_music")
                .register();
        audio("whisper_behind", CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 240, 60, "audio.whisper_behind")
                .register();
        audio("footsteps_behind", CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 300, 80, "audio.footsteps_behind")
                .register();
        audio("distant_voices", CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 240, 100, "audio.voices")
                .register();
        audio("breath_behind", CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL, NullEventDefinition.RARE, 480, 60, "audio.breath")
                .register();
        NullEventDefinition.builder("door_knock")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(10))
                .duration(80)
                .tags(EventTag.AUDIO)
                .action(AudioEvents::knock)
                .register();
        audio("pitch_chaos", CorruptionStage.NULL, CorruptionStage.NULL, NullEventDefinition.UNCOMMON, 360, 200, "audio.pitch_chaos")
                .register();
    }

    private static NullEventDefinition.Builder audio(String id, CorruptionStage min, CorruptionStage max, int weight,
                                                     int cooldownSeconds, int duration, String effect) {
        return NullEventDefinition.builder(id)
                .stages(min, max)
                .weight(weight)
                .cooldown(Ticks.seconds(cooldownSeconds))
                .duration(duration)
                .tags(EventTag.AUDIO)
                .clientEffect(effect);
    }

    /** Knocking on the nearest door (or somewhere close by when there is none). */
    private static boolean knock(EventContext ctx) {
        BlockPos door = findDoor(ctx.level(), ctx.player());
        ctx.sendEffect("audio.knock", Optional.ofNullable(door), 0, "");
        return true;
    }

    /** The closest door or trapdoor within a few blocks, without loading chunks. */
    @Nullable
    private static BlockPos findDoor(ServerLevel level, ServerPlayer player) {
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -3; dy <= 3; dy++) {
                    pos.set(x, origin.getY() + dy, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) {
                        double distance = dx * dx + dy * dy + dz * dz;
                        // Never right next to the player: the knock comes from the other side.
                        if (distance >= 4.0 && distance < bestDistance) {
                            bestDistance = distance;
                            best = pos.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }
}
