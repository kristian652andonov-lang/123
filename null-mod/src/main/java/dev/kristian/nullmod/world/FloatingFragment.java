package dev.kristian.nullmod.world;

import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.structure.Blueprint;
import dev.kristian.nullmod.world.structure.CorruptedStructureGenerator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A piece of the ground hanging in the air: an upside-down cone of the local terrain (or of old
 * retro terrain) 14-32 blocks above the ground, 40-120 blocks from the player, with grass or a
 * small tree on top. From Stage 4 its underside is shot through with error blocks.
 */
public final class FloatingFragment {
    private FloatingFragment() {
    }

    public static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            double min = req.minDistance(40.0, 20.0);
            BlockPos ground = LocationFinder.findGround(level, req.player(), random, min, req.maxDistance(120.0, min),
                    req.view(LocationFinder.View.HIDDEN), 4, pos -> true);
            if (ground == null) {
                continue;
            }
            int topY = Math.min(ground.getY() + 14 + random.nextInt(19), level.getMaxBuildHeight() - 10);
            if (topY < ground.getY() + 10) {
                continue;
            }
            BlockPos origin = new BlockPos(ground.getX(), topY, ground.getZ());
            PlacementJob job = plan(level, origin, level.getBlockState(ground), random, req.intensity(), req.stageNumber());
            if (job != null && CorruptedStructureGenerator.submit(req, job, "floating_fragment", "a piece of land in the air", origin)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static PlacementJob plan(ServerLevel level, BlockPos origin, BlockState groundState,
                             RandomSource random, float intensity, int stage) {
        int radius = 3 + random.nextInt(3) + Math.round(intensity * 3.0F);
        if (!LocationFinder.areaLoaded(level, origin.getX() - radius - 3, origin.getZ() - radius - 3,
                origin.getX() + radius + 3, origin.getZ() + radius + 3)) {
            return null;
        }
        TerrainShapes.IslandPalette palette = TerrainShapes.IslandPalette.forGround(groundState, random);
        float corruption = stage >= 4 ? 0.12F + 0.1F * intensity : 0.0F;
        Blueprint bp = new Blueprint();
        TerrainShapes.island(bp, 0, 0, 0, radius, palette, random, random.nextLong(), corruption, stage);

        // Something growing on top, as if it had always been there.
        if (palette.vanillaGrass() || palette.retro()) {
            if (random.nextFloat() < 0.4F) {
                int tx = random.nextInt(3) - 1;
                int tz = random.nextInt(3) - 1;
                int top = TerrainShapes.highest(bp, tx, tz, 2, -2);
                if (top != Integer.MIN_VALUE) {
                    if (palette.retro()) {
                        TerrainShapes.oldTree(bp, tx, top + 1, tz, CorruptionPalette.retroLog(Direction.Axis.Y),
                                NullBlocks.RETRO_LEAVES.get().defaultBlockState(), random);
                    } else {
                        TerrainShapes.oakTree(bp, tx, top + 1, tz, random);
                    }
                }
            }
            if (palette.vanillaGrass()) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (random.nextFloat() >= 0.22F) {
                            continue;
                        }
                        int top = TerrainShapes.highest(bp, x, z, 2, -2);
                        if (top != Integer.MIN_VALUE && !bp.has(x, top + 1, z)) {
                            bp.attach(x, top + 1, z, Blocks.SHORT_GRASS.defaultBlockState());
                        }
                    }
                }
            }
        }

        BlockPos[] bounds = bp.worldBounds(origin, Rotation.NONE, Mirror.NONE);
        int blocked = LocationFinder.countBlocked(level, bounds[0].offset(-1, -1, -1), bounds[1].offset(1, 1, 1), false, 4);
        if (blocked < 0 || blocked > 3) {
            return null;
        }
        PlacementJob job = new PlacementJob(level, "floating_fragment").atomic();
        bp.appendTo(job, origin, Rotation.NONE, Mirror.NONE);
        return job;
    }
}
