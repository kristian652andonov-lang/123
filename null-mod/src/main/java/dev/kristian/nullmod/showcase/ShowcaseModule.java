package dev.kristian.nullmod.showcase;

import dev.kristian.nullmod.command.ShowcaseCommand;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.gametest.ShowcaseGameTests;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Common entry point of {@code /null showcase}: the tour ({@link ShowcaseManager}, a corruption
 * system), its commands (merged under {@code /null} by Brigadier) and its GameTests.
 */
public final class ShowcaseModule {
    private ShowcaseModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        CorruptionManager.registerSystem(ShowcaseManager.INSTANCE);
        RandomEventManager.INSTANCE.addQuietCondition(ShowcaseManager.INSTANCE::watching);
        gameBus.addListener((RegisterCommandsEvent event) -> ShowcaseCommand.register(event.getDispatcher()));
        // Before NULL's own stopping listener: the world's stage is put back while every system
        // can still react to it, before they forget their state.
        gameBus.addListener(EventPriority.HIGH, ShowcaseModule::onServerStopping);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(ShowcaseGameTests.class));
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ShowcaseManager.INSTANCE.finishNow(event.getServer(), "null.showcase.end.server");
    }
}
