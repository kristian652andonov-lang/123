package dev.kristian.nullmod.entity.it;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.gametest.ItGameTests;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Stage 6 "IT": the entity type and its attributes, its sounds, the game-bus listeners
 * ({@link ItEvents}) and the GameTests. IT has no system of its own: the field summons it with
 * {@link ItEntity#summon} and watches {@link ItEntity#isHunting}. Server and client; nothing here
 * touches client classes (the client side is {@code client.it.ItClientModule}).
 */
public final class ItModule {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, NullMod.MODID);

    /**
     * Six blocks tall, far too wide for a door. Tracked from far away and updated every tick (it
     * moves fast); never saved with the world; fire cannot touch it.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ItEntity>> IT = ENTITY_TYPES.register("it",
            () -> EntityType.Builder.<ItEntity>of(ItEntity::new, MobCategory.MONSTER)
                    .sized(2.2F, 6.0F)
                    .eyeHeight(5.6F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .build("it"));

    private ItModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        ENTITY_TYPES.register(modBus);
        ItSounds.SOUND_EVENTS.register(modBus);
        modBus.addListener(ItModule::onEntityAttributes);
        modBus.addListener(ItModule::onRegisterGameTests);

        gameBus.addListener(ItEvents::onEntityMount);
        gameBus.addListener(EventPriority.LOWEST, ItEvents::onTravelToDimension);
        gameBus.addListener(ItEvents::onLoggedIn);
        gameBus.addListener(ItEvents::onLoggedOut);
        gameBus.addListener(ItEvents::onRespawn);
        gameBus.addListener(ItEvents::onChangedDimension);
        gameBus.addListener(ItEvents::onServerTick);
        gameBus.addListener(ItEvents::onServerStopping);
    }

    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(IT.get(), ItEntity.createAttributes().build());
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(ItGameTests.class);
    }
}
