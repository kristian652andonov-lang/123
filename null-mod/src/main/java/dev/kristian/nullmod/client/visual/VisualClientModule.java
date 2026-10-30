package dev.kristian.nullmod.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client entry point of the visual distortion system: camera hooks, the post-processing pass,
 * the overlay layer, sky / chunk / silhouette rendering, client block glitches and the handlers
 * of every {@code visual.*} and {@code block.*} effect.
 */
public final class VisualClientModule {
    private static ClientLevel lastLevel;

    private VisualClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(VisualClientModule::onRegisterGuiLayers);
        modBus.addListener(VisualClientModule::onRegisterReloadListeners);

        gameBus.addListener(VisualClientModule::onRenderFrame);
        gameBus.addListener(VisualClientModule::onClientTick);
        gameBus.addListener(VisualClientModule::onLoggingOut);
        gameBus.addListener(VisualClientModule::onRenderLevelStage);
        gameBus.addListener(CameraEffects::onCameraAngles);
        gameBus.addListener(CameraEffects::onComputeFov);
        gameBus.addListener(CameraEffects::onFogColor);
        gameBus.addListener(CameraEffects::onRenderFog);
        // First thing in the HUD pass, before anyone draws HUD elements that must stay sharp.
        gameBus.addListener(EventPriority.HIGHEST, CorruptionPostEffect::onRenderGuiPre);
        // The transition fade goes over everything else that is drawn.
        gameBus.addListener(EventPriority.LOWEST, VisualOverlayLayer::onRenderGuiPost);
        gameBus.addListener(EventPriority.LOWEST, VisualOverlayLayer::onRenderScreenPost);
        gameBus.addListener(ClientBlockGlitchManager::onChunkUnload);

        VisualEffectHandlers.register();
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(VisualOverlayLayer.ID, VisualOverlayLayer::render);
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> CorruptionPostEffect.onResourcesReloaded());
    }

    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        VisualDistortionManager.onRenderFrame();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            if (lastLevel != null && level != null) {
                // Dimension change: nothing may linger from the world we just left.
                VisualDistortionManager.onLevelChanged();
            }
            lastLevel = level;
        }
        VisualDistortionManager.clientTick();
        ClientBlockGlitchManager.clientTick();
        SilhouetteRenderer.clientTick();
        FakeDisconnectScreen.clientTick();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        VisualDistortionManager.reset();
        CorruptionPostEffect.close();
        lastLevel = null;
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            SkyEffects.onRenderStage(event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            SilhouetteRenderer.onRenderStage(event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            ChunkFlickerRenderer.onRenderStage(event);
        }
    }
}
