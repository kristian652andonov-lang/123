package dev.kristian.nullmod.client.it;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.entity.it.ItEntity;
import dev.kristian.nullmod.entity.it.ItModule;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Client side of IT: the model layer and renderer, the per-entity client hook (animation state,
 * footfalls, voice), the victim's view (camera, shakes, vignette, the black at the end), the
 * swallowed bodies, and the {@code it.howl} / {@code it.kill} effects.
 */
public final class ItClientModule {
    static final ModelLayerLocation LAYER = new ModelLayerLocation(NullMod.id("it"), "main");

    private ItClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        ItEntity.setClientHook(new ItClientHooks());
        modBus.addListener(ItClientModule::onRegisterLayers);
        modBus.addListener(ItClientModule::onRegisterRenderers);
        modBus.addListener(ItClientModule::onRegisterGuiLayers);
        gameBus.addListener(ItClientModule::onClientTick);
        gameBus.addListener(ItClientModule::onRenderFrame);
        gameBus.addListener(ItClientModule::onLoggingOut);
        // Explicit event class: the listener's parameter is generic.
        gameBus.addListener(EventPriority.NORMAL, false, RenderLivingEvent.Pre.class, ItBodies::onRenderLiving);
        ClientEffects.register(ItEntity.EFFECT_HOWL, ItClientHooks::onHowl);
        ClientEffects.register(ItEntity.EFFECT_KILL, ItKillView::onKillEffect);
    }

    private static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAYER, ItModelLayer::create);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ItModule.IT.get(), ItRenderer::new);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // The vignette lies over the world (below the hotbar); the black at the end covers everything.
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ItKillView.VIGNETTE_LAYER, ItKillView::renderVignette);
        event.registerAboveAll(ItKillView.FADE_LAYER, ItKillView::renderFade);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ItKillView.clientTick();
        ItBodies.clientTick();
    }

    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        ItKillView.onRenderFrame(event.getPartialTick());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ItKillView.reset();
        ItBodies.reset();
    }
}
