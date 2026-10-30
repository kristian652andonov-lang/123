package dev.kristian.nullmod.entity.fakeplayer;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Stage 5 simulated players: the body entity type, the {@link FakePlayerManager} system that
 * keeps the simulated player list, and the fake_player_* events.
 */
public final class FakePlayerModule {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, NullMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<FakePlayerEntity>> FAKE_PLAYER = ENTITY_TYPES.register("fake_player",
            () -> EntityType.Builder.<FakePlayerEntity>of(FakePlayerEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .noSave()
                    .build("fake_player"));

    private FakePlayerModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(FakePlayerModule::onEntityAttributes);
        CorruptionManager.registerSystem(FakePlayerManager.INSTANCE);
        FakePlayerEvents.register();
    }

    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(FAKE_PLAYER.get(), FakePlayerEntity.createAttributes().build());
    }
}
