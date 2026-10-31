package dev.kristian.nullmod.client.audio;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Real-time corruption of a piece of music that is already playing. Minecraft cannot process a
 * playing stream, so this is everything a sound instance can change from tick to tick: its pitch
 * (wow, flutter, the tape dragging and catching up again) and its volume (dropouts, stutters, the
 * signal sagging away for a moment). The generated broken versions of NULL's own pieces carry what
 * cannot be done live: reversed fragments, crushed and filtered passages, repeats, notes that hang.
 * <p>
 * How much happens follows the level: 0 is clean, about 0.1 the rare, barely noticeable wrongness of
 * Stage 3, 0.45 Stage 4, 0.75 Stage 5 and 1 the field of Stage 6. The pitch never drops by more than
 * about four semitones (a tape slowing down, never a sweep). Pure arithmetic without any game state,
 * so the main menu uses it as well. Call {@link #tick()} once per game tick, then read
 * {@link #pitch()} and {@link #volume()} as multipliers.
 */
public final class MusicWarp {
    private final RandomSource random;
    private final float wowRate;
    private float level;
    private float wowPhase;
    private float flutter;
    private int sagAge = -1;
    private int sagAttack;
    private int sagHold;
    private int sagRelease;
    private float sagDepth;
    private int dropout;
    private boolean dropped;
    private int stutter;
    private int stutterPeriod = 1;
    private float dip;
    private float dipTarget;
    private int dipTicks;
    private float pitch = 1.0F;
    private float volume = 1.0F;

    public MusicWarp(RandomSource random, float level) {
        this.random = random;
        this.level = Mth.clamp(level, 0.0F, 1.0F);
        this.wowPhase = random.nextFloat() * Mth.TWO_PI;
        // 0.35-0.6 wavers per second.
        this.wowRate = (0.35F + random.nextFloat() * 0.25F) * Mth.TWO_PI / 20.0F;
    }

    /**
     * The level of the main menu's music, from the highest stage this computer has seen: clean
     * through Stage 3, then increasingly warped (a little less than in the world itself).
     */
    public static float forMenu(int highestStage) {
        return switch (Math.max(1, Math.min(6, highestStage))) {
            case 1, 2, 3 -> 0.0F;
            case 4 -> 0.3F;
            case 5 -> 0.5F;
            default -> 0.65F;
        };
    }

    public float level() {
        return level;
    }

    /** Changes how corrupted the music is from now on (the stage changed). */
    public void setLevel(float newLevel) {
        level = Mth.clamp(newLevel, 0.0F, 1.0F);
    }

    /** Pitch multiplier for this tick. */
    public float pitch() {
        return pitch;
    }

    /** Volume multiplier for this tick (0 during a dropout). */
    public float volume() {
        return volume;
    }

    /** True on the tick a dropout begins (a moment for a note to be left hanging in the air). */
    public boolean droppedOut() {
        return dropped;
    }

    /**
     * Makes the tape drag now: the pitch sinks by {@code depth} (0.22 is about four semitones) over
     * {@code attack} ticks, holds, and comes back over {@code release} ticks.
     */
    public void drag(float depth, int attack, int hold, int release) {
        sagDepth = Mth.clamp(depth, 0.0F, 0.25F);
        sagAttack = Math.max(1, attack);
        sagHold = Math.max(0, hold);
        sagRelease = Math.max(1, release);
        sagAge = 0;
    }

    public void tick() {
        float l = level;
        // Wow: the speed wanders slowly, deeper the higher the level.
        wowPhase += wowRate;
        if (wowPhase > Mth.TWO_PI) {
            wowPhase -= Mth.TWO_PI;
        }
        float wow = (0.002F + 0.011F * l) * (Mth.sin(wowPhase) * 0.75F + Mth.sin(wowPhase * 2.3F + 1.1F) * 0.25F);
        // Flutter: a fast, uneven tremble, like a worn capstan.
        flutter += ((random.nextFloat() - 0.5F) * 0.004F * l - flutter) * 0.5F;
        // Tape stretch: now and then it drags, holds, and catches up again.
        if (sagAge < 0 && l > 0.05F && random.nextFloat() < l * l * 0.0022F) {
            drag(0.03F + l * (0.06F + random.nextFloat() * 0.14F), 8 + random.nextInt(14), 6 + random.nextInt(30),
                    14 + random.nextInt(26));
        }
        float sag = 0.0F;
        if (sagAge >= 0) {
            sagAge++;
            if (sagAge <= sagAttack) {
                sag = sagDepth * smooth(sagAge / (float) sagAttack);
            } else if (sagAge <= sagAttack + sagHold) {
                sag = sagDepth;
            } else if (sagAge <= sagAttack + sagHold + sagRelease) {
                sag = sagDepth * (1.0F - smooth((sagAge - sagAttack - sagHold) / (float) sagRelease));
            } else {
                sagAge = -1;
            }
        }
        pitch = Mth.clamp((1.0F + wow + flutter) * (1.0F - sag), 0.5F, 2.0F);

        // Dropouts: the sound is simply gone for a moment.
        dropped = false;
        if (dropout > 0) {
            dropout--;
        } else if (l > 0.3F && random.nextFloat() < (l - 0.2F) * (l - 0.2F) * 0.0035F) {
            dropout = 2 + random.nextInt(8 + (int) (8 * l));
            dropped = true;
        }
        // Stutters: it cuts in and out, tick by tick, like a buffer that keeps failing.
        if (stutter > 0) {
            stutter--;
        } else if (l > 0.55F && random.nextFloat() < (l - 0.5F) * 0.0025F) {
            stutter = 6 + random.nextInt(12);
            stutterPeriod = 1 + random.nextInt(2);
        }
        // Dips: the signal sags away for a second or two and comes back.
        if (dipTicks > 0) {
            if (--dipTicks == 0) {
                dipTarget = 0.0F;
            }
        } else if (l > 0.2F && dipTarget == 0.0F && random.nextFloat() < l * 0.0012F) {
            dipTarget = 0.3F + random.nextFloat() * 0.4F * l;
            dipTicks = 20 + random.nextInt(50);
        }
        dip += (dipTarget - dip) * 0.12F;
        float v = 1.0F - dip;
        if (dropout > 0) {
            v = 0.0F;
        } else if (stutter > 0 && (stutter / stutterPeriod) % 2 == 0) {
            v *= 0.05F;
        }
        volume = v;
    }

    private static float smooth(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
