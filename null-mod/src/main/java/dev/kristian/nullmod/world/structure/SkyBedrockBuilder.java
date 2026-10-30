package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bedrock in the sky: single blocks, short bars, slabs and long columns of the world's floor
 * hanging far above the ground, as if the bottom of the world had loaded at the wrong height.
 */
final class SkyBedrockBuilder {
    private SkyBedrockBuilder() {
    }

    static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level) || !level.dimensionType().hasSkyLight()) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            LocationFinder.Column c = LocationFinder.column(req.player(), random, req.minDistance(30.0, 16.0), 100.0,
                    req.view(LocationFinder.View.ANY));
            int ground = LocationFinder.surfaceY(level, c.x(), c.z());
            if (ground == LocationFinder.NONE) {
                continue;
            }
            int baseY = Math.max(ground + 26 + random.nextInt(30), level.getSeaLevel() + 40);
            baseY = Math.min(baseY, level.getMaxBuildHeight() - 12);
            if (baseY < ground + 16) {
                continue;
            }
            PlacementJob job = plan(level, new BlockPos(c.x(), baseY, c.z()), random, req.intensity());
            if (job != null && CorruptedStructureGenerator.submit(req, job, "sky_bedrock", "bedrock in the sky", new BlockPos(c.x(), baseY, c.z()))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static PlacementJob plan(ServerLevel level, BlockPos center, RandomSource random, float intensity) {
        int spread = 6 + random.nextInt(9);
        if (!LocationFinder.areaLoaded(level, center.getX() - spread - 2, center.getZ() - spread - 2,
                center.getX() + spread + 2, center.getZ() + spread + 2)) {
            return null;
        }
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        Blueprint bp = new Blueprint();
        int pieces = 6 + Math.round(intensity * 14.0F) + random.nextInt(5);
        for (int i = 0; i < pieces; i++) {
            int x = random.nextInt(spread * 2 + 1) - spread;
            int z = random.nextInt(spread * 2 + 1) - spread;
            int y = random.nextInt(9) - 4;
            int kind = random.nextInt(100);
            if (kind < 38) {
                bp.set(x, y, z, bedrock);
            } else if (kind < 62) {
                int length = 2 + random.nextInt(3);
                boolean alongX = random.nextBoolean();
                for (int k = 0; k < length; k++) {
                    bp.set(alongX ? x + k : x, y, alongX ? z : z + k, bedrock);
                }
            } else if (kind < 78) {
                int sx = 2 + random.nextInt(2);
                int sz = 2 + random.nextInt(2);
                bp.fill(x, y, z, x + sx - 1, y, z + sz - 1, bedrock);
            } else {
                // A column hanging down (or standing up) from the cluster.
                int length = 3 + random.nextInt(8);
                int dir = random.nextFloat() < 0.75F ? -1 : 1;
                for (int k = 0; k < length; k++) {
                    bp.set(x, y + k * dir, z, bedrock);
                }
            }
        }
        BlockPos[] bounds = bp.worldBounds(center, Rotation.NONE, Mirror.NONE);
        // Only into open sky: give up if more than a few planned blocks are not air.
        int blocked = LocationFinder.countBlocked(level, bounds[0].offset(-1, -1, -1), bounds[1].offset(1, 1, 1), false, 3);
        if (blocked < 0 || blocked > 2) {
            return null;
        }
        PlacementJob job = new PlacementJob(level, "bedrock_sky").atomic();
        bp.appendTo(job, center, Rotation.NONE, Mirror.NONE);
        return job;
    }
}
