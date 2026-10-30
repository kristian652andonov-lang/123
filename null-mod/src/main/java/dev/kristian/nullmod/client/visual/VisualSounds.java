package dev.kristian.nullmod.client.visual;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;

/**
 * The few sounds the visual effects make, all routed through the audio corruption system so the
 * player's audio settings apply. Kept in one place so the visual code has a single dependency on
 * the audio API.
 */
final class VisualSounds {
    private VisualSounds() {
    }

    private static boolean audible() {
        return ClientLimits.audio() > 0.0F;
    }

    /** Non-positional, right at the player's ears (blackouts, freezes, tearing). */
    private static void ui(SoundEvent sound, float volume, float pitch) {
        if (!audible() || AudioCorruptionManager.isSilenced()) {
            return;
        }
        AudioCorruptionManager.playUi(sound, volume * ClientLimits.audio(), pitch);
    }

    private static void at(SoundEvent sound, @Nullable Vec3 pos, float volume, float pitch) {
        if (!audible()) {
            return;
        }
        AudioCorruptionManager.play(sound, AudioCorruptionManager.Channel.EVENT, pos, volume, pitch);
    }

    /** Silence is part of some illusions (a hung game, a dead connection). */
    private static void silence(int ticks) {
        if (audible() && NullClientSettings.get(NullClientSettings.SUDDEN_SILENCE)) {
            AudioCorruptionManager.silence(ticks);
        }
    }

    static void freeze(int ticks) {
        silence(ticks);
        if (audible()) {
            // The buzz of a stuck audio buffer is the only thing left.
            AudioCorruptionManager.playUi(NullSounds.FREEZE.get(), 0.55F * ClientLimits.audio(), 1.0F);
        }
    }

    static void blackout(float intensity, boolean full, int ticks) {
        if (full) {
            silence(ticks);
            if (audible()) {
                AudioCorruptionManager.playUi(NullSounds.BLACKOUT.get(), (0.5F + 0.4F * intensity) * ClientLimits.audio(), 0.85F);
            }
        } else {
            ui(NullSounds.BLACKOUT.get(), 0.25F + 0.4F * intensity, 1.0F);
        }
    }

    static void fakeDisconnect(int ticks) {
        silence(ticks);
    }

    static void tear(float intensity) {
        ui(NullSounds.SCREEN_TEAR.get(), 0.2F + 0.45F * intensity, 0.9F + 0.2F * intensity);
    }

    static void glitch(float intensity) {
        ui(NullSounds.GLITCH.get(), 0.25F + 0.45F * intensity, 1.0F);
    }

    static void glitchText(float intensity) {
        ui(NullSounds.STATIC_BURST.get(), 0.3F + 0.4F * intensity, 0.8F);
    }

    static void skyFlicker(float intensity) {
        ui(NullSounds.SKY_FLICKER.get(), 0.2F + 0.35F * intensity, 1.0F);
    }

    static void chunkFlicker(Vec3 pos, float intensity) {
        at(NullSounds.GLITCH.get(), pos, 0.4F + 0.5F * intensity, 0.6F);
    }
}
