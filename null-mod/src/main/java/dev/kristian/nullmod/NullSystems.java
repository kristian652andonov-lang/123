package dev.kristian.nullmod;

import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.gametest.CoreGameTests;
import dev.kristian.nullmod.world.BuildProtection;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The list of NULL's server-side systems, in tick order, and the place where each system's
 * registries and random events are hooked up.
 */
final class NullSystems {
    private NullSystems() {
    }

    static void init(IEventBus modBus, IEventBus gameBus) {
        BuildProtection.init(gameBus);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(CoreGameTests.class));

        CorruptionManager.registerSystem(RandomEventManager.INSTANCE);
    }
}
