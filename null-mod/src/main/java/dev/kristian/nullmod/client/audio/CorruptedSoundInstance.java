package dev.kristian.nullmod.client.audio;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/**
 * A game sound played at the wrong pitch (and, while captured, the wrong volume). It wraps
 * the original instance and forwards everything else to it, including resolving and
 * streaming, so other mods' custom sound instances keep working. Only used for non-ticking
 * sounds nobody holds on to (see {@link SoundHooks}).
 */
final class CorruptedSoundInstance implements SoundInstance {
    private final SoundInstance delegate;
    private final float pitchFactor;
    private final float volumeFactor;

    CorruptedSoundInstance(SoundInstance delegate, float pitchFactor, float volumeFactor) {
        this.delegate = delegate;
        this.pitchFactor = pitchFactor;
        this.volumeFactor = volumeFactor;
    }

    @Override
    public ResourceLocation getLocation() {
        return delegate.getLocation();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        return delegate.resolve(manager);
    }

    @Override
    public Sound getSound() {
        return delegate.getSound();
    }

    @Override
    public SoundSource getSource() {
        return delegate.getSource();
    }

    @Override
    public boolean isLooping() {
        return delegate.isLooping();
    }

    @Override
    public boolean isRelative() {
        return delegate.isRelative();
    }

    @Override
    public int getDelay() {
        return delegate.getDelay();
    }

    @Override
    public float getVolume() {
        return delegate.getVolume() * volumeFactor;
    }

    @Override
    public float getPitch() {
        return delegate.getPitch() * pitchFactor;
    }

    @Override
    public double getX() {
        return delegate.getX();
    }

    @Override
    public double getY() {
        return delegate.getY();
    }

    @Override
    public double getZ() {
        return delegate.getZ();
    }

    @Override
    public Attenuation getAttenuation() {
        return delegate.getAttenuation();
    }

    @Override
    public boolean canStartSilent() {
        return delegate.canStartSilent();
    }

    @Override
    public boolean canPlaySound() {
        return delegate.canPlaySound();
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return delegate.getStream(soundBuffers, sound, looping);
    }

    @Override
    public String toString() {
        return "Corrupted[" + delegate + " x" + pitchFactor + "]";
    }
}
