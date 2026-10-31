package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Something built far away, the way people built things in a much older version of the game:
 * a one-block cobblestone pillar into the sky, a dirt pole with one block sticking out at the
 * top, a cobblestone hut with a flat roof, a sand pyramid, a long wall going nowhere, a brick
 * chimney with no house, a wooden platform high in the air. Placed 80-120 blocks away, at the
 * edge of the player's view, so it is noticed late or not at all.
 */
final class DistantStructureBuilder {
    enum Kind { PILLAR, DIRT_POLE, HUT, PYRAMID, WALL, CHIMNEY, SKY_PLATFORM }

    private DistantStructureBuilder() {
    }

    static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            double min = req.minDistance(80.0, 30.0);
            BlockPos ground = LocationFinder.findGround(level, req.player(), random, min,
                    req.maxDistance(req.forced() ? 90.0 : 120.0, min), req.view(LocationFinder.View.EDGE), 4, pos -> true);
            if (ground == null) {
                continue;
            }
            Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
            PlacementJob job = plan(level, ground, kind, random);
            if (job != null && CorruptedStructureGenerator.submit(req, job, "distant_structure", describe(kind), ground)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Kind kind) {
        return switch (kind) {
            case PILLAR -> "a pillar into the sky";
            case DIRT_POLE -> "a dirt pole";
            case HUT -> "an old cobblestone hut";
            case PYRAMID -> "a sand pyramid";
            case WALL -> "a wall going nowhere";
            case CHIMNEY -> "a chimney without a house";
            case SKY_PLATFORM -> "a platform in the sky";
        };
    }

    @Nullable
    static PlacementJob plan(ServerLevel level, BlockPos ground, Kind kind, RandomSource random) {
        BlockState cobble = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        BlockState planks = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        Blueprint bp = new Blueprint();
        int footprint = 1;
        Rotation rotation = Rotation.getRandom(random);
        BlockPos origin = ground.above();
        switch (kind) {
            case PILLAR -> {
                int h = 10 + random.nextInt(15);
                for (int y = 0; y < h; y++) {
                    bp.set(0, y, 0, cobble);
                }
                bp.attach(0, h, 0, Blocks.TORCH.defaultBlockState());
            }
            case DIRT_POLE -> {
                int h = 8 + random.nextInt(9);
                BlockState dirt = NullBlocks.RETRO_DIRT.get().defaultBlockState();
                for (int y = 0; y < h; y++) {
                    bp.set(0, y, 0, dirt);
                }
                bp.set(1, h - 1, 0, dirt);
            }
            case HUT -> {
                footprint = 5;
                BlockState glass = NullBlocks.RETRO_GLASS.get().defaultBlockState();
                for (int y = 0; y < 3; y++) {
                    for (int x = 0; x < 5; x++) {
                        for (int z = 0; z < 5; z++) {
                            if (x == 0 || x == 4 || z == 0 || z == 4) {
                                bp.set(x, y, z, cobble);
                            }
                        }
                    }
                }
                bp.fill(0, 3, 0, 4, 3, 4, planks);
                bp.remove(2, 0, 0);
                bp.remove(2, 1, 0);
                bp.set(4, 1, 2, glass);
                bp.attach(1, 1, 3, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
            }
            case PYRAMID -> {
                int base = 7 + random.nextInt(2) * 2;
                footprint = base;
                BlockState sand = NullBlocks.RETRO_SAND.get().defaultBlockState();
                for (int layer = 0; layer * 2 < base; layer++) {
                    bp.fill(layer, layer, layer, base - 1 - layer, layer, base - 1 - layer, sand);
                }
            }
            case WALL -> {
                int length = 16 + random.nextInt(13);
                // Each column of the wall stands on its own patch of ground.
                PlacementJob job = new PlacementJob(level, "distant_structure").atomic();
                Direction along = random.nextBoolean() ? Direction.EAST : Direction.SOUTH;
                if (!LocationFinder.areaLoaded(level, ground.getX() - 2, ground.getZ() - 2,
                        ground.getX() + length + 2, ground.getZ() + length + 2)) {
                    return null;
                }
                int placed = 0;
                for (int k = 0; k < length; k++) {
                    BlockPos column = ground.relative(along, k);
                    BlockPos g = LocationFinder.naturalGround(level, column.getX(), column.getZ());
                    if (g == null || Math.abs(g.getY() - ground.getY()) > 4) {
                        continue;
                    }
                    for (int y = 1; y <= 3; y++) {
                        job.place(g.above(y), (y == 3 && random.nextFloat() < 0.15F) ? planks : cobble);
                    }
                    placed++;
                }
                return placed >= length * 2 / 3 ? job : null;
            }
            case CHIMNEY -> {
                footprint = 2;
                int h = 9 + random.nextInt(6);
                bp.fill(0, 0, 0, 1, h - 1, 1, NullBlocks.RETRO_BRICKS.get().defaultBlockState());
            }
            case SKY_PLATFORM -> {
                int lift = 18 + random.nextInt(14);
                if (ground.getY() + lift + 4 >= level.getMaxBuildHeight()) {
                    return null;
                }
                origin = ground.above(lift);
                bp.fill(0, 0, 0, 4, 0, 4, planks);
                bp.attach(2, 1, 2, Blocks.TORCH.defaultBlockState());
                BlockPos[] bounds = bp.worldBounds(origin, rotation, Mirror.NONE);
                int blocked = LocationFinder.countBlocked(level, bounds[0].offset(-1, -1, -1), bounds[1].offset(1, 2, 1), false, 1);
                if (blocked != 0) {
                    return null;
                }
                PlacementJob job = new PlacementJob(level, "distant_structure").atomic();
                bp.appendTo(job, origin, rotation, Mirror.NONE);
                return job;
            }
        }
        // Everything else stands on the ground: it must be flat enough, and old foundations fill any gap.
        BlockPos center = Blueprint.toWorld(BlockPos.ZERO, footprint / 2, 0, footprint / 2, rotation, Mirror.NONE);
        BlockPos probe = new BlockPos(ground.getX() - center.getX(), 0, ground.getZ() - center.getZ());
        int[] rect = Siting.rect(probe, footprint, footprint, rotation);
        if (!LocationFinder.areaLoaded(level, rect[0] - 2, rect[1] - 2, rect[2] + 2, rect[3] + 2)) {
            return null;
        }
        int[] range = Siting.groundRange(level, rect, 1);
        if (range == null || range[1] - range[0] > 2) {
            return null;
        }
        int floorY = range[1] + 1;
        PlacementJob job = new PlacementJob(level, "distant_structure").atomic();
        bp.appendTo(job, probe.atY(floorY), rotation, Mirror.NONE);
        Siting.foundation(level, job, rect, floorY, cobble, 3, null);
        return job;
    }
}
