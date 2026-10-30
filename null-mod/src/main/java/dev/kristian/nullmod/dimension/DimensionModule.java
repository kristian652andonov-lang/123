package dev.kristian.nullmod.dimension;

import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.gametest.DimensionGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Common entry point of the NULL dimension: the chunk generator type, the server-side manager
 * (transition, respawning, decay), the dimension's random events and its GameTests. The
 * dimension itself, its type and its biome are data in {@code data/null/}.
 */
public final class DimensionModule {
    private DimensionModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        NullWorldgen.CHUNK_GENERATORS.register(modBus);
        CorruptionManager.registerSystem(NullDimensionManager.INSTANCE);
        DimensionEvents.register();
        gameBus.addListener(NullDimensionManager::onRespawnPosition);
        gameBus.addListener(NullDimensionManager::onRespawn);
        DimensionSelfCheck.init(gameBus);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(DimensionGameTests.class));
    }
}
