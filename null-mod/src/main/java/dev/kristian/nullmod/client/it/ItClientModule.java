package dev.kristian.nullmod.client.it;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.client.variant.ClientMobVariants;
import dev.kristian.nullmod.entity.it.ItEntity;
import dev.kristian.nullmod.entity.it.ItModule;
import dev.kristian.nullmod.entity.variant.MobVariant;
import dev.kristian.nullmod.network.MobVariantPayload;
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
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Client side of IT: the model layer and renderer, the per-entity client hook (animation state,
 * footfalls, voice), the victim's view (camera, shakes, vignette, the black at the end), the
 * swallowed bodies, the {@code it.howl} / {@code it.kill} effects, its bugged-out look (every IT
 * is a corrupted mob on the client, see {@link #corrupt}) and its eyes glowing through the night
 * from far away ({@link ItEyes}).
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
        gameBus.addListener(ItClientModule::onEntityJoin);
        gameBus.addListener(ItEyes::onRenderStage);
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
        ItEyes.reset();
    }

    /** IT arrived in the client level: it is a corrupted mob here, and its eyes are drawn from now on. */
    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof ItEntity it) {
            corrupt(it);
            ItEyes.add(it);
        }
    }

    /**
     * Makes IT a corrupted mob for this client alone, for good: the variant layer on every living
     * renderer ({@code MobVariantClientModule}) then draws the bugged-out look over it, and
     * forgets it again by itself when IT leaves the level. The seed comes from IT's UUID, so each
     * IT breaks its own way and the same IT always breaks the same way.
     */
    static void corrupt(ItEntity it) {
        long seed = it.getUUID().getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ it.getUUID().getLeastSignificantBits();
        ClientMobVariants.accept(new MobVariantPayload(it.getId(), MobVariant.CORRUPTED.id(), seed, 1.0F));
    }
}
