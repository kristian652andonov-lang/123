package dev.kristian.nullmod.moon;

import dev.kristian.nullmod.core.CorruptionManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Common entry point of the Shattering Moon (server logic and its random events). */
public final class MoonModule {
    private MoonModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        CorruptionManager.registerSystem(MoonCorruptionManager.INSTANCE);
        MoonEvents.register();
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(MoonGameTests.class));
    }
}
