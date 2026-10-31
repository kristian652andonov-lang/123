package dev.kristian.nullmod.client.menu;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.MusicWarp;
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
import net.minecraft.util.RandomSource;

/**
 * The menu's own sounds, played as plain sound instances (never through the in-game audio
 * corruption system): the menu's music, a quiet loop that fades in and out, and one-shot interface
 * sounds. The music always plays, like NULL's music in the world: it is on the master source, so no
 * setting and no category slider switches it off and only the master volume changes it. From the
 * highest stage this computer has seen it is bent by {@link MusicWarp}, like the music in the world.
 * The interface sounds are silent unless both MENU_SOUNDS and HORROR_SOUNDS are on.
 */
final class MenuAudio {
    private static final int RETRY_TICKS = 40;
    private static final long HOVER_GAP_MS = 90L;

    private AmbienceLoop loop;
    private boolean wanted;
    private float volume;
    private float pitch;
    private float warpLevel;
    private float duck = 1.0F;
    private int retry;
    private long lastHoverMs;
    private boolean warned;

    /** True when the menu may make interface sounds (its music does not depend on it). */
    static boolean enabled() {
        return NullClientSettings.get(NullClientSettings.MENU_SOUNDS)
                && NullClientSettings.get(NullClientSettings.HORROR_SOUNDS)
                && ClientLimits.audio() > 0.001F;
    }

    private static float scale() {
        return MenuMath.clamp01(ClientLimits.audio());
    }

    /** Starts (or revives) the menu's music for the menu being shown. */
    void start(MenuMood mood) {
        wanted = true;
        duck = 1.0F;
        volume = mood.ambienceVolume;
        pitch = mood.ambiencePitch;
        warpLevel = MusicWarp.forMenu(mood.stage);
        if (loop != null && !loop.isStopped() && sounds().isActive(loop)) {
            loop.fadeTo(volume, 50);
            loop.warpTo(warpLevel);
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
        if (!wanted || !enabled()) {
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
        if (!wanted || !enabled() || !NullClientSettings.get(NullClientSettings.WHISPERS)) {
            return;
        }
        playUi(NullSounds.MENU_WHISPER.get(), MenuMath.range(random, 0.22F, 0.42F) * scale(), MenuMath.range(random, 0.86F, 1.08F));
    }

    void waiting() {
        if (!wanted || !enabled()) {
            return;
        }
        playUi(NullSounds.MENU_WAITING.get(), 0.75F * scale(), 1.0F);
    }

    private void play() {
        stopLoop(4);
        try {
            AmbienceLoop next = new AmbienceLoop(NullSounds.MENU_AMBIENCE.get(), volume * duck, pitch, warpLevel);
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

    /**
     * The menu's music: non-positional, looping, on the master source, its volume gliding towards a
     * target every tick, then bent by the warp (the fades and the warp are kept apart).
     */
    static final class AmbienceLoop extends AbstractTickableSoundInstance {
        private final MusicWarp warp;
        private final float basePitch;
        /** Where the fades have got to, before the warp. */
        private float level;
        private float target;
        private float step;
        private boolean stopping;

        AmbienceLoop(SoundEvent sound, float target, float pitch, float warpLevel) {
            super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.warp = new MusicWarp(RandomSource.create(), warpLevel);
            this.basePitch = pitch;
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

        void warpTo(float warpLevel) {
            warp.setLevel(warpLevel);
        }

        void fadeTo(float newTarget, int ticks) {
            stopping = false;
            target = Math.max(0.0F, newTarget);
            step = Math.max(0.001F, Math.abs(target - level) / Math.max(1, ticks));
        }

        void fadeOutAndStop(int ticks) {
            stopping = true;
            target = 0.0F;
            step = Math.max(0.002F, level / Math.max(1, ticks));
        }

        @Override
        public void tick() {
            level = MenuMath.approach(level, target, step);
            if (stopping && level <= 0.0005F) {
                stop();
            }
            float gain = 1.0F;
            float bend = 1.0F;
            if (warp.level() > 0.0F) {
                warp.tick();
                gain = warp.volume();
                bend = warp.pitch();
            }
            volume = level * gain;
            pitch = basePitch * bend;
        }
    }
}
