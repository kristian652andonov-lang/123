package dev.kristian.nullmod.client.nullroot;

import dev.kristian.nullmod.entity.nullroot.NullrootEntity;
import dev.kristian.nullmod.entity.nullroot.NullrootModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Client side of NULLROOT: the models and renderer, the per-organism client hook (animation,
 * sounds), what a captured player sees and hears, and the glimpse and pulse effects.
 */
public final class NullrootClientModule {
    private NullrootClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        NullrootEntity.setClientHook(new NullrootClientHooks());
        modBus.addListener(NullrootModels::register);
        modBus.addListener(NullrootClientModule::onRegisterRenderers);
        modBus.addListener(NullrootClientModule::onRegisterGuiLayers);
        gameBus.addListener(NullrootClientModule::onClientTick);
        gameBus.addListener(NullrootClientModule::onLoggingOut);
        NullrootClientEffects.register();
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(NullrootModule.NULLROOT.get(), NullrootRenderer::new);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // In the world rather than on the HUD: above the vignette and pumpkin, below crosshair and hotbar.
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, NullrootGlimpseLayer.ID, NullrootGlimpseLayer::render);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        NullrootCaptureEffects.clientTick();
        NullrootGlimpseLayer.clientTick();
        NullrootClientEffects.clientTick();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        NullrootCaptureEffects.reset();
        NullrootGlimpseLayer.reset();
        NullrootClientEffects.reset();
    }
}
