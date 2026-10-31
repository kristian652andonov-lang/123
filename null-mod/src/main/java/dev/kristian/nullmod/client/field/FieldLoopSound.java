package dev.kristian.nullmod.client.field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * One of the field's ambient loops (wind, the unease, the night): non-positional, it glides to the
 * volume {@link FieldAmbience} asks for (already scaled by the player's audio settings) and fades
 * out when told to. Played on the ambient source, so the game's own slider applies too.
 */
final class FieldLoopSound extends AbstractTickableSoundInstance {
    private float target;
    private boolean fading;

    FieldLoopSound(SoundEvent sound) {
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

    void setTarget(float volume) {
        target = Math.max(0.0F, Math.min(1.0F, volume));
    }

    void fadeOut() {
        fading = true;
    }

    boolean fading() {
        return fading;
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
        float goal = fading ? 0.0F : target;
        volume += (goal - volume) * (fading ? 0.06F : 0.035F);
        if (fading && volume < 0.004F) {
            stop();
        }
    }
}
