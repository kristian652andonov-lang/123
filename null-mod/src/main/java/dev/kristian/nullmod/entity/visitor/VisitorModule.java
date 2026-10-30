package dev.kristian.nullmod.entity.visitor;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.entity.PlayerLikeEntity;
import dev.kristian.nullmod.gametest.VisitorGameTests;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Stage 4 Visitors: the entity type, its attributes, the {@link VisitorManager} system, the
 * visitor_* events and the GameTests for this whole area (visitors and simulated players).
 */
public final class VisitorModule {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, NullMod.MODID);

    /** Same size, eye height, tracking and update rate as a player; never saved with the world. */
    public static final DeferredHolder<EntityType<?>, EntityType<VisitorEntity>> VISITOR = ENTITY_TYPES.register("visitor",
            () -> EntityType.Builder.<VisitorEntity>of(VisitorEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .noSave()
                    .build("visitor"));

    private VisitorModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(VisitorModule::onEntityAttributes);
        modBus.addListener(VisitorModule::onRegisterGameTests);
        gameBus.addListener(VisitorModule::onMobGriefing);
        CorruptionManager.registerSystem(VisitorManager.INSTANCE);
        VisitorEvents.register();
    }

    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(VISITOR.get(), PlayerLikeEntity.createAttributes().build());
    }

    /**
     * Visitors and simulated player bodies never change the world, whatever the mobGriefing
     * rule says: no trampled farmland, no broken turtle eggs, nothing another mod asks about.
     */
    private static void onMobGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity() instanceof PlayerLikeEntity) {
            event.setCanGrief(false);
        }
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(VisitorGameTests.class);
    }
}
