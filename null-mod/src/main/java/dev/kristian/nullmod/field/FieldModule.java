package dev.kristian.nullmod.field;

import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.gametest.FieldGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Common entry point of the field (Stage 6, HARVEST): the chunk generator type, the field's sounds,
 * the server-side manager (cycle, transitions, respawning), its event listeners and GameTests. The
 * dimension itself, its type and its biome are data in {@code data/null/}.
 */
public final class FieldModule {
    private FieldModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        FieldWorldgen.CHUNK_GENERATORS.register(modBus);
        FieldSounds.SOUNDS.register(modBus);
        CorruptionManager.registerSystem(FieldManager.INSTANCE);
        // The transitions belong to themselves, and once the sun breaks the field has its own horror.
        RandomEventManager.INSTANCE.addQuietCondition(FieldManager.INSTANCE::quiet);
        gameBus.addListener(FieldManager::onRespawnPosition);
        gameBus.addListener(FieldManager::onRespawn);
        gameBus.addListener(FieldManager::onUseBlock);
        gameBus.addListener(FieldManager::onFarmlandTrample);
        FieldSelfCheck.init(gameBus);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(FieldGameTests.class));
    }
}
