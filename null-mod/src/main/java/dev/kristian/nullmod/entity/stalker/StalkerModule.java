package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.gametest.StalkerGameTests;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The figure of Stages 1-4 ("stalkers"): its entity type and attributes, its sounds, the
 * {@link StalkerManager} system, the {@code stalker_*} events, the chunk-unload hook of
 * {@link TemporaryChanges} and the GameTests. Server and client; nothing here touches client
 * classes (the client side is {@code client.stalker.StalkerClientModule}).
 */
public final class StalkerModule {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, NullMod.MODID);

    /**
     * A player-sized hitbox under a model about 2.4 blocks tall (it stoops under anything lower).
     * Tracked as far as a player, never saved with the world, untouched by fire.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<StalkerEntity>> STALKER = ENTITY_TYPES.register("stalker",
            () -> EntityType.Builder.<StalkerEntity>of(StalkerEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .fireImmune()
                    .noSave()
                    .build("stalker"));

    private StalkerModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        ENTITY_TYPES.register(modBus);
        StalkerSounds.SOUND_EVENTS.register(modBus);
        modBus.addListener(StalkerModule::onEntityAttributes);
        modBus.addListener(StalkerModule::onRegisterGameTests);
        gameBus.addListener(StalkerModule::onMobGriefing);
        gameBus.addListener(StalkerModule::onChunkUnload);
        CorruptionManager.registerSystem(StalkerManager.INSTANCE);
        StalkerEvents.register();
    }

    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(STALKER.get(), StalkerEntity.createAttributes().build());
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(StalkerGameTests.class);
    }

    /** It never breaks, tramples or changes anything by vanilla means, whatever the mobGriefing rule says. */
    private static void onMobGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity() instanceof StalkerEntity) {
            event.setCanGrief(false);
        }
    }

    /** A chunk is about to be saved and dropped: anything the figure changed in it is put back first. */
    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            TemporaryChanges.INSTANCE.onChunkUnload(level, chunk);
        }
    }
}
