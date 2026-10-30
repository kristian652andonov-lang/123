package dev.kristian.nullmod.world;

import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.gametest.WorldGameTests;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * World corruption and corrupted structures: background terrain corruption and infection
 * ({@link ChunkCorruptionManager}), the budgeted block edit queue everything goes through
 * ({@link SafeBlockPlacer}), and the world and structure events ({@link WorldEvents}).
 * Server-side only; nothing here touches client classes.
 */
public final class WorldModule {
    private WorldModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        // Corruption plans its edits first; the placer carries them out in the same tick.
        CorruptionManager.registerSystem(ChunkCorruptionManager.INSTANCE);
        CorruptionManager.registerSystem(SafeBlockPlacer.INSTANCE);
        WorldEvents.register();
        gameBus.addListener(WorldModule::onLevelUnload);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(WorldGameTests.class));
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SafeBlockPlacer.INSTANCE.abortDimension(level);
        }
    }
}
