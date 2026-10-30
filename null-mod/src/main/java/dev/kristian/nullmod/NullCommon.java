package dev.kristian.nullmod;

import dev.kristian.nullmod.autotest.ServerAutoTest;
import dev.kristian.nullmod.command.NullCommand;
import dev.kristian.nullmod.core.CorruptionManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus wiring shared by the dedicated server and the integrated server. */
public final class NullCommon {
    private NullCommon() {
    }

    static void init(IEventBus modBus) {
        IEventBus game = NeoForge.EVENT_BUS;
        game.addListener(NullCommon::onServerTick);
        game.addListener(NullCommon::onRegisterCommands);
        game.addListener(NullCommon::onPlayerLoggedIn);
        game.addListener(NullCommon::onPlayerLoggedOut);
        game.addListener(NullCommon::onServerStopping);
        NullSystems.init(modBus, game);
        ServerAutoTest.init(game);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        CorruptionManager.tick(event.getServer());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        NullCommand.register(event.getDispatcher());
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CorruptionManager.onPlayerJoin(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CorruptionManager.onPlayerLeave(player);
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        CorruptionManager.onServerStopping(event.getServer());
    }
}
