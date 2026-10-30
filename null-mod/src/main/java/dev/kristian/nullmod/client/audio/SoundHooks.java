package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.NullMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Sees every sound the game is about to play (NeoForge's {@link PlaySoundEvent}): holds sounds
 * back during silences, hands music to {@link MusicCorruption}, echoes the player's footsteps
 * and plays some game sounds at the wrong pitch (or bent by a NULLROOT capture).
 * <p>
 * Only one-shot sounds that nobody keeps a reference to are ever wrapped: never ticking or looping
 * sounds (minecarts, bees, elytra, other mods' loops), records, UI sounds, voice or music, because
 * whoever started those may later try to stop or query that exact instance. Nothing happens
 * outside a world.
 */
final class SoundHooks {
    private static boolean failed;

    private SoundHooks() {
    }

    static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        try {
            if (AudioCorruptionManager.suppressed(sound)) {
                event.setSound(null);
                return;
            }
            if (sound instanceof NullSoundInstance || sound instanceof CorruptedSoundInstance) {
                return;
            }
            RandomSource random = AudioCorruptionManager.random();
            SoundSource source = sound.getSource();
            if (source == SoundSource.MUSIC) {
                MusicCorruption.onVanillaMusic(event, sound, mc, random);
                return;
            }
            if (source == SoundSource.MASTER || source == SoundSource.RECORDS || source == SoundSource.VOICE
                    || sound instanceof TickableSoundInstance) {
                return;
            }
            if (NullMod.MODID.equals(sound.getLocation().getNamespace())) {
                // NULL's own sounds are already what they should be.
                return;
            }
            PhantomFootsteps.onSound(mc, sound, random);
            if (sound.isLooping()) {
                // A loop is stopped by whoever started it, through that exact instance: never replace it.
                return;
            }
            float pitch = PitchCorruption.factorFor(random);
            float volume = 1.0F;
            float capture = AudioCorruptionManager.captureDistortion();
            if (capture > 0.01F && AudioCorruptionManager.channelEnabled(AudioCorruptionManager.Channel.CREATURE)) {
                pitch *= 1.0F - 0.25F * capture * (0.6F + 0.4F * random.nextFloat());
                volume *= 1.0F - 0.4F * capture;
            }
            if (pitch != 1.0F || volume != 1.0F) {
                event.setSound(new CorruptedSoundInstance(sound, pitch, volume));
            }
        } catch (RuntimeException e) {
            if (!failed) {
                failed = true;
                NullMod.LOGGER.error("NULL audio corruption failed on {}", sound.getLocation(), e);
            }
        }
    }
}
