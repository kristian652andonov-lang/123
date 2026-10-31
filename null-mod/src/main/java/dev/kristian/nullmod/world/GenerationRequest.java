package dev.kristian.nullmod.world;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.event.EventContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * Everything a world generator needs to place one piece of corruption near a player.
 *
 * @param intensity 0..1 strength of this run (bigger fragments, more pieces)
 * @param forced    started by an administrator: try harder, accept closer and visible spots
 */
public record GenerationRequest(ServerLevel level, ServerPlayer player, RandomSource random, CorruptionStage stage,
                                float intensity, boolean forced) {

    public static GenerationRequest of(EventContext ctx) {
        return new GenerationRequest(ctx.level(), ctx.player(), ctx.random(), ctx.corruption().stage(), ctx.intensity(), ctx.forced());
    }

    public MinecraftServer server() {
        return level.getServer();
    }

    /** Stage number 1..5 for the generators' per-stage tables (HARVEST counts as NULL). */
    public int stageNumber() {
        return stage.tableIndex() + 1;
    }

    /** More attempts when forced, so an administrator's command almost always finds a spot. */
    public int attempts(int normal) {
        return forced ? normal * 3 : normal;
    }

    /** The preferred view mode, relaxed to any direction when forced. */
    public LocationFinder.View view(LocationFinder.View preferred) {
        return forced ? LocationFinder.View.ANY : preferred;
    }

    /** Distance band start, pulled closer when forced. */
    public double minDistance(double normal, double forcedMin) {
        return forced ? Math.min(normal, forcedMin) : normal;
    }
}
