package dev.kristian.nullmod.client.visual;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.Arrays;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Every screen-space distortion NULL shows goes through here. Effects are kept in a fixed pool
 * of slots (kind, strength, start, duration, attack/release envelope, seed); once per frame the
 * envelopes are evaluated and folded into one 0..1 level per kind, which the camera hooks, the
 * post-processing shader, the sky pass and the overlay layer read.
 * <p>
 * Accessibility is applied on the way in: shake-type effects scale with
 * {@link ClientLimits#shake()}, flashes, blackouts and flicker with {@link ClientLimits#flashing()}
 * (at 0 they turn into slow fades, never strobes), freezes, strobing and complete blackouts need
 * {@link ClientLimits#intenseAllowed()}, and no more than {@link ClientLimits#maxVisualEffects()}
 * effects run at once (the weakest one is dropped). The transition fade and the capture overlay
 * are separate channels that ignore that cap. Everything here runs on the render thread.
 */
public final class VisualDistortionManager {
    public enum Kind {
        SHAKE, CAMERA_JERK, RGB_SPLIT, COLOR_SHIFT, NOISE, SCANLINES, WARP, DESATURATE, DARKEN,
        BLACKOUT, FLASH, FREEZE, FOV_PULSE, SKY_TINT
    }

    // Channels 0..13 are the Kind ordinals; the rest are internal.
    static final int SHAKE = 0;
    static final int CAMERA_JERK = 1;
    static final int RGB_SPLIT = 2;
    static final int COLOR_SHIFT = 3;
    static final int NOISE = 4;
    static final int SCANLINES = 5;
    static final int WARP = 6;
    static final int DESATURATE = 7;
    static final int DARKEN = 8;
    static final int BLACKOUT = 9;
    static final int FLASH = 10;
    static final int FREEZE = 11;
    static final int FOV_PULSE = 12;
    static final int SKY_TINT = 13;
    /** Horizontal block-displacement bands in the shader (terrain shift). */
    static final int CH_BANDS = 14;
    /** Tearing bars and glitch rectangles drawn by the overlay. */
    static final int CH_TEAR = 15;
    /** The sky blinking dark or inverted. */
    static final int CH_SKY_FLICKER = 16;
    static final int CHANNELS = 17;

    static final int STYLE_SMOOTH = 0;
    /** Hard on/off segments; only used while strobing is allowed. */
    static final int STYLE_FLICKER = 1;
    /** Sinusoidal throbbing. */
    static final int STYLE_PULSE = 2;
    /** A sudden snap that eases back (camera jerk). */
    static final int STYLE_SNAP = 3;

    private static final int MAX_SLOTS = 32;
    private static final float EPSILON = 0.004F;

    private static final long SEED_YAW = 0x6E756C6C5941574CL;
    private static final long SEED_PITCH = 0x6E756C6C50495443L;
    private static final long SEED_ROLL = 0x6E756C6C524F4C4CL;
    private static final long SEED_FOV = 0x6E756C6C464F5650L;
    private static final long SEED_STILL = 0x6E756C6C5354494CL;
    private static final long SEED_WANDER = 0x6E756C6C57414E44L;

    /** Sky tint colours: sickly green, blood red, deep violet, cold cyan, dead white, amber. */
    private static final float[][] TINTS = {
            {0.42F, 0.86F, 0.30F},
            {0.92F, 0.14F, 0.10F},
            {0.52F, 0.20F, 0.88F},
            {0.22F, 0.84F, 0.92F},
            {0.80F, 0.80F, 0.74F},
            {0.95F, 0.58F, 0.12F}
    };

    private static final class Effect {
        boolean active;
        int channel;
        int style;
        float strength;
        double start;
        float duration;
        float attack;
        float release;
        /** Flicker segment length in ticks, or pulse speed in radians per tick. */
        float param;
        long seed;
        /** Effects added together (one storm, one distortion) share a group and count once. */
        long group;
    }

    private static final Effect[] SLOTS = new Effect[MAX_SLOTS];
    private static final float[] SLOT_VALUES = new float[MAX_SLOTS];
    private static final float[] LEVELS = new float[CHANNELS];
    private static final float[] REMAINING = new float[CHANNELS];
    private static final RandomSource RANDOM = RandomSource.create();

    static {
        for (int i = 0; i < MAX_SLOTS; i++) {
            SLOTS[i] = new Effect();
        }
    }

    private static long nextGroup = 1L;
    private static long openGroup;

    // Visual clock in ticks: advances with real time while the game is not paused.
    private static double time;
    private static long lastNanos;
    private static double lastCameraTime = -1.0;

    // Camera output, recomputed once per frame.
    private static double shakePhase;
    private static double fovPhase;
    private static float cameraYaw;
    private static float cameraPitch;
    private static float cameraRoll;
    private static float fovOffset;

    // Persistent stage-driven layer.
    private static float persistentShake;
    private static float persistentNoise;
    private static float persistentRgb;

    // Colour of the strongest sky tint / direction of the strongest colour shift.
    private static float skyTintR = 1.0F;
    private static float skyTintG = 1.0F;
    private static float skyTintB = 1.0F;
    private static float hueDirection = 1.0F;
    private static float huePhase;

    // Transition fade (separate channel, never flashes).
    private static float fadeFrom;
    private static float fadeTo;
    private static double fadeStart;
    private static float fadeDuration;

    // NULLROOT capture overlay (separate channel).
    private static float captureTarget;
    private static float captureValue;
    private static double captureTouched;

    /** A transition fade held this long without news is released (its driver must have died). */
    private static final double FADE_WATCHDOG_TICKS = 20.0 * 60.0;
    /** A capture overlay nobody has updated for this long is released. */
    private static final double CAPTURE_WATCHDOG_TICKS = 20.0 * 30.0;

    // Glitch text.
    private static String glitchText;
    private static double glitchStart;
    private static int glitchDuration;
    private static boolean glitchLarge;
    private static long glitchSeed;

    private VisualDistortionManager() {
    }

    // ===================================================================== public API

    /** Starts an effect; every accessibility limit is applied here. */
    public static void add(Kind kind, float intensity, int durationTicks) {
        add(kind, intensity, durationTicks, RANDOM.nextLong());
    }

    public static void add(Kind kind, float intensity, int durationTicks, long seed) {
        if (kind == null) {
            return;
        }
        switch (kind) {
            case FREEZE -> freeze(durationTicks, seed);
            case CAMERA_JERK -> addEffect(CAMERA_JERK, intensity, durationTicks, seed, STYLE_SNAP, 0.0F, 0.0F, 0.0F);
            case FLASH -> addEffect(FLASH, intensity, durationTicks, seed, STYLE_SMOOTH, 1.0F, durationTicks * 0.7F, 0.0F);
            case BLACKOUT -> addEffect(BLACKOUT, intensity, durationTicks, seed, STYLE_SMOOTH, 1.5F, Math.min(10.0F, durationTicks * 0.35F), 0.0F);
            case FOV_PULSE -> addEffect(FOV_PULSE, intensity, durationTicks, seed, STYLE_SMOOTH, -1.0F, -1.0F, 0.0F);
            default -> addEffect(kind.ordinal(), intensity, durationTicks, seed, STYLE_SMOOTH, -1.0F, -1.0F, 0.0F);
        }
    }

    public static void shake(float intensity, int durationTicks) {
        add(Kind.SHAKE, intensity, durationTicks);
    }

    /**
     * Full-screen black for the NULL transition: fades from the current value to
     * {@code targetAlpha} over {@code ticks}. Ignores the effect cap and never flashes.
     */
    public static void setTransitionFade(float targetAlpha, int ticks) {
        float current = transitionFade();
        fadeFrom = current;
        fadeTo = SmoothNoise.clamp01(targetAlpha);
        fadeStart = time;
        fadeDuration = Math.max(0, ticks);
    }

    /** 0..1 tendrils closing in over the screen edges while a NULLROOT holds the player. */
    public static void setCaptureOverlay(float amount) {
        captureTarget = SmoothNoise.clamp01(Float.isNaN(amount) ? 0.0F : amount);
        captureTouched = time;
    }

    /** Big (or smaller) centred corrupted text. Replaces any glitch text already showing. */
    public static void showGlitchText(String text, int durationTicks, boolean large) {
        if (text == null || text.isBlank() || ClientLimits.maxVisualEffects() <= 0) {
            return;
        }
        glitchText = text;
        glitchStart = time;
        glitchDuration = Mth.clamp(durationTicks, 10, 600);
        glitchLarge = large;
        glitchSeed = RANDOM.nextLong();
    }

    /**
     * Stops every distortion, glitch text, freeze, block glitch, chunk flicker and silhouette.
     * The transition fade and the capture overlay are left alone: their owners drive them.
     */
    public static void clearAll() {
        clearEffects();
        ClientBlockGlitchManager.restoreAll();
        FakeDisconnectScreen.closeIfOpen();
    }

    /** Current aggregated 0..1 strength of a kind (as of the last rendered frame). */
    public static float level(Kind kind) {
        return kind == null ? 0.0F : LEVELS[kind.ordinal()];
    }

    /** A white flash, softened (or turned into a slow brightening) by the flashing setting. */
    public static void flash(float intensity, int durationTicks) {
        add(Kind.FLASH, intensity, durationTicks);
    }

    /** Current transition fade alpha, 0..1. */
    public static float transitionFadeAlpha() {
        return transitionFade();
    }

    /** True while a visual freeze holds the screen. */
    public static boolean frozen() {
        return LEVELS[FREEZE] > 0.0F;
    }

    // ===================================================================== internal API

    /**
     * Adds an effect on any channel. A negative attack or release picks a default from the
     * duration. Returns false when the limits rejected it.
     */
    static boolean addEffect(int channel, float intensity, int durationTicks, long seed, int style,
                             float attack, float release, float param) {
        return addEffect(channel, intensity, durationTicks, seed, style, attack, release, param, 0);
    }

    /** As above, starting {@code delayTicks} from now. */
    static boolean addEffect(int channel, float intensity, int durationTicks, long seed, int style,
                             float attack, float release, float param, int delayTicks) {
        if (!(intensity > 0.0F) || durationTicks <= 0) {
            return false;
        }
        int cap = ClientLimits.maxVisualEffects();
        if (cap <= 0) {
            return false;
        }
        float strength = Math.min(1.0F, intensity);
        float duration = durationTicks;
        float flashing = SmoothNoise.clamp01(ClientLimits.flashing());
        boolean intense = ClientLimits.intenseAllowed();
        if (attack < 0.0F) {
            attack = Mth.clamp(duration * 0.15F, 1.5F, 20.0F);
        }
        if (release < 0.0F) {
            release = Mth.clamp(duration * 0.35F, 2.0F, 40.0F);
        }
        boolean strobeAllowed = intense && flashing >= 0.6F;
        switch (channel) {
            case SHAKE, CAMERA_JERK, FOV_PULSE -> strength *= ClientLimits.shake();
            case FLASH -> {
                // Flashes lose strength and speed with the flashing setting; at 0 they are a
                // slow, faint brightening.
                strength *= 0.3F + 0.7F * flashing;
                attack = Mth.lerp(flashing, 14.0F, attack);
                release = Mth.lerp(flashing, 26.0F, release);
                if (!strobeAllowed && style == STYLE_FLICKER) {
                    style = STYLE_SMOOTH;
                }
            }
            case BLACKOUT, CH_SKY_FLICKER -> {
                // Darkness itself is fine; what must go is the speed of the change.
                attack = Mth.lerp(flashing, Math.max(attack, 16.0F), attack);
                release = Mth.lerp(flashing, Math.max(release, 24.0F), release);
                if (!strobeAllowed && style == STYLE_FLICKER) {
                    style = STYLE_SMOOTH;
                    strength *= 0.6F + 0.4F * flashing;
                }
                if (channel == BLACKOUT && !intense) {
                    // Blackouts are intense effects: softened, the world stays dimly visible.
                    strength = Math.min(strength, 0.7F);
                }
            }
            case CH_TEAR -> strength *= 0.3F + 0.7F * flashing;
            default -> {
            }
        }
        if (style == STYLE_FLICKER && !strobeAllowed) {
            style = STYLE_SMOOTH;
        }
        if (style != STYLE_SNAP) {
            // A slowed-down fade needs room to happen.
            duration = Math.max(duration, attack + release + 2.0F);
        }
        if (strength < EPSILON) {
            return false;
        }
        long group = openGroup != 0L ? openGroup : nextGroup++;
        Effect slot = claimSlot(cap, strength, group);
        if (slot == null) {
            return false;
        }
        slot.active = true;
        slot.channel = channel;
        slot.style = style;
        slot.strength = strength;
        slot.start = time + Math.max(0, delayTicks);
        slot.duration = duration;
        slot.attack = attack;
        slot.release = release;
        slot.param = param;
        slot.seed = seed;
        slot.group = group;
        return true;
    }

    /**
     * Everything added until {@link #endGroup()} is one effect as far as the effect cap is
     * concerned: an RGB storm made of seven layers still counts once, and is dropped as a whole.
     */
    static void beginGroup() {
        openGroup = nextGroup++;
    }

    static void endGroup() {
        openGroup = 0L;
    }

    static RandomSource random() {
        return RANDOM;
    }

    static double time() {
        return time;
    }

    static float level(int channel) {
        return LEVELS[channel];
    }

    static float cameraYaw() {
        return cameraYaw;
    }

    static float cameraPitch() {
        return cameraPitch;
    }

    static float cameraRoll() {
        return cameraRoll;
    }

    static float fovOffset() {
        return fovOffset;
    }

    static float captureOverlay() {
        return captureValue;
    }

    static float skyTintR() {
        return skyTintR;
    }

    static float skyTintG() {
        return skyTintG;
    }

    static float skyTintB() {
        return skyTintB;
    }

    /** Hue rotation for the shader in radians. */
    static float hueShift() {
        return LEVELS[COLOR_SHIFT] * hueDirection * (1.15F + 0.45F * Mth.sin(huePhase));
    }

    static float transitionFade() {
        if (fadeDuration <= 0.0F) {
            return fadeTo;
        }
        float x = SmoothNoise.clamp01((float) ((time - fadeStart) / fadeDuration));
        float s = x * x * (3.0F - 2.0F * x);
        return fadeFrom + (fadeTo - fadeFrom) * s;
    }

    static String glitchText() {
        if (glitchText != null && time - glitchStart >= glitchDuration) {
            glitchText = null;
        }
        return glitchText;
    }

    static double glitchAge() {
        return time - glitchStart;
    }

    static int glitchDuration() {
        return glitchDuration;
    }

    static boolean glitchLarge() {
        return glitchLarge;
    }

    static long glitchSeed() {
        return glitchSeed;
    }

    /** True when any level would change the picture through the post-processing shader. */
    static boolean anyShaderLevel() {
        return LEVELS[RGB_SPLIT] > EPSILON || LEVELS[COLOR_SHIFT] > EPSILON || LEVELS[NOISE] > EPSILON
                || LEVELS[SCANLINES] > EPSILON || LEVELS[WARP] > EPSILON || LEVELS[DESATURATE] > EPSILON
                || LEVELS[DARKEN] > EPSILON || LEVELS[CH_BANDS] > EPSILON;
    }

    // ===================================================================== lifecycle

    /** Advances the clock and recomputes every level. Called once per frame. */
    static void onRenderFrame() {
        long nanos = Util.getNanos();
        if (lastNanos != 0L && !Minecraft.getInstance().isPaused()) {
            double dt = (nanos - lastNanos) / 5.0E7;
            if (dt > 0.0) {
                // A long hitch must not skip whole effects.
                time += Math.min(dt, 4.0);
            }
        }
        lastNanos = nanos;
        refresh();
    }

    /** Stage-driven ambience scheduled per client tick, plus the safety watchdogs. */
    static void clientTick() {
        // Never leave the screen black or wrapped in tendrils because whoever drives those
        // channels stopped talking (lost packet, crashed system): give the game back.
        if (fadeTo > 0.0F && time - (fadeStart + fadeDuration) > FADE_WATCHDOG_TICKS) {
            NullMod.LOGGER.warn("NULL transition fade was held for over a minute without updates; releasing it");
            setTransitionFade(0.0F, 60);
        }
        if (captureTarget > 0.0F && time - captureTouched > CAPTURE_WATCHDOG_TICKS) {
            captureTarget = 0.0F;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || !ClientCorruptionState.synced() || ClientCorruptionState.paused()) {
            return;
        }
        CorruptionStage stage = ClientCorruptionState.stage();
        if (stage == CorruptionStage.MIDDLE || stage == CorruptionStage.ABOVE_MIDDLE) {
            // Rare, tiny ambient warps: the picture breathes once and is still again (on average
            // every 40 s in Stage 3 and 26 s in Stage 4; it was 95 s and 55 s before the pacing was raised).
            float chance = stage == CorruptionStage.MIDDLE ? 1.0F / (20.0F * 40.0F) : 1.0F / (20.0F * 26.0F);
            if (RANDOM.nextFloat() < chance) {
                float strength = 0.06F + RANDOM.nextFloat() * (stage == CorruptionStage.MIDDLE ? 0.08F : 0.12F);
                addEffect(WARP, strength, 40 + RANDOM.nextInt(50), RANDOM.nextLong(), STYLE_SMOOTH, 16.0F, 24.0F, 0.0F);
            }
        }
    }

    /** Logout: everything goes, including the transition fade and the capture overlay. */
    static void reset() {
        // The world is being torn down: forget everything without touching it or the screen.
        clearEffects();
        ClientBlockGlitchManager.forgetAll();
        FakeDisconnectScreen.cancelPending();
        fadeFrom = 0.0F;
        fadeTo = 0.0F;
        fadeDuration = 0.0F;
        captureTarget = 0.0F;
        captureValue = 0.0F;
        shakePhase = 0.0;
        fovPhase = 0.0;
        lastCameraTime = -1.0;
        persistentShake = 0.0F;
        persistentNoise = 0.0F;
        persistentRgb = 0.0F;
        FreezeFrame.destroy();
    }

    /** Dimension change: effects stop, but the transition fade carries over (it hides the teleport). */
    static void onLevelChanged() {
        clearAll();
        captureTarget = 0.0F;
        captureValue = 0.0F;
    }

    // ===================================================================== internals

    private static void clearEffects() {
        for (Effect e : SLOTS) {
            e.active = false;
        }
        Arrays.fill(LEVELS, 0.0F);
        cameraYaw = 0.0F;
        cameraPitch = 0.0F;
        cameraRoll = 0.0F;
        fovOffset = 0.0F;
        glitchText = null;
        FreezeFrame.release();
        ChunkFlickerRenderer.clear();
        SilhouetteRenderer.clear();
    }

    /** Starts a freeze; false if it was softened (intense effects off) or did not fit. */
    static boolean freeze(int durationTicks, long seed) {
        if (!ClientLimits.intenseAllowed()) {
            // Too intense for this player: the colour drains for a moment instead.
            addEffect(DESATURATE, 0.35F, Math.max(30, durationTicks), seed, STYLE_SMOOTH, 6.0F, 14.0F, 0.0F);
            return false;
        }
        if (LEVELS[FREEZE] <= 0.0F) {
            FreezeFrame.release();
        }
        return addEffect(FREEZE, 1.0F, Mth.clamp(durationTicks, 4, 120), seed, STYLE_SMOOTH, 0.0F, 0.0F, 0.0F);
    }

    /**
     * A free slot for an effect of {@code group}. A new group needs room under the cap; when
     * there is none the weakest whole group goes, but only if it is weaker than the newcomer.
     */
    private static Effect claimSlot(int cap, float strength, long group) {
        Effect free = null;
        boolean groupActive = false;
        for (int i = 0; i < MAX_SLOTS; i++) {
            Effect e = SLOTS[i];
            SLOT_VALUES[i] = 0.0F;
            if (e.active) {
                // A delayed effect that has not started yet counts at its full strength.
                SLOT_VALUES[i] = time < e.start ? e.strength : evaluate(e, time);
                groupActive |= e.active && e.group == group;
            }
            if (!e.active && free == null) {
                free = e;
            }
        }
        if (!groupActive) {
            int groups = 0;
            long weakestGroup = 0L;
            float weakestValue = Float.MAX_VALUE;
            for (int i = 0; i < MAX_SLOTS; i++) {
                Effect e = SLOTS[i];
                if (!e.active || firstOfGroup(i) != i) {
                    continue;
                }
                groups++;
                float value = groupValue(e.group);
                if (value < weakestValue) {
                    weakestValue = value;
                    weakestGroup = e.group;
                }
            }
            if (groups >= cap) {
                if (weakestValue >= strength) {
                    return null;
                }
                free = dropGroup(weakestGroup, free);
            }
        }
        if (free != null) {
            return free;
        }
        // Every slot is busy: the single weakest layer of another effect makes room.
        int weakest = -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (SLOTS[i].active && SLOTS[i].group != group && (weakest < 0 || SLOT_VALUES[i] < SLOT_VALUES[weakest])) {
                weakest = i;
            }
        }
        if (weakest >= 0 && SLOT_VALUES[weakest] < strength) {
            SLOTS[weakest].active = false;
            return SLOTS[weakest];
        }
        return null;
    }

    private static int firstOfGroup(int index) {
        long group = SLOTS[index].group;
        for (int i = 0; i < index; i++) {
            if (SLOTS[i].active && SLOTS[i].group == group) {
                return i;
            }
        }
        return index;
    }

    private static float groupValue(long group) {
        float value = 0.0F;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (SLOTS[i].active && SLOTS[i].group == group) {
                value = Math.max(value, SLOT_VALUES[i]);
            }
        }
        return value;
    }

    private static Effect dropGroup(long group, Effect free) {
        for (Effect e : SLOTS) {
            if (e.active && e.group == group) {
                e.active = false;
                if (free == null) {
                    free = e;
                }
            }
        }
        return free;
    }

    private static float evaluate(Effect e, double now) {
        double age = now - e.start;
        if (age >= e.duration) {
            e.active = false;
            return 0.0F;
        }
        if (age < 0.0) {
            return 0.0F;
        }
        float envelope;
        if (e.style == STYLE_SNAP) {
            envelope = snap((float) age, e.duration);
        } else {
            float a = e.attack <= 0.0F ? 1.0F : smooth((float) (age / e.attack));
            float r = e.release <= 0.0F ? 1.0F : smooth((float) ((e.duration - age) / e.release));
            envelope = Math.min(a, r);
            if (e.style == STYLE_FLICKER) {
                float segment = Math.max(1.0F, e.param);
                long index = (long) Math.floor(age / segment);
                envelope *= SmoothNoise.hash01(e.seed, index) < 0.6F ? 1.0F : 0.0F;
            } else if (e.style == STYLE_PULSE) {
                float speed = e.param > 0.0F ? e.param : 0.6F;
                envelope *= 0.55F + 0.45F * Mth.sin((float) (age * speed) + (e.seed & 63L));
            }
        }
        return e.strength * envelope;
    }

    private static float smooth(float x) {
        x = SmoothNoise.clamp01(x);
        return x * x * (3.0F - 2.0F * x);
    }

    /** Instant snap (about one tick), then an eased return to rest. */
    private static float snap(float age, float duration) {
        final float rise = 1.2F;
        if (age < rise) {
            float x = age / rise;
            return 1.0F - (1.0F - x) * (1.0F - x);
        }
        float x = SmoothNoise.clamp01((age - rise) / Math.max(1.0F, duration - rise));
        return (float) Math.exp(-4.2 * x) * (1.0F - x);
    }

    private static void refresh() {
        double now = time;
        Arrays.fill(REMAINING, 1.0F);
        float jerkYaw = 0.0F;
        float jerkPitch = 0.0F;
        float jerkRoll = 0.0F;
        float bestTint = 0.0F;
        long tintSeed = 0L;
        float bestShift = 0.0F;
        long shiftSeed = 0L;
        for (Effect e : SLOTS) {
            if (!e.active) {
                continue;
            }
            float v = evaluate(e, now);
            if (!e.active || v <= 0.0F) {
                continue;
            }
            REMAINING[e.channel] *= 1.0F - Math.min(1.0F, v);
            if (e.channel == CAMERA_JERK) {
                float side = SmoothNoise.hashSigned(e.seed, 1L);
                jerkYaw += v * Math.signum(side == 0.0F ? 1.0F : side) * (5.5F + 5.5F * Math.abs(side));
                jerkPitch += v * SmoothNoise.hashSigned(e.seed, 2L) * 4.5F;
                jerkRoll += v * SmoothNoise.hashSigned(e.seed, 3L) * 3.0F;
            } else if (e.channel == SKY_TINT && v > bestTint) {
                bestTint = v;
                tintSeed = e.seed;
            } else if (e.channel == COLOR_SHIFT && v > bestShift) {
                bestShift = v;
                shiftSeed = e.seed;
            }
        }
        updatePersistent(now);
        REMAINING[SHAKE] *= 1.0F - persistentShake;
        REMAINING[NOISE] *= 1.0F - persistentNoise;
        REMAINING[RGB_SPLIT] *= 1.0F - persistentRgb;
        for (int c = 0; c < CHANNELS; c++) {
            float level = 1.0F - REMAINING[c];
            LEVELS[c] = level < 0.0005F ? 0.0F : Math.min(1.0F, level);
        }
        if (bestTint > 0.0F) {
            float[] tint = TINTS[(int) Math.floorMod(SmoothNoise.mix(tintSeed), (long) TINTS.length)];
            skyTintR = tint[0];
            skyTintG = tint[1];
            skyTintB = tint[2];
        }
        if (bestShift > 0.0F) {
            hueDirection = (shiftSeed & 1L) == 0L ? 1.0F : -1.0F;
        }

        double dt = lastCameraTime < 0.0 ? 0.0 : Mth.clamp(now - lastCameraTime, 0.0, 4.0);
        lastCameraTime = now;
        huePhase = (float) ((huePhase + dt * 0.045) % (Math.PI * 2.0));

        // Smooth, noise-driven shake: the phase is integrated so a changing strength never
        // makes the motion jump.
        float shake = LEVELS[SHAKE];
        shakePhase += dt * (0.05 + 0.30 * shake);
        cameraYaw = shake * 3.4F * SmoothNoise.fbm(SEED_YAW, shakePhase) + jerkYaw;
        cameraPitch = shake * 2.6F * SmoothNoise.fbm(SEED_PITCH, shakePhase * 1.07) + jerkPitch;
        cameraRoll = shake * 2.2F * SmoothNoise.fbm(SEED_ROLL, shakePhase * 0.83) + jerkRoll;

        float fov = LEVELS[FOV_PULSE];
        fovPhase += dt * (0.16 + 0.22 * fov);
        fovOffset = fov * (7.5F * Mth.sin((float) fovPhase) + 2.5F * SmoothNoise.noise(SEED_FOV, fovPhase * 0.7));

        captureValue += (captureTarget - captureValue) * (float) Math.min(1.0, dt * 0.35);
        if (Math.abs(captureTarget - captureValue) < 0.001F) {
            captureValue = captureTarget;
        }
        if (LEVELS[FREEZE] <= 0.0F) {
            FreezeFrame.release();
        }
    }

    private static void updatePersistent(double now) {
        persistentShake = 0.0F;
        persistentNoise = 0.0F;
        persistentRgb = 0.0F;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ClientCorruptionState.synced() || ClientLimits.maxVisualEffects() <= 0) {
            return;
        }
        if (ClientCorruptionState.stage() == CorruptionStage.NULL && !ClientCorruptionState.paused()) {
            // Like the audio ambience, the tremor stops while an administrator has NULL paused.
            float progress = SmoothNoise.clamp01(ClientCorruptionState.stageProgress());
            // A constant low tremor that now and then stops completely for a few seconds,
            // which is worse than the tremor itself.
            float still = SmoothNoise.smoothstep(0.6F, 0.74F, SmoothNoise.noise01(SEED_STILL, now / 520.0));
            float wander = 0.75F + 0.25F * SmoothNoise.noise(SEED_WANDER, now / 90.0);
            persistentShake = (0.09F + 0.06F * progress) * wander * (1.0F - still) * ClientLimits.shake();
            persistentNoise = (0.05F + 0.04F * progress) * (1.0F - 0.6F * still);
        }
        if (NullDimensions.isNull(mc.level)) {
            persistentRgb = 0.05F + 0.015F * Mth.sin((float) (now * 0.031));
        }
    }
}
