package dev.kristian.nullmod.chat;

import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.gametest.ChatGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Common entry point of the corrupted chat: the {@link ChatCorruptionManager} system, the chat
 * random events, the listeners that let the chat react to players, and the GameTests. Server
 * logic only; there is nothing to set up on the client (every line is a vanilla system message).
 */
public final class ChatModule {
    private ChatModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        CorruptionManager.registerSystem(ChatCorruptionManager.INSTANCE);
        ChatEvents.register();
        ChatReactions.register(gameBus);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(ChatGameTests.class));
    }
}
