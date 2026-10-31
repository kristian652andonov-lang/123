package dev.kristian.nullmod.entity.it;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Game-bus side of IT: nobody gets down from its claw on their own, players who just joined,
 * respawned or arrived in a world are left alone for a moment, a victim who logs out is let go
 * first (otherwise the game would save IT with them as their mount and take it out of the world),
 * and the per-player bookkeeping is dropped on logout and server stop. Server thread only.
 */
public final class ItEvents {
    /** Player id to the server tick their grace period ends. */
    private static final Map<UUID, Integer> GRACE = new HashMap<>();

    private ItEvents() {
    }

    /** True for a short while after a player joined, respawned, changed world or survived being seized. */
    public static boolean inGrace(ServerPlayer player) {
        Integer until = GRACE.get(player.getUUID());
        return until != null && player.server.getTickCount() < until;
    }

    /** Leaves a player alone for at least this many ticks from now. */
    public static void grace(ServerPlayer player, int ticks) {
        int until = player.server.getTickCount() + ticks;
        GRACE.merge(player.getUUID(), until, Math::max);
    }

    /** Ends a player's grace period at once (tests and commands). */
    public static void clearGrace(ServerPlayer player) {
        GRACE.remove(player.getUUID());
    }

    /** The held victim cannot dismount; everything else (death, logout, a change of world, IT letting go) may. */
    static void onEntityMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide() || event.isMounting()) {
            return;
        }
        if (event.getEntityBeingMounted() instanceof ItEntity it && event.getEntityMounting() instanceof ServerPlayer player
                && it.holdsFast(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * A held victim sent to another world (the field sending them home, a command) is let go
     * before they leave: teleports call stopRiding() before the move starts, which the guard above
     * would refuse. Registered last, so it only runs for moves nobody cancelled.
     */
    static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getVehicle() instanceof ItEntity it
                && event.getDimension() != player.level().dimension()) {
            it.release(player);
        }
    }

    static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            grace(player, ItTuning.JOIN_GRACE_TICKS);
        }
    }

    /** Fired before the game saves the player and removes their mount with them: let go first. */
    static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.getVehicle() instanceof ItEntity it) {
                it.release(player);
            }
            GRACE.remove(player.getUUID());
            ItShelter.forget(player.getUUID());
        }
    }

    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            grace(player, ItTuning.RESPAWN_GRACE_TICKS);
            ItShelter.forget(player.getUUID());
        }
    }

    static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            grace(player, ItTuning.RESPAWN_GRACE_TICKS);
            ItShelter.forget(player.getUUID());
        }
    }

    /** Expired grace periods go every few seconds. */
    static void onServerTick(ServerTickEvent.Post event) {
        int now = event.getServer().getTickCount();
        if (GRACE.isEmpty() || now % 100 != 0) {
            return;
        }
        for (Iterator<Map.Entry<UUID, Integer>> it = GRACE.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        GRACE.clear();
        ItShelter.clear();
    }
}
