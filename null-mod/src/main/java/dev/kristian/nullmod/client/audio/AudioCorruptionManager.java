package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * NULL's audio corruption: the one place horror sounds are played from, so the player's audio
 * settings (horror sounds on/off, intensity, the per-channel toggles) and the server's limits
 * always apply. It also owns the moments when the game's own audio fails: sudden silences,
 * the silence of the transition into NULL, and the distortion while a NULLROOT holds the player.
 * <p>
 * Silence pauses everything that is playing (it resumes exactly where it was, like a dropout)
 * and holds back new sounds; only {@link #playUi} sounds get through. Loops that the game or
 * another mod starts during a silence are not lost: they start when the silence is over. Never
 * touches audio files or the system mixer, only what Minecraft itself plays. Render thread only.
 */
public final class AudioCorruptionManager {
    /**
     * What a sound is, which decides the player's toggle that controls it: STATIC = static noise,
     * WHISPER = whispers, FOOTSTEPS = phantom footsteps, MUSIC = music distortion; AMBIENT,
     * CREATURE and EVENT only follow the horror-sound switch and intensity (as all channels do).
     * UI sounds are not horror sounds: their caller decides whether they may play.
     */
    public enum Channel { STATIC, WHISPER, FOOTSTEPS, MUSIC, AMBIENT, CREATURE, EVENT, UI }

    private static final int MAX_ACTIVE = 18;
    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<NullSoundInstance> ACTIVE = new ArrayList<>();
    private static final List<Scheduled> SCHEDULED = new ArrayList<>();
    /** Loops of the game and other mods held back by a silence, newest per sound id. */
    private static final Map<ResourceLocation, Deferred> DEFERRED = new LinkedHashMap<>();
    private static final int MAX_DEFERRED = 48;

    /** If nobody lifts the transition silence (a lost packet), it ends on its own after this long. */
    private static final int TRANSITION_SILENCE_LIMIT = 20 * 45;
    /** A capture distortion nobody has updated for this long is released (its driver must have died). */
    private static final int CAPTURE_WATCHDOG_TICKS = 20 * 30;
    /** Ticks between attempts to (re)start the capture drone when the sound engine refused it. */
    private static final int LOOP_RETRY_TICKS = 40;

    /** Set for the rest of the session once the audio tick kept failing: nothing may silence the game then. */
    private static boolean disabled;
    private static int silenceTicks;
    private static boolean pausedBySilence;
    private static boolean transitionSilent;
    private static int transitionTicks;
    private static float captureTarget;
    private static float capture;
    private static int captureUntouched;
    private static int captureRetry;
    @Nullable
    private static NullSoundInstance captureLoop;
    @Nullable
    private static ClientLevel lastLevel;

    private static final class Scheduled {
        int ticks;
        final Runnable action;

        Scheduled(int ticks, Runnable action) {
            this.ticks = ticks;
            this.action = action;
        }
    }

    /** A sound someone keeps and expects to play, and the world it belongs to. */
    private record Deferred(TickableSoundInstance sound, @Nullable ClientLevel level) {
    }

    private AudioCorruptionManager() {
    }

    // ------------------------------------------------------------------ public API (BRIEF section 4)

    /**
     * Plays a horror sound. With a position it sits there; without one it comes from somewhere
     * the player cannot pin down (6-20 blocks away, right behind them, below them, or nowhere).
     * Volume is scaled by the player's audio settings; nothing plays when the channel is off.
     */
    public static void play(SoundEvent sound, Channel channel, @Nullable Vec3 pos, float volume, float pitch) {
        if (sound == null || !canStart(channel, sound) || volume <= 0.0F) {
            return;
        }
        if (pos != null) {
            Vec3 listener = listener();
            float distance = listener == null ? 0.0F : (float) listener.distanceTo(pos);
            start(new NullSoundInstance(sound, channel, NullSoundInstance.Placement.WORLD, pos.x, pos.y, pos.z,
                    volume, pitch, false, false, RANDOM).range(Math.max(40.0F, distance * 1.5F)));
            return;
        }
        AudioPositions.Spot spot = AudioPositions.ambiguous(RANDOM);
        start(new NullSoundInstance(sound, channel, spot.placement(), spot.x(), spot.y(), spot.z(),
                volume, pitch, false, false, RANDOM).range(40.0F));
    }

    /**
     * A non-positional sound at the player's ears that ignores silences (UI, transitions,
     * frozen-game buzz). The caller decides whether it may play; volume is used as given.
     */
    public static void playUi(SoundEvent sound, float volume, float pitch) {
        if (sound == null || volume <= 0.0F || Minecraft.getInstance().level == null) {
            return;
        }
        start(new NullSoundInstance(sound, Channel.UI, NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0,
                volume, pitch, false, true, RANDOM).steady());
    }

    /**
     * Everything stops at once and nothing new plays (except {@link #playUi}) for a while.
     * Does nothing when the player switched off horror sounds or sudden silences.
     */
    public static void silence(int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (disabled || ticks <= 0 || transitionSilent || mc.level == null || ClientLimits.audio() <= 0.0F
                || !NullClientSettings.get(NullClientSettings.SUDDEN_SILENCE)) {
            return;
        }
        silenceTicks = Math.max(silenceTicks, ticks);
        SCHEDULED.clear();
        if (!pausedBySilence) {
            mc.getSoundManager().pause();
            pausedBySilence = true;
        }
    }

    /**
     * 0..1 while a NULLROOT holds the player: everything bends and the capture drone plays.
     * Call it while the capture lasts (every tick or whenever it changes) and with 0 on release;
     * a value nobody refreshes for 30 seconds is released automatically.
     */
    public static void setCaptureDistortion(float amount) {
        captureTarget = Float.isNaN(amount) ? 0.0F : Mth.clamp(amount, 0.0F, 1.0F);
        captureUntouched = 0;
    }

    /**
     * The cinematic move into NULL: every sound stops and only transition sounds
     * ({@code null:transition.*}, through any of the play methods) and {@link #playUi} sounds may play.
     */
    public static void setTransitionSilence(boolean silent) {
        if (silent == transitionSilent || (silent && disabled)) {
            // Disabled: tick() no longer runs, so nothing would ever lift it.
            return;
        }
        transitionSilent = silent;
        transitionTicks = 0;
        if (silent) {
            silenceTicks = 0;
            SCHEDULED.clear();
            Minecraft mc = Minecraft.getInstance();
            stopEverythingButUi(mc);
            if (pausedBySilence) {
                pausedBySilence = false;
                if (!mc.isPaused()) {
                    mc.getSoundManager().resume();
                }
            }
            MusicCorruption.stop(0);
            AmbientScheduler.stopLoops(0);
            PhantomFootsteps.reset();
        }
    }

    public static boolean isSilenced() {
        return silenceTicks > 0 || transitionSilent;
    }

    // ------------------------------------------------------------------ extras

    /** Plays a sound from a direction (it keeps that direction while the player walks). */
    public static void playFromDirection(SoundEvent sound, Channel channel, Vec3 direction, float volume, float pitch) {
        if (sound == null || !canStart(channel, sound) || volume <= 0.0F || direction.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 offset = direction.normalize().scale(12.0);
        start(new NullSoundInstance(sound, channel, NullSoundInstance.Placement.FOLLOW, offset.x, offset.y, offset.z,
                volume, pitch, false, false, RANDOM));
    }

    /** Plays a sound at a point that stays audible out to {@code range} blocks. */
    public static void playAt(SoundEvent sound, Channel channel, Vec3 pos, float volume, float pitch, float range) {
        if (sound == null || !canStart(channel, sound) || volume <= 0.0F) {
            return;
        }
        start(new NullSoundInstance(sound, channel, NullSoundInstance.Placement.WORLD, pos.x, pos.y, pos.z,
                volume, pitch, false, false, RANDOM).range(range));
    }

    /** Plays a sound with no position at all. */
    public static void playGlobal(SoundEvent sound, Channel channel, float volume, float pitch) {
        if (sound == null || !canStart(channel, sound) || volume <= 0.0F) {
            return;
        }
        start(new NullSoundInstance(sound, channel, NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0,
                volume, pitch, false, false, RANDOM));
    }

    /** Plays a sound right behind the player's head; it stays behind them however they turn. */
    public static void playBehind(SoundEvent sound, Channel channel, float distance, float volume, float pitch) {
        if (sound == null || !canStart(channel, sound) || volume <= 0.0F) {
            return;
        }
        double side = (RANDOM.nextFloat() - 0.5F) * 0.8F;
        start(new NullSoundInstance(sound, channel, NullSoundInstance.Placement.HEAD, side, 0.05 + RANDOM.nextFloat() * 0.2,
                Math.max(0.4F, distance), volume, pitch, false, false, RANDOM));
    }

    public static boolean isTransitionSilent() {
        return transitionSilent;
    }

    /** Current (smoothed) capture distortion, 0..1. */
    public static float captureDistortion() {
        return capture;
    }

    /** True if a channel may make sound at all right now (settings and silences). */
    public static boolean channelEnabled(Channel channel) {
        return channelGain(channel) > 0.0F;
    }

    /** Stops every sound NULL started (not the game's own sounds). */
    public static void stopAll() {
        stopOwn(false);
    }

    /** True while one of our sounds is really in the sound engine (not finished, refused or cut off). */
    public static boolean isPlaying(@Nullable NullSoundInstance sound) {
        return sound != null && !sound.isStopped() && Minecraft.getInstance().getSoundManager().isActive(sound);
    }

    /** Stops our sounds; with {@code keepUiAndTransition} interface and transition sounds keep playing. */
    private static void stopOwn(boolean keepUiAndTransition) {
        Minecraft mc = Minecraft.getInstance();
        for (Iterator<NullSoundInstance> it = ACTIVE.iterator(); it.hasNext(); ) {
            NullSoundInstance sound = it.next();
            if (!keepUiAndTransition || !(sound.isUi() || isTransitionSound(sound.getLocation()))) {
                sound.stopNow();
                mc.getSoundManager().stop(sound);
                it.remove();
            }
        }
        captureLoop = null;
        SCHEDULED.clear();
    }

    // ------------------------------------------------------------------ used by the rest of the audio package

    /** True if a sound on this channel may start now (world loaded, settings, silences). */
    static boolean canStart(Channel channel) {
        return canStart(channel, null);
    }

    /**
     * True if this sound may start now: the channel is on, nothing is silencing the game, or the
     * transition into NULL is running and this is one of its own {@code transition.*} sounds.
     */
    static boolean canStart(Channel channel, @Nullable SoundEvent sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || channelGain(channel) <= 0.0F) {
            return false;
        }
        if (transitionSilent) {
            return sound != null && isTransitionSound(sound.getLocation());
        }
        return silenceTicks <= 0;
    }

    /** Settings multiplier for a channel: 0 when horror sounds or the channel's toggle are off. */
    static float channelGain(Channel channel) {
        if (channel == Channel.UI) {
            return 1.0F;
        }
        float audio = ClientLimits.audio();
        if (audio <= 0.0F) {
            return 0.0F;
        }
        boolean on = switch (channel) {
            case STATIC -> NullClientSettings.get(NullClientSettings.STATIC_NOISE);
            case WHISPER -> NullClientSettings.get(NullClientSettings.WHISPERS);
            case FOOTSTEPS -> NullClientSettings.get(NullClientSettings.PHANTOM_FOOTSTEPS);
            case MUSIC -> NullClientSettings.get(NullClientSettings.MUSIC_DISTORTION);
            default -> true;
        };
        return on ? audio : 0.0F;
    }

    static SoundSource sourceOf(Channel channel) {
        return switch (channel) {
            case FOOTSTEPS -> SoundSource.PLAYERS;
            case MUSIC -> SoundSource.MUSIC;
            case CREATURE -> SoundSource.HOSTILE;
            case UI -> SoundSource.MASTER;
            default -> SoundSource.AMBIENT;
        };
    }

    /** Starts one of our instances (dropping the oldest one if too many are playing). */
    @Nullable
    static NullSoundInstance start(NullSoundInstance sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        SoundManager manager = mc.getSoundManager();
        prune(manager);
        if (ACTIVE.size() >= MAX_ACTIVE) {
            for (NullSoundInstance old : ACTIVE) {
                if (!old.isUi() && old != captureLoop && !old.isLooping()) {
                    old.stopNow();
                    manager.stop(old);
                    ACTIVE.remove(old);
                    break;
                }
            }
        }
        ACTIVE.add(sound);
        manager.play(sound);
        return sound;
    }

    /** Runs an action after a delay unless a silence or the transition intervenes. */
    static void schedule(int ticks, Runnable action) {
        if (SCHEDULED.size() < 64) {
            SCHEDULED.add(new Scheduled(Math.max(1, ticks), action));
        }
    }

    /** Whether one of our sounds may keep playing (checked every tick by the sound itself). */
    static boolean mayContinue(NullSoundInstance sound) {
        if (Minecraft.getInstance().level == null) {
            return false;
        }
        return !transitionSilent || sound.isUi() || isTransitionSound(sound.getLocation());
    }

    /**
     * Whether a sound about to start must be held back right now. One-shots are dropped; a
     * ticking sound of the game or another mod (biome loops, minecarts, bees, machines) is kept by
     * whoever started it and would otherwise stay silent for good, so it starts once the silence
     * is over. Records and loops nobody could restart are never held back.
     */
    static boolean suppressed(SoundInstance sound) {
        if (!isSilenced() || sound.getSource() == SoundSource.MASTER) {
            // Interface clicks keep working; menus must never feel broken.
            return false;
        }
        boolean transition = transitionSilent && isTransitionSound(sound.getLocation());
        if (sound instanceof NullSoundInstance own) {
            return !own.isUi() && !transition;
        }
        if (transition || sound.getSource() == SoundSource.RECORDS
                || (sound.isLooping() && !(sound instanceof TickableSoundInstance))) {
            return false;
        }
        if (sound instanceof TickableSoundInstance tickable && !tickable.isStopped()) {
            // Only the newest per sound id: a mod that makes a new instance every tick while its
            // loop is not playing must get exactly one loop back, not dozens.
            DEFERRED.remove(sound.getLocation());
            if (DEFERRED.size() < MAX_DEFERRED) {
                DEFERRED.put(sound.getLocation(), new Deferred(tickable, Minecraft.getInstance().level));
            }
        }
        return true;
    }

    /** Starts the held-back loops that still belong to this world and are still wanted. */
    private static void replayDeferred(Minecraft mc) {
        List<Deferred> due = new ArrayList<>(DEFERRED.values());
        DEFERRED.clear();
        SoundManager manager = mc.getSoundManager();
        for (Deferred deferred : due) {
            TickableSoundInstance sound = deferred.sound();
            if (deferred.level() == mc.level && !sound.isStopped() && !manager.isActive(sound)) {
                manager.play(sound);
            }
        }
    }

    static boolean isTransitionSound(ResourceLocation id) {
        return NullMod.MODID.equals(id.getNamespace()) && id.getPath().startsWith("transition.");
    }

    static float capturePitch(int age) {
        if (capture <= 0.001F) {
            return 1.0F;
        }
        return 1.0F - 0.22F * capture * (0.65F + 0.35F * Mth.sin(age * 0.21F));
    }

    static float captureVolume() {
        return 1.0F - 0.35F * capture;
    }

    static RandomSource random() {
        return RANDOM;
    }

    @Nullable
    static Vec3 listener() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return null;
        }
        return mc.gameRenderer.getMainCamera().getPosition();
    }

    // ------------------------------------------------------------------ ticking and cleanup

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            boolean hadLevel = lastLevel != null;
            lastLevel = mc.level;
            if (hadLevel) {
                onLevelChanged(mc);
            }
        }
        if (mc.level == null) {
            return;
        }
        if (silenceTicks > 0 && --silenceTicks == 0) {
            endSilence(mc);
        }
        if (transitionSilent && ++transitionTicks > TRANSITION_SILENCE_LIMIT) {
            NullMod.LOGGER.warn("NULL transition silence was never lifted; lifting it");
            transitionSilent = false;
            transitionTicks = 0;
        }
        if (!DEFERRED.isEmpty() && !isSilenced()) {
            replayDeferred(mc);
        }
        if (!SCHEDULED.isEmpty() && !mc.isPaused()) {
            List<Scheduled> due = null;
            for (Iterator<Scheduled> it = SCHEDULED.iterator(); it.hasNext(); ) {
                Scheduled scheduled = it.next();
                if (--scheduled.ticks <= 0) {
                    it.remove();
                    if (due == null) {
                        due = new ArrayList<>(2);
                    }
                    due.add(scheduled);
                }
            }
            if (due != null && !isSilenced()) {
                for (Scheduled scheduled : due) {
                    scheduled.action.run();
                }
            }
        }
        tickCapture(mc);
        prune(mc.getSoundManager());
    }

    private static void tickCapture(Minecraft mc) {
        if (captureTarget > 0.0F && !mc.isPaused() && ++captureUntouched > CAPTURE_WATCHDOG_TICKS) {
            NullMod.LOGGER.warn("NULL capture distortion was never released; releasing it");
            captureTarget = 0.0F;
        }
        float difference = captureTarget - capture;
        capture = Math.abs(difference) < 0.002F ? captureTarget : capture + difference * 0.15F;
        if (captureRetry > 0) {
            captureRetry--;
        }
        boolean wanted = capture > 0.02F && !isSilenced() && channelGain(Channel.CREATURE) > 0.0F;
        if (wanted) {
            NullSoundInstance loop = captureLoop;
            if (!isPlaying(loop) || loop.fading()) {
                if (loop != null && !loop.fading()) {
                    // Cut off from outside (sound engine reset, /stopsound): forget it and start anew.
                    loop.stopNow();
                }
                captureLoop = null;
                if (captureRetry <= 0) {
                    captureRetry = LOOP_RETRY_TICKS;
                    captureLoop = start(new NullSoundInstance(NullSounds.NULLROOT_CAPTURE.get(), Channel.CREATURE,
                            NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0, 0.9F, 1.0F, true, false, RANDOM)
                            .steady().fades(15, 25));
                }
            }
            if (captureLoop != null) {
                captureLoop.modulate(0.85F + 0.25F * capture, capture);
            }
        } else if (captureLoop != null) {
            captureLoop.fadeOut(25);
            captureLoop = null;
        }
    }

    private static void endSilence(Minecraft mc) {
        silenceTicks = 0;
        if (pausedBySilence) {
            pausedBySilence = false;
            // If the game itself is paused right now, vanilla resumes everything when it unpauses.
            if (!mc.isPaused()) {
                mc.getSoundManager().resume();
            }
        }
    }

    private static void stopEverythingButUi(Minecraft mc) {
        SoundManager manager = mc.getSoundManager();
        for (SoundSource source : SoundSource.values()) {
            if (source != SoundSource.MASTER) {
                manager.stop(null, source);
            }
        }
        for (Iterator<NullSoundInstance> it = ACTIVE.iterator(); it.hasNext(); ) {
            NullSoundInstance sound = it.next();
            if (!sound.isUi() && !isTransitionSound(sound.getLocation())) {
                sound.stopNow();
                it.remove();
            }
        }
        captureLoop = null;
    }

    private static void prune(SoundManager manager) {
        ACTIVE.removeIf(sound -> sound.isStopped() || !manager.isActive(sound));
    }

    private static void onLevelChanged(Minecraft mc) {
        // Dimension change or respawn: nothing of ours carries over, except interface and
        // transition sounds started for the new world (the move into NULL keeps its own sounds).
        stopOwn(true);
        // Loops held back in the old world died with it (the game stops every sound on a level
        // change); ones the new world already started stay held until the silence ends.
        DEFERRED.values().removeIf(deferred -> deferred.level() != mc.level);
        captureTarget = 0.0F;
        capture = 0.0F;
        captureRetry = 0;
        MusicCorruption.stop(0);
        AmbientScheduler.reset();
        PhantomFootsteps.reset();
        PhantomWalkers.reset();
        PitchCorruption.reset();
        if (pausedBySilence) {
            endSilence(mc);
        }
        silenceTicks = 0;
    }

    /** The audio tick gave up: back to normal audio, and no silences from now on (it would never end them). */
    static void disable() {
        disabled = true;
        reset();
    }

    /** Logout: back to a clean state, with the game's audio running normally. */
    static void reset() {
        Minecraft mc = Minecraft.getInstance();
        stopAll();
        if (pausedBySilence) {
            pausedBySilence = false;
            if (!mc.isPaused()) {
                mc.getSoundManager().resume();
            }
        }
        silenceTicks = 0;
        transitionSilent = false;
        transitionTicks = 0;
        captureTarget = 0.0F;
        capture = 0.0F;
        captureRetry = 0;
        lastLevel = null;
        DEFERRED.clear();
        MusicCorruption.stop(0);
        AmbientScheduler.reset();
        PhantomFootsteps.reset();
        PhantomWalkers.reset();
        PitchCorruption.reset();
    }
}
