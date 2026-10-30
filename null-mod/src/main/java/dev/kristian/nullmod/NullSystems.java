package dev.kristian.nullmod;

import dev.kristian.nullmod.audio.AudioModule;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.entity.fakeplayer.FakePlayerModule;
import dev.kristian.nullmod.entity.visitor.VisitorModule;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.gametest.CoreGameTests;
import dev.kristian.nullmod.moon.MoonModule;
import dev.kristian.nullmod.visual.VisualModule;
import dev.kristian.nullmod.world.BuildProtection;
import dev.kristian.nullmod.world.WorldModule;
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

        // Systems tick in this order.
        CorruptionManager.registerSystem(RandomEventManager.INSTANCE);
        WorldModule.init(modBus, gameBus);
        MoonModule.init(modBus, gameBus);
        VisitorModule.init(modBus, gameBus);
        FakePlayerModule.init(modBus, gameBus);

        // Event-only modules (their effects run on the client).
        VisualModule.init(modBus, gameBus);
        AudioModule.init(modBus, gameBus);
    }
}
