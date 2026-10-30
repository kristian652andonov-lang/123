package dev.kristian.nullmod.world;

import dev.kristian.nullmod.world.structure.Blueprint;
import dev.kristian.nullmod.world.structure.CorruptedStructureGenerator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Part of the nearby landscape exists twice: a small natural area is copied and pasted again
 * hanging in the air above itself, shifted to one side, or upside down in the sky, with part of
 * the copy cut away along a ragged line. Only natural blocks and plants are ever copied (an area
 * with anything built in it is refused), the copy only goes into air, and sand and gravel in the
 * copy become their non-falling retro versions.
 */
public final class DuplicatedTerrain {
    enum Variant { FLOATING, SHIFTED, INVERTED }

    private DuplicatedTerrain() {
    }

    public static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            BlockPos source = LocationFinder.findGround(level, req.player(), random, req.minDistance(24.0, 12.0), 64.0,
                    req.view(LocationFinder.View.EDGE), 4, pos -> true);
            if (source == null) {
                continue;
            }
            Variant variant = Variant.values()[random.nextInt(Variant.values().length)];
            PlacementJob job = plan(level, source, variant, random);
            if (job != null && CorruptedStructureGenerator.submit(req, job, "duplicated_terrain", "the same ground twice", source)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static PlacementJob plan(ServerLevel level, BlockPos source, Variant variant, RandomSource random) {
        int size = 5 + random.nextInt(5);
        int x0 = source.getX() - size / 2;
        int z0 = source.getZ() - size / 2;
        int x1 = x0 + size - 1;
        int z1 = z0 + size - 1;
        if (!LocationFinder.areaLoaded(level, x0 - 1, z0 - 1, x1 + 1, z1 + 1)) {
            return null;
        }
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int g = LocationFinder.groundY(level, x, z);
                if (g == LocationFinder.NONE) {
                    return null;
                }
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        if (hi - lo > 6) {
            return null;
        }
        int y0 = lo - 2;
        int y1 = Math.min(hi + 6, level.getMaxBuildHeight() - 1);
        BlockPos srcMin = new BlockPos(x0, y0, z0);
        BlockPos srcMax = new BlockPos(x1, y1, z1);
        // Never copy anywhere near something somebody built.
        if (BuildProtection.isAreaProtected(level, srcMin, srcMax)) {
            return null;
        }

        int dx;
        int dy;
        int dz;
        int height = y1 - y0 + 1;
        switch (variant) {
            case FLOATING -> {
                dx = random.nextInt(7) - 3;
                dy = height + 6 + random.nextInt(12);
                dz = random.nextInt(7) - 3;
            }
            case SHIFTED -> {
                int gap = size + 2 + random.nextInt(5);
                boolean alongX = random.nextBoolean();
                int sign = random.nextBoolean() ? 1 : -1;
                dx = alongX ? gap * sign : random.nextInt(3) - 1;
                dz = alongX ? random.nextInt(3) - 1 : gap * sign;
                dy = (hi - lo) + 2 + random.nextInt(4);
            }
            default -> {
                dx = random.nextInt(5) - 2;
                dy = height + 14 + random.nextInt(12);
                dz = random.nextInt(5) - 2;
            }
        }
        if (y1 + dy >= level.getMaxBuildHeight() - 2) {
            return null;
        }

        // A ragged cut: part of the copy is simply missing.
        long seed = random.nextLong();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double nx = Math.cos(angle);
        double nz = Math.sin(angle);
        double cut = (random.nextDouble() - 0.2) * size * 0.35;
        double cx = (x0 + x1) / 2.0;
        double cz = (z0 + z1) / 2.0;

        Blueprint bp = new Blueprint();
        int copied = 0;
        int foreign = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double side = (x - cx) * nx + (z - cz) * nz + (WorldNoise.value(seed, x * 0.45, z * 0.45) - 0.5) * 3.0;
                if (side < cut) {
                    continue;
                }
                for (int y = y0; y <= y1; y++) {
                    BlockState state = level.getBlockState(cursor.set(x, y, z));
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.hasBlockEntity()) {
                        return null;
                    }
                    boolean plant = state.getBlock() instanceof BushBlock || state.getBlock() instanceof SnowLayerBlock;
                    if (!state.getFluidState().isEmpty() || !(BuildProtection.isNatural(state) || plant)) {
                        // Water, ores, anything unusual: left out of the copy (no free ores),
                        // and if there is too much of it this is not plain ground.
                        if (++foreign > size * size / 3) {
                            return null;
                        }
                        continue;
                    }
                    if (state.getBlock() instanceof DoublePlantBlock || (plant && variant == Variant.INVERTED)) {
                        continue;
                    }
                    int ly = variant == Variant.INVERTED ? (y1 - y) : (y - y0);
                    BlockState copy = CorruptionPalette.stableCopy(state);
                    if (plant) {
                        bp.attach(x - x0, ly, z - z0, copy);
                    } else {
                        bp.set(x - x0, ly, z - z0, copy);
                    }
                    copied++;
                }
            }
        }
        if (copied < size * 2) {
            return null;
        }
        BlockPos origin = new BlockPos(x0 + dx, y0 + dy, z0 + dz);
        BlockPos[] bounds = bp.worldBounds(origin, Rotation.NONE, Mirror.NONE);
        int limit = Math.max(2, bp.size() / 12);
        int blocked = LocationFinder.countBlocked(level, bounds[0], bounds[1], true, limit);
        if (blocked < 0 || blocked > limit) {
            return null;
        }
        PlacementJob job = new PlacementJob(level, "duplicated_terrain").atomic();
        bp.appendTo(job, origin, Rotation.NONE, Mirror.NONE);
        return job;
    }
}
