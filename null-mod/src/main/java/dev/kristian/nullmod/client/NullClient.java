package dev.kristian.nullmod.client;

import dev.kristian.nullmod.network.NullNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point, called from the mod constructor on the physical client. */
public final class NullClient {
    private NullClient() {
    }

    public static void init(IEventBus modBus, ModContainer container) {
        NullNetworking.setClientSink(new ClientPacketHandler());
        IEventBus game = NeoForge.EVENT_BUS;
        game.addListener(NullClient::onLoggingOut);
        NullClientSystems.init(modBus, game, container);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCorruptionState.reset();
    }
}
