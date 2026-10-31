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
 * @param showcase  started by {@code /null showcase}: always forced, and placed in front of the
 *                  player, about {@value #SHOWCASE_NEAR}-{@value #SHOWCASE_FAR} blocks away (large
 *                  things a little further, so the player never stands inside them), trying other
 *                  spots in view when one is refused
 */
public record GenerationRequest(ServerLevel level, ServerPlayer player, RandomSource random, CorruptionStage stage,
                                float intensity, boolean forced, boolean showcase) {
    /** Nearest distance of a showcase placement. */
    public static final double SHOWCASE_NEAR = 10.0;
    /** Furthest distance of a showcase placement for things that fit (see {@link #maxDistance}). */
    public static final double SHOWCASE_FAR = 30.0;
    /** Width of a showcase distance band when a large structure must start further out. */
    private static final double SHOWCASE_BAND = 10.0;

    public GenerationRequest {
        forced = forced || showcase;
    }

    public GenerationRequest(ServerLevel level, ServerPlayer player, RandomSource random, CorruptionStage stage,
                             float intensity, boolean forced) {
        this(level, player, random, stage, intensity, forced, false);
    }

    public static GenerationRequest of(EventContext ctx) {
        return new GenerationRequest(ctx.level(), ctx.player(), ctx.random(), ctx.corruption().stage(), ctx.intensity(),
                ctx.forced(), ctx.showcase());
    }

    public MinecraftServer server() {
        return level.getServer();
    }

    /** Stage number 1..5 for the generators' per-stage tables (HARVEST counts as NULL). */
    public int stageNumber() {
        return stage.tableIndex() + 1;
    }

    /** More attempts when forced, so an administrator's command almost always finds a spot (more still in the showcase's narrow view). */
    public int attempts(int normal) {
        return showcase ? normal * 4 : forced ? normal * 3 : normal;
    }

    /** The preferred view mode: straight ahead in the showcase, relaxed to any direction when forced. */
    public LocationFinder.View view(LocationFinder.View preferred) {
        if (showcase) {
            return LocationFinder.View.FRONT;
        }
        return forced ? LocationFinder.View.ANY : preferred;
    }

    /**
     * Distance band start, pulled closer when forced; in the showcase never closer than
     * {@link #SHOWCASE_NEAR} nor than the generator's own forced minimum (its size).
     */
    public double minDistance(double normal, double forcedMin) {
        if (showcase) {
            return Math.max(forcedMin, SHOWCASE_NEAR);
        }
        return forced ? Math.min(normal, forcedMin) : normal;
    }

    /**
     * Distance band end for a band starting at {@code min}: unchanged normally; in the showcase
     * at most {@link #SHOWCASE_FAR}, or {@value #SHOWCASE_BAND} blocks past {@code min} for things
     * that must start further out.
     */
    public double maxDistance(double normal, double min) {
        if (!showcase) {
            return normal;
        }
        return Math.max(min + SHOWCASE_BAND, Math.min(normal, SHOWCASE_FAR));
    }
}
