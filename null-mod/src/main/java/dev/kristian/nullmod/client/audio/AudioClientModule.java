package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.network.TransitionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client entry point of the audio corruption. The music ({@link NullMusic}) ticks on its own, with
 * its own failure count: nothing that goes wrong in the horror sounds may take the music with it.
 */
public final class AudioClientModule {
    private static final int MAX_FAILURES = 20;
    private static int failures;
    private static int musicFailures;

    private AudioClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        AudioEffects.register();
        gameBus.addListener(SoundHooks::onPlaySound);
        gameBus.addListener(NullMusic::onSelectMusic);
        gameBus.addListener(AudioClientModule::onClientTick);
        gameBus.addListener(AudioClientModule::onLoggingOut);
        ClientCorruptionState.addStageListener(AmbientScheduler::onStageChanged);
        ClientCorruptionState.addTransitionListener(payload -> {
            // Safety net: an aborted transition must never leave the game silent.
            if (payload.phase() == TransitionPayload.PHASE_CLEAR) {
                AudioCorruptionManager.setTransitionSilence(false);
            }
        });
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        tickHorrorSounds(mc);
        tickMusic(mc);
    }

    private static void tickHorrorSounds(Minecraft mc) {
        if (failures >= MAX_FAILURES) {
            return;
        }
        try {
            AudioCorruptionManager.tick();
            if (mc.level == null) {
                return;
            }
            RandomSource random = AudioCorruptionManager.random();
            PitchCorruption.tick();
            PhantomFootsteps.tick();
            PhantomWalkers.tick(mc);
            AmbientScheduler.tick(mc, random);
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("NULL audio tick failed ({} of {})", failures, MAX_FAILURES, e);
            if (failures >= MAX_FAILURES) {
                AudioCorruptionManager.disable();
            }
        }
    }

    private static void tickMusic(Minecraft mc) {
        if (musicFailures >= MAX_FAILURES || mc.level == null) {
            return;
        }
        try {
            NullMusic.tick(mc, AudioCorruptionManager.random());
        } catch (RuntimeException e) {
            musicFailures++;
            NullMod.LOGGER.error("NULL music tick failed ({} of {})", musicFailures, MAX_FAILURES, e);
            if (musicFailures >= MAX_FAILURES) {
                NullMusic.reset();
            }
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AudioCorruptionManager.reset();
    }
}
