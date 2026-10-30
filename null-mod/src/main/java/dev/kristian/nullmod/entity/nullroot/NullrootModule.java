package dev.kristian.nullmod.entity.nullroot;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.gametest.NullrootGameTests;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Stage 3 NULLROOT: the organism entity type and its attributes, the {@link NullrootManager}
 * system that grows them, the capture bookkeeping ({@link NullrootCaptures}) on the game bus,
 * the nullroot_* events and the GameTests. Server and client; nothing here touches client
 * classes (the client side is {@code client.nullroot.NullrootClientModule}).
 */
public final class NullrootModule {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, NullMod.MODID);

    /**
     * One type for every variant; the variant (synced) decides the size. Never spawns naturally:
     * only {@link NullrootManager}, the events and commands create them.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<NullrootEntity>> NULLROOT = ENTITY_TYPES.register("nullroot",
            () -> EntityType.Builder.<NullrootEntity>of(NullrootEntity::new, MobCategory.MONSTER)
                    .sized(NullrootVariant.TENDRIL.width(), NullrootVariant.TENDRIL.height())
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build("nullroot"));

    private NullrootModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(NullrootModule::onEntityAttributes);
        modBus.addListener(NullrootModule::onRegisterGameTests);

        CorruptionManager.registerSystem(NullrootManager.INSTANCE);
        NullrootEvents.register();

        gameBus.addListener(NullrootCaptures::onServerTick);
        gameBus.addListener(NullrootCaptures::onLivingDeath);
        gameBus.addListener(NullrootCaptures::onChangedDimension);
        gameBus.addListener(NullrootCaptures::onRespawn);
        gameBus.addListener(NullrootCaptures::onLoggedIn);
        gameBus.addListener(NullrootCaptures::onLoggedOut);
        gameBus.addListener(NullrootCaptures::onServerStopping);
    }

    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(NULLROOT.get(), NullrootEntity.createAttributes().build());
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(NullrootGameTests.class);
    }
}
