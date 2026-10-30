package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * The music goes wrong. From Stage 3 a track the game starts may come out bent (slowed, wavering,
 * sagging, dropping out); from Stage 4 it may be swapped for one of NULL's distorted pieces, and
 * in Stage 5 that happens most of the time. The game's music manager simply sees its track end
 * early and waits for the next one as usual.
 */
final class MusicCorruption {
    @Nullable
    private static NullSoundInstance current;
    private static boolean bent;
    private static int dropout;

    private MusicCorruption() {
    }

    static boolean playing() {
        NullSoundInstance music = current;
        return music != null && !music.isStopped() && Minecraft.getInstance().getSoundManager().isActive(music);
    }

    /** A track the game wants to start (PlaySoundEvent, MUSIC source, not ours). */
    static void onVanillaMusic(PlaySoundEvent event, SoundInstance sound, Minecraft mc, RandomSource random) {
        if (!enabled()) {
            return;
        }
        if (playing()) {
            if (mc.getSituationalMusic().replaceCurrentMusic()) {
                // Boss fights, the End, credits: the game's music wins.
                stop(40);
            } else {
                event.setSound(null);
            }
            return;
        }
        int stage = ClientCorruptionState.stage().number();
        float roll = random.nextFloat();
        boolean bend;
        boolean replace;
        switch (stage) {
            case 3 -> {
                bend = roll < 0.35F;
                replace = false;
            }
            case 4 -> {
                bend = roll < 0.3F;
                replace = !bend && roll < 0.55F;
            }
            case 5 -> {
                bend = roll < 0.2F;
                replace = !bend && roll < 0.85F;
            }
            default -> {
                bend = false;
                replace = false;
            }
        }
        if (bend) {
            event.setSound(null);
            startBent(sound.getLocation(), random);
        } else if (replace) {
            event.setSound(null);
            startReplacement(stage, mc.level != null && NullDimensions.isNull(mc.level), random);
        }
    }

    /** The audio.corrupted_music effect: whatever is playing stops and a distorted piece begins. */
    static void playCorrupted(RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || mc.level == null) {
            return;
        }
        stop(0);
        mc.getSoundManager().stop(null, SoundSource.MUSIC);
        startReplacement(ClientCorruptionState.stage().number(), NullDimensions.isNull(mc.level), random);
    }

    static void tick(Minecraft mc, RandomSource random) {
        NullSoundInstance music = current;
        if (music == null) {
            return;
        }
        if (!playing()) {
            current = null;
            return;
        }
        if (!enabled()) {
            stop(30);
            return;
        }
        Music situational = mc.getSituationalMusic();
        if (situational.replaceCurrentMusic() && !situational.getEvent().value().getLocation().equals(music.getLocation())) {
            stop(60);
            return;
        }
        int stage = ClientCorruptionState.stage().number();
        float audio = ClientLimits.audio();
        if (dropout > 0) {
            dropout--;
            music.modulate(1.0F, 0.0F);
            return;
        }
        music.modulate(1.0F, 1.0F);
        if (bent && stage >= 4 && random.nextFloat() < 0.004F * audio) {
            music.sag(0.08F + 0.14F * random.nextFloat());
        }
        if (stage >= 4 && random.nextFloat() < 0.0025F * audio) {
            dropout = 3 + random.nextInt(9);
        }
    }

    static void stop(int fade) {
        NullSoundInstance music = current;
        current = null;
        dropout = 0;
        if (music != null) {
            if (fade <= 0) {
                music.stopNow();
                Minecraft.getInstance().getSoundManager().stop(music);
            } else {
                music.fadeOut(fade);
            }
        }
    }

    private static boolean enabled() {
        return ClientCorruptionState.synced() && !ClientCorruptionState.paused()
                && AudioCorruptionManager.channelEnabled(AudioCorruptionManager.Channel.MUSIC)
                && !AudioCorruptionManager.isSilenced();
    }

    private static void startBent(ResourceLocation track, RandomSource random) {
        float audio = ClientLimits.audio();
        SoundEvent event = SoundEvent.createVariableRangeEvent(track);
        float pitch = 0.97F - 0.05F * audio - random.nextFloat() * 0.02F;
        current = AudioCorruptionManager.start(new NullSoundInstance(event, AudioCorruptionManager.Channel.MUSIC,
                NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0, 1.0F, pitch, false, false, random)
                .wobble(0.015F + 0.025F * audio, 0.025F + random.nextFloat() * 0.02F)
                .fades(30, 60));
        bent = true;
    }

    private static void startReplacement(int stage, boolean inNull, RandomSource random) {
        SoundEvent track;
        if (inNull || (stage >= 5 && random.nextBoolean())) {
            track = NullSounds.MUSIC_NULL.get();
        } else {
            track = random.nextBoolean() ? NullSounds.MUSIC_DISTORTED_1.get() : NullSounds.MUSIC_DISTORTED_2.get();
        }
        float audio = ClientLimits.audio();
        current = AudioCorruptionManager.start(new NullSoundInstance(track, AudioCorruptionManager.Channel.MUSIC,
                NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0, 0.9F, 1.0F, false, false, random)
                .wobble(0.006F * audio, 0.03F)
                .fades(60, 80));
        bent = false;
    }
}
