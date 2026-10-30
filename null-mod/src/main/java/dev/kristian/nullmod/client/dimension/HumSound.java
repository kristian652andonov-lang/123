package dev.kristian.nullmod.client.dimension;

import dev.kristian.nullmod.client.ClientLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * The hum of the NULL dimension: a non-positional loop that fades in, follows the horror sound
 * volume, sinks during a geometry blackout (and drops in pitch with it) and fades out on request.
 */
final class HumSound extends AbstractTickableSoundInstance {
    private static final float LEVEL = 0.5F;
    private boolean fading;

    HumSound(SoundEvent sound) {
        super(sound, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.01F;
        this.pitch = 1.0F;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    /** It starts almost silent and fades in. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    void fadeOut() {
        fading = true;
    }

    void stopNow() {
        stop();
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().level == null) {
            stop();
            return;
        }
        float dark = DimensionAtmosphere.blackoutLevel(0.0F);
        float goal = fading ? 0.0F : LEVEL * ClientLimits.audio() * (1.0F - 0.85F * dark);
        volume += (goal - volume) * (fading ? 0.08F : 0.03F);
        pitch = 1.0F - 0.12F * dark;
        if (fading && volume < 0.004F) {
            stop();
        }
    }
}
