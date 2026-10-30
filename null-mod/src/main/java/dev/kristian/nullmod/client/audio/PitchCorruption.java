package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.config.NullClientSettings;
import net.minecraft.util.RandomSource;

/**
 * Game sounds played at the wrong pitch: rare and slight in Stage 2, frequent and strong in
 * Stage 5, everything at once during a pitch-chaos burst. Only with the "wrong pitches"
 * setting on.
 */
final class PitchCorruption {
    private static int chaosTicks;
    private static float chaosStrength;

    private PitchCorruption() {
    }

    static void startChaos(int ticks, float strength) {
        if (!enabled()) {
            return;
        }
        chaosTicks = Math.max(chaosTicks, Math.max(20, ticks));
        chaosStrength = Math.max(0.0F, Math.min(1.0F, strength));
    }

    static boolean chaos() {
        return chaosTicks > 0;
    }

    static float chaosStrength() {
        return chaosTicks > 0 ? chaosStrength : 0.0F;
    }

    static void tick() {
        if (chaosTicks > 0) {
            chaosTicks--;
        }
    }

    static void reset() {
        chaosTicks = 0;
    }

    static boolean enabled() {
        return NullClientSettings.get(NullClientSettings.PITCH_DISTORTION) && ClientLimits.audio() > 0.0F
                && ClientCorruptionState.synced() && !ClientCorruptionState.paused();
    }

    /** Pitch multiplier for a game sound that is about to play (1 = untouched). */
    static float factorFor(RandomSource random) {
        if (!enabled()) {
            return 1.0F;
        }
        float audio = ClientLimits.audio();
        int stage = ClientCorruptionState.stage().number();
        float chance;
        float semitones;
        switch (stage) {
            case 1 -> {
                chance = 0.0F;
                semitones = 0.0F;
            }
            case 2 -> {
                chance = 0.012F;
                semitones = 0.9F;
            }
            case 3 -> {
                chance = 0.03F;
                semitones = 2.0F;
            }
            case 4 -> {
                chance = 0.06F;
                semitones = 4.0F;
            }
            default -> {
                chance = 0.13F + 0.07F * ClientCorruptionState.stageProgress();
                semitones = 8.0F;
            }
        }
        if (chaosTicks > 0) {
            chance = 0.85F;
            semitones = 3.0F + 9.0F * chaosStrength;
        }
        if (random.nextFloat() >= chance * audio) {
            return 1.0F;
        }
        float amount = (0.35F + 0.65F * random.nextFloat()) * semitones * (0.5F + 0.5F * audio);
        if (random.nextBoolean()) {
            amount = -amount;
        }
        if (stage >= 5 && random.nextFloat() < 0.1F) {
            amount = random.nextBoolean() ? -12.0F : 7.0F;
        }
        return (float) Math.pow(2.0, amount / 12.0);
    }
}
