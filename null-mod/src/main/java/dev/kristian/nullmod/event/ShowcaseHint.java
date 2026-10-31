package dev.kristian.nullmod.event;

import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;

/**
 * The showcase hint while an event's action runs for {@code /null showcase}: everything it places
 * should end up where the watching player can see it. Event actions read it through
 * {@link EventContext#showcase()}; shared placement helpers that only get a player
 * ({@code world.LocationFinder}, NULLROOT, Visitors) ask {@link #active(ServerPlayer)}, so events
 * need no special cases of their own.
 * <p>
 * Server thread only: the hint is set for the duration of one action and cleared afterwards, even
 * when the action throws.
 */
public final class ShowcaseHint {
    @Nullable
    private static ServerPlayer viewer;

    private ShowcaseHint() {
    }

    /** True while a showcase event runs for this player. */
    public static boolean active(ServerPlayer player) {
        return viewer != null && viewer == player;
    }

    /** True while any showcase event runs. */
    public static boolean active() {
        return viewer != null;
    }

    /** Runs {@code action} with the hint set for {@code player} (server thread; nests safely). */
    public static boolean run(ServerPlayer player, BooleanSupplier action) {
        ServerPlayer previous = viewer;
        viewer = player;
        try {
            return action.getAsBoolean();
        } finally {
            viewer = previous;
        }
    }
}
