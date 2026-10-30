package dev.kristian.nullmod.audio;

import net.neoforged.bus.api.IEventBus;

/** Common entry point of the audio corruption: its random events (the sounds are all client side). */
public final class AudioModule {
    private AudioModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        AudioEvents.register();
    }
}
