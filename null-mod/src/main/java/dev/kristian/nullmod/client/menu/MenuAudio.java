package dev.kristian.nullmod.client.menu;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * The menu's own sounds, played as plain sound instances (never through the in-game audio
 * corruption system): a quiet ambience loop that fades in and out, and one-shot interface
 * sounds. Silent unless both MENU_SOUNDS and HORROR_SOUNDS are on.
 */
final class MenuAudio {
    private static final int RETRY_TICKS = 40;
    private static final long HOVER_GAP_MS = 90L;

    private AmbienceLoop loop;
    private boolean wanted;
    private float volume;
    private float pitch;
    private float duck = 1.0F;
    private int retry;
    private long lastHoverMs;
    private boolean warned;

    /** True when the menu may make horror sounds at all. */
    static boolean enabled() {
        return NullClientSettings.get(NullClientSettings.MENU_SOUNDS)
                && NullClientSettings.get(NullClientSettings.HORROR_SOUNDS)
                && ClientLimits.audio() > 0.001F;
    }

    private static float scale() {
        return MenuMath.clamp01(ClientLimits.audio());
    }

    /** Starts (or revives) the ambience loop for the menu being shown. */
    void start(MenuMood mood) {
        wanted = enabled();
        duck = 1.0F;
        if (!wanted) {
            stopLoop(10);
            return;
        }
        volume = mood.ambienceVolume * scale();
        pitch = mood.ambiencePitch;
        if (loop != null && !loop.isStopped() && sounds().isActive(loop)) {
            loop.fadeTo(volume, 50);
        } else {
            play();
        }
        retry = RETRY_TICKS;
    }

    /** Called every client tick while the menu is open. */
    void tick() {
        if (!wanted) {
            return;
        }
        if (loop == null || loop.isStopped() || !sounds().isActive(loop)) {
            if (--retry <= 0) {
                retry = RETRY_TICKS;
                play();
            }
        }
    }

    /** Fades the loop out; it stops itself once silent. */
    void stop() {
        wanted = false;
        stopLoop(16);
    }

    /** Temporarily lowers the ambience (1 = normal). */
    void duck(float factor, int ticks) {
        duck = MenuMath.clamp01(factor);
        if (loop != null && !loop.isStopped()) {
            loop.fadeTo(volume * duck, ticks);
        }
    }

    void hover() {
        if (!wanted) {
            return;
        }
        long now = net.minecraft.Util.getMillis();
        if (now - lastHoverMs < HOVER_GAP_MS) {
            return;
        }
        lastHoverMs = now;
        playUi(NullSounds.MENU_HOVER.get(), 0.22F * (0.5F + 0.5F * scale()), 1.0F);
    }

    /** Button press sound; returns false when the menu sounds are off (callers then use the vanilla click). */
    boolean click() {
        if (!enabled()) {
            return false;
        }
        playUi(NullSounds.MENU_CLICK.get(), 0.5F * (0.5F + 0.5F * scale()), 1.0F);
        return true;
    }

    void whisper(Random random) {
        if (!wanted || !NullClientSettings.get(NullClientSettings.WHISPERS)) {
            return;
        }
        playUi(NullSounds.MENU_WHISPER.get(), MenuMath.range(random, 0.22F, 0.42F) * scale(), MenuMath.range(random, 0.86F, 1.08F));
    }

    void waiting() {
        if (!wanted) {
            return;
        }
        playUi(NullSounds.MENU_WAITING.get(), 0.75F * scale(), 1.0F);
    }

    private void play() {
        stopLoop(4);
        try {
            AmbienceLoop next = new AmbienceLoop(NullSounds.MENU_AMBIENCE.get(), volume * duck, pitch);
            sounds().play(next);
            loop = next;
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                NullMod.LOGGER.warn("NULL menu ambience could not start", e);
            }
        }
    }

    private void stopLoop(int ticks) {
        if (loop != null) {
            loop.fadeOutAndStop(ticks);
            loop = null;
        }
    }

    private void playUi(SoundEvent sound, float volume, float pitch) {
        if (volume <= 0.001F) {
            return;
        }
        try {
            // forUI(sound, pitch, volume)
            sounds().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                NullMod.LOGGER.warn("NULL menu sound could not play", e);
            }
        }
    }

    private static SoundManager sounds() {
        return Minecraft.getInstance().getSoundManager();
    }

    /** Non-positional looping ambience whose volume glides towards a target every tick. */
    static final class AmbienceLoop extends AbstractTickableSoundInstance {
        private float target;
        private float step;
        private boolean stopping;

        AmbienceLoop(SoundEvent sound, float target, float pitch) {
            super(sound, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0F;
            this.pitch = pitch;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.target = target;
            this.step = Math.max(0.002F, target / 70.0F);
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        void fadeTo(float newTarget, int ticks) {
            stopping = false;
            target = Math.max(0.0F, newTarget);
            step = Math.max(0.001F, Math.abs(target - volume) / Math.max(1, ticks));
        }

        void fadeOutAndStop(int ticks) {
            stopping = true;
            target = 0.0F;
            step = Math.max(0.002F, volume / Math.max(1, ticks));
        }

        @Override
        public void tick() {
            volume = MenuMath.approach(volume, target, step);
            if (stopping && volume <= 0.0005F) {
                stop();
            }
        }
    }
}
