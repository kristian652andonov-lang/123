package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.core.CorruptionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Somebody walking in step behind the player: now and then, for a few steps, the player's own
 * footstep sounds are repeated 0.3-0.8 seconds later from a couple of blocks behind them.
 * Stops the moment the player stops (it only answers their own steps).
 */
final class PhantomFootsteps {
    private static int streak;
    private static int cooldown;

    private PhantomFootsteps() {
    }

    static void tick() {
        if (cooldown > 0) {
            cooldown--;
        }
    }

    static void reset() {
        streak = 0;
        cooldown = 0;
    }

    /** Looks at a game sound that is about to play; echoes it if it is one of the player's steps. */
    static void onSound(Minecraft mc, SoundInstance sound, RandomSource random) {
        if (sound.getSource() != SoundSource.PLAYERS) {
            return;
        }
        ResourceLocation id = sound.getLocation();
        if (!id.getPath().endsWith(".step")) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        double dx = sound.getX() - player.getX();
        double dy = sound.getY() - player.getY();
        double dz = sound.getZ() - player.getZ();
        if (dx * dx + dy * dy + dz * dz > 2.25) {
            return;
        }
        if (!ClientCorruptionState.atLeast(CorruptionStage.UNDER_MIDDLE) || ClientCorruptionState.paused()
                || !AudioCorruptionManager.channelEnabled(AudioCorruptionManager.Channel.FOOTSTEPS)) {
            streak = 0;
            return;
        }
        if (streak <= 0) {
            if (cooldown > 0) {
                return;
            }
            int stage = ClientCorruptionState.stage().number();
            float chance = switch (stage) {
                case 2 -> 0.006F;
                case 3 -> 0.01F;
                case 4 -> 0.016F;
                default -> 0.024F;
            };
            if (random.nextFloat() >= chance * AudioCorruptionManager.channelGain(AudioCorruptionManager.Channel.FOOTSTEPS)) {
                return;
            }
            streak = 4 + random.nextInt(8);
            cooldown = 20 * (stage >= 4 ? 25 : 45);
        }
        streak--;
        SoundEvent step = SoundEvent.createVariableRangeEvent(id);
        float pitch = 0.86F + random.nextFloat() * 0.09F;
        AudioCorruptionManager.schedule(6 + random.nextInt(11), () -> echo(step, pitch));
    }

    private static void echo(SoundEvent step, float pitch) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        RandomSource random = AudioCorruptionManager.random();
        Vec3 look = AudioPositions.lookFlat();
        double back = 2.2 + random.nextDouble() * 1.2;
        double side = (random.nextDouble() - 0.5) * 1.0;
        Vec3 pos = new Vec3(player.getX() - look.x * back - look.z * side, player.getY() + 0.1,
                player.getZ() - look.z * back + look.x * side);
        AudioCorruptionManager.play(step, AudioCorruptionManager.Channel.FOOTSTEPS, pos, 0.2F, pitch);
    }
}
