package dev.kristian.nullmod.client.fakeplayer;

import dev.kristian.nullmod.client.visitor.PlayerLikeRenderer;
import dev.kristian.nullmod.entity.fakeplayer.FakePlayerEntity;
import dev.kristian.nullmod.entity.fakeplayer.FakePlayerModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client side of the simulated players: their bodies are drawn exactly like players, and the
 * player list shows them while they exist.
 */
public final class FakePlayerClientModule {
    private FakePlayerClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(FakePlayerClientModule::onRegisterRenderers);
        gameBus.addListener(FakeTabList::onRenderGuiLayer);
        gameBus.addListener(FakePlayerClientModule::onLoggingOut);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FakePlayerModule.FAKE_PLAYER.get(), context -> new PlayerLikeRenderer<FakePlayerEntity>(context));
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FakeTabList.reset();
    }
}
