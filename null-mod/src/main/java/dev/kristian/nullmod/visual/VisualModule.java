package dev.kristian.nullmod.visual;

import net.neoforged.bus.api.IEventBus;

/**
 * Common (client and dedicated server) entry point of the visual distortion system. It only
 * registers the random events; everything that draws lives in
 * {@code dev.kristian.nullmod.client.visual.VisualClientModule}.
 */
public final class VisualModule {
    private VisualModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        VisualEvents.register();
    }
}
