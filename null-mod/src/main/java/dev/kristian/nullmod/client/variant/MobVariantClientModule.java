package dev.kristian.nullmod.client.variant;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.effect.ClientEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client side of corrupted and parasite mobs: the layer on every living entity renderer, the
 * glitches before a variant mob is drawn, the generated textures, the {@code variant.parasite_burst}
 * effect, faint sounds near variant mobs, and keeping {@link ClientMobVariants} in step with the
 * level (mobs leaving, level changes, logout).
 */
public final class MobVariantClientModule {
    /** Ticks between sweeps for variant entries whose mob never arrived. */
    private static final int SWEEP_EVERY = 200;
    private static int sweepTicks;

    private MobVariantClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(MobVariantClientModule::onAddLayers);
        modBus.addListener(MobVariantClientModule::onRegisterReloadListeners);
        // Explicit event class: the listener's parameter is generic. Low priority, so a mob another
        // listener hides is not touched.
        gameBus.addListener(EventPriority.LOW, false, RenderLivingEvent.Pre.class, VariantRendering::onRenderLivingPre);
        gameBus.addListener(MobVariantClientModule::onRenderFrame);
        gameBus.addListener(MobVariantClientModule::onClientTick);
        gameBus.addListener(ParasiteBursts::onRenderStage);
        gameBus.addListener(MobVariantClientModule::onEntityLeave);
        gameBus.addListener(MobVariantClientModule::onLevelUnload);
        gameBus.addListener(MobVariantClientModule::onLoggingOut);
        ClientEffects.register(ParasiteBursts.EFFECT, ParasiteBursts::onEffect);
    }

    /** Every living entity renderer, vanilla or modded, gets the variant layer (players never become variants). */
    private static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        int added = 0;
        for (EntityType<?> type : event.getEntityTypes()) {
            EntityRenderer<?> renderer = event.getRenderer(type);
            if (renderer instanceof LivingEntityRenderer<?, ?> living) {
                attach(living);
                added++;
            }
        }
        NullMod.LOGGER.debug("NULL added its variant layer to {} entity renderers", added);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void attach(LivingEntityRenderer renderer) {
        renderer.addLayer(new MobVariantLayer(renderer));
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> VariantTextures.releaseAll());
    }

    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        VariantTextures.onRenderFrame();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ParasiteBursts.clientTick();
        VariantAmbience.clientTick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && ++sweepTicks >= SWEEP_EVERY) {
            sweepTicks = 0;
            ClientMobVariants.sweep(mc.level);
        }
    }

    /** A mob left the client's level (killed, unloaded, out of range): forget it. */
    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientMobVariants.remove(event.getEntity().getId());
        }
    }

    /** Entity ids mean nothing in the next level (dimension change). */
    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientMobVariants.clear();
            ParasiteBursts.reset();
            VariantAmbience.reset();
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMobVariants.clear();
        ParasiteBursts.reset();
        VariantAmbience.reset();
        VariantTextures.releaseAll();
        sweepTicks = 0;
    }
}
