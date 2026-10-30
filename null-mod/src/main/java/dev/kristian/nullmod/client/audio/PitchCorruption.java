package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.config.NullClientSettings;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * Some blocks sound wrong when they break, already in Stage 1: which ones is fixed per world
     * (the world seed and the block's break sound), so the same blocks keep sounding wrong.
     * Returns the pitch multiplier for the sound, 1 for untouched.
     */
    static float breakFactorFor(ResourceLocation id, RandomSource random) {
        String path = id.getPath();
        if (!path.startsWith("block.") || !path.endsWith(".break") || !enabled()) {
            return 1.0F;
        }
        int stage = ClientCorruptionState.stage().number();
        // Which break sounds are affected is fixed; how often one of them comes out wrong grows
        // with the stage. Stage 1 must stay subtle: stone or grass may be among them.
        float share;
        float chance;
        switch (stage) {
            case 1 -> {
                share = 0.15F;
                chance = 0.1F;
            }
            case 2 -> {
                share = 0.22F;
                chance = 0.18F;
            }
            case 3 -> {
                share = 0.3F;
                chance = 0.3F;
            }
            case 4 -> {
                share = 0.4F;
                chance = 0.45F;
            }
            default -> {
                share = 0.55F;
                chance = 0.6F;
            }
        }
        long h = ClientCorruptionState.seed() * 0x9E3779B97F4A7C15L + id.hashCode();
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        float audio = ClientLimits.audio();
        if ((h >>> 40) * 0x1.0p-24F >= share || random.nextFloat() >= chance * audio) {
            return 1.0F;
        }
        // Slowed and deep: the block breaks like a recording played back wrong.
        float semitones = (4.0F + 5.0F * random.nextFloat()) * (0.5F + 0.5F * audio);
        return (float) Math.pow(2.0, -semitones / 12.0);
    }
}
