package dev.kristian.nullmod.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A server-side part of NULL that is driven by {@link CorruptionManager}. Systems are ticked
 * every server tick in registration order, even while NULL is paused (check
 * {@link CorruptionContext#paused()}), and should spread expensive work over many ticks.
 */
public interface CorruptionSystem {
    /** Short stable name, used for logging and as the key of {@link CorruptionData#systemTag}. */
    String name();

    void tick(MinecraftServer server, CorruptionContext context);

    /** Called after the stage changed, in either direction. */
    default void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
    }

    /** Called when a player joins, after the stage has been synced to them. */
    default void onPlayerJoin(ServerPlayer player, CorruptionContext context) {
    }

    default void onPlayerLeave(ServerPlayer player) {
    }

    /** Called by /null reset, before the stored system data is cleared. */
    default void onReset(MinecraftServer server) {
    }

    default void onServerStopping(MinecraftServer server) {
    }
}
