package dev.kristian.nullmod.client.audio;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * One piece of music as NULL plays it: never positional, never looping, bent every tick by its
 * {@link MusicWarp} when it has one. NULL's own pieces play on the master source, so no setting
 * switches them off and only the master volume makes them quieter; the game's own tracks that NULL
 * corrupts stay on the music source, where the music slider still applies.
 * <p>
 * A detuned double follows a leader: it plays exactly the same file (the leader's resolved variant),
 * a little sharp or flat, with the leader's warp, and stops with it.
 */
final class MusicInstance extends AbstractTickableSoundInstance {
    @Nullable
    private final MusicWarp warp;
    @Nullable
    private final MusicInstance leader;
    private final float baseVolume;
    private final float basePitch;
    @Nullable
    private Sound fixedSound;
    private float mix = 1.0F;
    private float mixTarget = 1.0F;
    private float mixStep = 0.05F;
    private int fadeIn;
    private int fadeOut = 40;
    private int fadeOutStart = -1;
    private int age;

    MusicInstance(SoundEvent event, SoundSource source, float volume, float pitch, @Nullable MusicWarp warp,
                  @Nullable MusicInstance leader, RandomSource random) {
        super(event, source, random);
        this.warp = warp;
        this.leader = leader;
        this.baseVolume = volume;
        this.basePitch = pitch;
        this.looping = false;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
        this.pitch = Mth.clamp(pitch, 0.5F, 2.0F);
        this.volume = Mth.clamp(volume, 0.0F, 1.0F);
    }

    // ------------------------------------------------------------------ configuration (before playing)

    /** Fades in over this many ticks (NULL's own pieces only: a game track starts as it was written). */
    MusicInstance fadeIn(int ticks) {
        fadeIn = Math.max(0, ticks);
        volume = fadeIn > 0 ? 0.0F : volume;
        return this;
    }

    /** Plays exactly this variant of the event (a double plays its leader's file). */
    MusicInstance fixed(@Nullable Sound sound) {
        fixedSound = sound;
        return this;
    }

    /** Starts at this share of its volume (a double starts silent and comes in for passages). */
    MusicInstance mix(float start) {
        mix = Mth.clamp(start, 0.0F, 1.0F);
        mixTarget = mix;
        volume = Mth.clamp(baseVolume * mix, 0.0F, 1.0F);
        return this;
    }

    // ------------------------------------------------------------------ live control

    /** Glides its share of the volume to {@code target} over {@code ticks}. */
    void mixTo(float target, int ticks) {
        mixTarget = Mth.clamp(target, 0.0F, 1.0F);
        mixStep = Math.max(0.001F, Math.abs(mixTarget - mix) / Math.max(1, ticks));
    }

    float mixTarget() {
        return mixTarget;
    }

    /** Begins fading out now; it stops itself once silent. */
    void fadeOut(int ticks) {
        if (fadeOutStart < 0) {
            fadeOut = Math.max(1, ticks);
            fadeOutStart = age;
        }
    }

    boolean fading() {
        return fadeOutStart >= 0;
    }

    @Nullable
    MusicWarp warp() {
        return warp;
    }

    float baseVolume() {
        return baseVolume;
    }

    /** Stops on the next engine tick, without a fade. */
    void stopNow() {
        stop();
    }

    /** The current warp of the pitch, for a double that follows this track. */
    float warpPitch() {
        return warp != null ? warp.pitch() : 1.0F;
    }

    /** The current warp of the volume, for a double that follows this track. */
    float warpVolume() {
        return warp != null ? warp.volume() : 1.0F;
    }

    // ------------------------------------------------------------------ the sound engine's side

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents events = super.resolve(manager);
        if (fixedSound != null && events != null) {
            this.sound = fixedSound;
        }
        return events;
    }

    /**
     * NULL's own pieces keep their place (silently) at a master volume of 0, and a double starts
     * silent and comes in later; a game track with its music slider at 0 is not started at all, like
     * the game's own (and its double is never created).
     */
    @Override
    public boolean canStartSilent() {
        return source == SoundSource.MASTER || leader != null;
    }

    @Override
    public void tick() {
        age++;
        if (Minecraft.getInstance().level == null || (leader != null && leader.isStopped())) {
            stop();
            return;
        }
        if (warp != null) {
            warp.tick();
        }
        float envelope = 1.0F;
        if (fadeIn > 0 && age < fadeIn) {
            envelope = (float) age / fadeIn;
        }
        if (fadeOutStart >= 0) {
            float left = 1.0F - (float) (age - fadeOutStart) / fadeOut;
            if (left <= 0.0F) {
                stop();
                return;
            }
            envelope *= left;
        }
        if (mix != mixTarget) {
            mix = mix < mixTarget ? Math.min(mixTarget, mix + mixStep) : Math.max(mixTarget, mix - mixStep);
        }
        float warpPitch = leader != null ? leader.warpPitch() : warpPitch();
        float warpVolume = leader != null ? leader.warpVolume() : warpVolume();
        this.volume = Mth.clamp(baseVolume * envelope * mix * warpVolume, 0.0F, 1.0F);
        this.pitch = Mth.clamp(basePitch * warpPitch, 0.5F, 2.0F);
    }
}
