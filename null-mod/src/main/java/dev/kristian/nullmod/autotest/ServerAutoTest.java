package dev.kristian.nullmod.autotest;

import dev.kristian.nullmod.NullMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Server half of the CI multiplayer test: when the JVM runs with {@code -Dnull.autotest}, every
 * joining player is made an operator so the scripted client can drive {@code /null}. Inactive
 * (never registered) in normal play.
 */
public final class ServerAutoTest {
    private ServerAutoTest() {
    }

    public static void init(IEventBus gameBus) {
        if (System.getProperty("null.autotest") == null) {
            return;
        }
        NullMod.LOGGER.info("NULL-AUTOTEST: server side enabled");
        gameBus.addListener(ServerAutoTest::onLogin);
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.server.isSingleplayer()) {
            player.server.getPlayerList().op(player.getGameProfile());
            NullMod.LOGGER.info("NULL-AUTOTEST: opped {}", player.getGameProfile().getName());
        }
    }
}
