package dev.kristian.nullmod.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Every sound NULL plays itself. It never relies on OpenAL's distance attenuation (which would
 * make a sound 20 blocks away inaudible): it uses no attenuation and computes its own gain
 * every tick, so it can sit anywhere around the player, follow them, stay glued behind their
 * head, fade in and out smoothly, and bend with the capture distortion.
 */
public final class NullSoundInstance extends AbstractTickableSoundInstance {
    /** Where the sound is. */
    public enum Placement {
        /** A fixed point in the world; quieter with distance up to {@code range}. */
        WORLD,
        /** A fixed offset from the listener (world axes): the direction stays the same while walking. */
        FOLLOW,
        /** An offset in the listener's own space (-Z ahead, +Z behind): it turns with the head. */
        HEAD,
        /** No position at all: inside the player's head. */
        GLOBAL
    }

    final AudioCorruptionManager.Channel channel;
    private final Placement placement;
    private final double placeX;
    private final double placeY;
    private final double placeZ;
    private final float baseVolume;
    private final float basePitch;
    private final boolean ui;
    private float range = 40.0F;
    private int fadeIn;
    private int fadeOut = 20;
    private int lifetime = -1;
    private float wobble;
    private float wobbleSpeed = 0.05F;
    private float sag;
    private boolean bendsWithCapture = true;
    private float pitchScale = 1.0F;
    private float volumeScale = 1.0F;
    private int age;
    private int fadeOutStart = -1;

    NullSoundInstance(SoundEvent sound, AudioCorruptionManager.Channel channel, Placement placement, double x, double y, double z,
                      float volume, float pitch, boolean looping, boolean ui, RandomSource random) {
        super(sound, AudioCorruptionManager.sourceOf(channel), random);
        this.channel = channel;
        this.placement = placement;
        this.placeX = x;
        this.placeY = y;
        this.placeZ = z;
        this.baseVolume = volume;
        this.basePitch = pitch;
        this.ui = ui;
        this.looping = looping;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = placement == Placement.HEAD || placement == Placement.GLOBAL;
        updatePosition();
        this.pitch = Mth.clamp(pitch, 0.5F, 2.0F);
        this.volume = computeVolume(1.0F);
    }

    // ------------------------------------------------------------------ configuration (before playing)

    NullSoundInstance range(float blocks) {
        this.range = Math.max(1.0F, blocks);
        this.volume = computeVolume(fadeIn > 0 ? 0.0F : 1.0F);
        return this;
    }

    /** Fade in over {@code in} ticks, fade out over {@code out} ticks. */
    NullSoundInstance fades(int in, int out) {
        this.fadeIn = Math.max(0, in);
        this.fadeOut = Math.max(1, out);
        this.volume = computeVolume(fadeIn > 0 ? 0.0F : 1.0F);
        return this;
    }

    /** Starts fading out after this many ticks (loops otherwise play until stopped). */
    NullSoundInstance lifetime(int ticks) {
        this.lifetime = ticks;
        return this;
    }

    /** Slow pitch wobble (wow and flutter): depth as a pitch fraction, speed in radians per tick. */
    NullSoundInstance wobble(float depth, float speed) {
        this.wobble = depth;
        this.wobbleSpeed = speed;
        return this;
    }

    /** Not bent by the capture distortion (the capture loop itself, UI sounds). */
    NullSoundInstance steady() {
        this.bendsWithCapture = false;
        return this;
    }

    // ------------------------------------------------------------------ live control

    /** Begins the fade-out now. */
    public void fadeOut(int ticks) {
        if (fadeOutStart < 0) {
            fadeOut = Math.max(1, ticks);
            fadeOutStart = age;
        }
    }

    /** Multiplies pitch and volume from outside (music bending, drone drift); applied next tick. */
    void modulate(float pitchFactor, float volumeFactor) {
        this.pitchScale = pitchFactor;
        this.volumeScale = volumeFactor;
    }

    /** A sudden sag in pitch that recovers on its own (corrupted music). */
    void sag(float amount) {
        this.sag = Math.max(this.sag, amount);
    }

    public boolean isUi() {
        return ui;
    }

    public boolean fading() {
        return fadeOutStart >= 0;
    }

    int age() {
        return age;
    }

    /** Stops on the next engine tick without a fade. */
    void stopNow() {
        stop();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        age++;
        if (!AudioCorruptionManager.mayContinue(this)) {
            stop();
            return;
        }
        float envelope = 1.0F;
        if (fadeIn > 0 && age < fadeIn) {
            envelope = (float) age / fadeIn;
        }
        if (lifetime >= 0 && age >= lifetime && fadeOutStart < 0) {
            fadeOutStart = age;
        }
        if (fadeOutStart >= 0) {
            float left = 1.0F - (float) (age - fadeOutStart) / fadeOut;
            if (left <= 0.0F) {
                stop();
                return;
            }
            envelope *= left;
        }
        updatePosition();
        this.volume = computeVolume(envelope);
        float p = basePitch * pitchScale;
        if (wobble > 0.0F) {
            p *= 1.0F + wobble * (Mth.sin(age * wobbleSpeed) * 0.7F + Mth.sin(age * wobbleSpeed * 2.7F + 1.3F) * 0.3F);
        }
        if (sag > 0.0F) {
            p *= 1.0F - sag;
            sag = Math.max(0.0F, sag - 0.006F);
        }
        if (bendsWithCapture && !ui) {
            p *= AudioCorruptionManager.capturePitch(age);
        }
        this.pitch = Mth.clamp(p, 0.5F, 2.0F);
    }

    private float computeVolume(float envelope) {
        float gain = baseVolume * envelope * volumeScale;
        if (!ui) {
            gain *= AudioCorruptionManager.channelGain(channel);
            if (bendsWithCapture) {
                gain *= AudioCorruptionManager.captureVolume();
            }
        }
        if (placement == Placement.WORLD) {
            Vec3 listener = listener();
            if (listener != null) {
                double dx = x - listener.x;
                double dy = y - listener.y;
                double dz = z - listener.z;
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float falloff = Mth.clamp(1.0F - (distance - 2.0F) / range, 0.0F, 1.0F);
                gain *= falloff * (0.35F + 0.65F * falloff);
            }
        }
        return Mth.clamp(gain, 0.0F, 1.0F);
    }

    private void updatePosition() {
        switch (placement) {
            case WORLD -> {
                this.x = placeX;
                this.y = placeY;
                this.z = placeZ;
            }
            case FOLLOW -> {
                Vec3 listener = listener();
                if (listener != null) {
                    this.x = listener.x + placeX;
                    this.y = listener.y + placeY;
                    this.z = listener.z + placeZ;
                }
            }
            case HEAD -> {
                this.x = placeX;
                this.y = placeY;
                this.z = placeZ;
            }
            case GLOBAL -> {
                this.x = 0.0;
                this.y = 0.0;
                this.z = 0.0;
            }
        }
    }

    private static Vec3 listener() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.level == null) {
            return null;
        }
        return mc.gameRenderer.getMainCamera().getPosition();
    }
}
