package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.network.TransitionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client entry point of the audio corruption. */
public final class AudioClientModule {
    private static final int MAX_FAILURES = 20;
    private static int failures;

    private AudioClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        AudioEffects.register();
        gameBus.addListener(SoundHooks::onPlaySound);
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
        if (failures >= MAX_FAILURES) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            AudioCorruptionManager.tick();
            if (mc.level == null) {
                return;
            }
            RandomSource random = AudioCorruptionManager.random();
            PitchCorruption.tick();
            PhantomFootsteps.tick();
            PhantomWalkers.tick(mc);
            MusicCorruption.tick(mc, random);
            AmbientScheduler.tick(mc, random);
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("NULL audio tick failed ({} of {})", failures, MAX_FAILURES, e);
            if (failures >= MAX_FAILURES) {
                AudioCorruptionManager.disable();
            }
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AudioCorruptionManager.reset();
    }
}
