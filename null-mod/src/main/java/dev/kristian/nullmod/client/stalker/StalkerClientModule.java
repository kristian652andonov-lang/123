package dev.kristian.nullmod.client.stalker;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.entity.stalker.StalkerEntity;
import dev.kristian.nullmod.entity.stalker.StalkerModule;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client side of the figure: its model layer and renderer, and the per-entity client hook
 * (animation smoothing and the sounds of its body). The NULL stage's runner uses the same model
 * through {@link StalkerFigure}.
 */
public final class StalkerClientModule {
    static final ModelLayerLocation LAYER = new ModelLayerLocation(NullMod.id("stalker"), "main");

    private StalkerClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        StalkerEntity.setClientHook(new StalkerClientHooks());
        modBus.addListener(StalkerClientModule::onRegisterLayers);
        modBus.addListener(StalkerClientModule::onRegisterRenderers);
    }

    private static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAYER, StalkerModelLayer::create);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(StalkerModule.STALKER.get(), StalkerRenderer::new);
    }
}
