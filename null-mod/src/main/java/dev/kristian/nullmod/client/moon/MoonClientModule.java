package dev.kristian.nullmod.client.moon;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.moon.MoonCorruptionManager;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Client entry point of the Shattering Moon. */
public final class MoonClientModule {
    private static final int MAX_FAILURES = 10;
    private static int failures;
    @Nullable
    private static ClientLevel lastLevel;

    private MoonClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        ClientEffects.register(MoonCorruptionManager.EFFECT_CRACK, MoonEffects::crack);
        ClientEffects.register(MoonCorruptionManager.EFFECT_PIECE, MoonEffects::piece);
        ClientEffects.register(MoonCorruptionManager.EFFECT_DISTORT, MoonEffects::distort);
        ClientEffects.register(MoonCorruptionManager.EFFECT_SHATTER, MoonEffects::shatter);
        ClientEffects.register(MoonCorruptionManager.EFFECT_GONE, MoonEffects::gone);
        gameBus.addListener(MoonClientModule::onClientTick);
        gameBus.addListener(MoonSkyRenderer::onRenderLevelStage);
        gameBus.addListener(MoonClientModule::onLoggingOut);
        modBus.addListener(MoonClientModule::onRegisterReloadListeners);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (failures >= MAX_FAILURES) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != lastLevel) {
                lastLevel = mc.level;
                MoonEffects.reset();
            }
            MoonTextureManager.tick();
            MoonEffects.tick();
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("NULL moon tick failed ({} of {})", failures, MAX_FAILURES, e);
            if (failures >= MAX_FAILURES) {
                MoonEffects.reset();
                MoonTextureManager.restore();
            }
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MoonEffects.reset();
        MoonTextureManager.restore();
        lastLevel = null;
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(MoonTextureManager.RELOAD_LISTENER);
    }
}
