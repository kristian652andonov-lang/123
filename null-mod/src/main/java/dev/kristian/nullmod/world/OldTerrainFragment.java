package dev.kristian.nullmod.world;

import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.structure.Blueprint;
import dev.kristian.nullmod.world.structure.CorruptedStructureGenerator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "Chunk corruption": a square of terrain from a much older version of the game, sitting on
 * top of today's ground as if a chunk had loaded from the wrong save. Blocky stepped terraces
 * of retro grass, dirt and stone with sharp vertical sides, sand and gravel beaches in the low
 * parts, round old trees, a few loose blocks floating at the edge. Generated from noise of its
 * own (no old chunk data), only into air, 40-110 blocks from the player.
 */
public final class OldTerrainFragment {
    private OldTerrainFragment() {
    }

    public static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            BlockPos center = LocationFinder.findGround(level, req.player(), random, req.minDistance(40.0, 20.0), 110.0,
                    req.view(LocationFinder.View.HIDDEN), 4, pos -> true);
            if (center == null) {
                continue;
            }
            PlacementJob job = plan(level, center, random, req.forced());
            if (job != null && CorruptedStructureGenerator.submit(req, job, "old_terrain", "a piece of old land", center)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static PlacementJob plan(ServerLevel level, BlockPos center, RandomSource random, boolean forced) {
        boolean chunkShaped = random.nextBoolean();
        int w;
        int d;
        int x0;
        int z0;
        if (chunkShaped) {
            // Exactly one chunk, on the chunk grid: it looks like a chunk that loaded wrong.
            w = 16;
            d = 16;
            x0 = center.getX() & ~15;
            z0 = center.getZ() & ~15;
        } else {
            w = 10 + random.nextInt(6);
            d = 10 + random.nextInt(6);
            x0 = center.getX() - w / 2;
            z0 = center.getZ() - d / 2;
        }
        if (!LocationFinder.areaLoaded(level, x0 - 3, z0 - 3, x0 + w + 2, z0 + d + 2)) {
            return null;
        }
        int[] ground = new int[w * d];
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int cluttered = 0;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int gx = x0 + x;
                int gz = z0 + z;
                int g = LocationFinder.groundY(level, gx, gz);
                if (g == LocationFinder.NONE) {
                    return null;
                }
                BlockState top = level.getBlockState(new BlockPos(gx, g, gz));
                if (!top.getFluidState().isEmpty()) {
                    return null;
                }
                int surface = LocationFinder.surfaceY(level, gx, gz);
                if (top.is(BlockTags.LOGS) || surface - g > 2) {
                    cluttered++;
                }
                ground[x * d + z] = g;
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        if (hi - lo > (forced ? 12 : 8) || cluttered > w * d / (forced ? 4 : 10)) {
            return null;
        }

        long seed = random.nextLong();
        int base = hi + 1 + random.nextInt(2);
        int amplitude = 2 + random.nextInt(4);
        BlockState grass = NullBlocks.RETRO_GRASS_BLOCK.get().defaultBlockState();
        BlockState dirt = NullBlocks.RETRO_DIRT.get().defaultBlockState();
        BlockState stone = NullBlocks.RETRO_STONE.get().defaultBlockState();
        BlockState sand = NullBlocks.RETRO_SAND.get().defaultBlockState();
        BlockState gravel = NullBlocks.RETRO_GRAVEL.get().defaultBlockState();
        Blueprint bp = new Blueprint();
        int[] tops = new int[w * d];
        boolean[] beach = new boolean[w * d];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int i = x * d + z;
                double n = WorldNoise.fractal(seed, x / 5.0, z / 5.0);
                // Quantised heights: flat terraces with one-block steps and the odd cliff.
                int top = base + Mth.floor(n * (amplitude + 1));
                boolean low = n < 0.28;
                tops[i] = top;
                beach[i] = low;
                int g = ground[i];
                int bottom = top - g > 11 ? top - 5 - (int) (WorldNoise.hash01(seed, x, z) * 3.0) : g + 1;
                int dirtDepth = 2 + (WorldNoise.hash01(seed ^ 0x11L, x, z) > 0.6 ? 1 : 0);
                for (int y = bottom; y <= top; y++) {
                    int below = top - y;
                    BlockState state;
                    if (below == 0) {
                        state = low ? (WorldNoise.hash01(seed ^ 0x22L, x, z) < 0.55 ? sand : gravel) : grass;
                    } else if (below <= dirtDepth) {
                        state = low ? sand : dirt;
                    } else {
                        double pocket = WorldNoise.hash01(seed ^ 0x33L, x >> 1, y >> 1, z >> 1);
                        state = pocket < 0.07 ? gravel : pocket < 0.12 ? dirt : stone;
                    }
                    bp.set(x, y - base, z, state);
                }
            }
        }

        // Round old trees on the grass.
        int trees = random.nextInt(3) + (w >= 14 ? 1 : 0);
        BlockState log = CorruptionPalette.retroLog(Direction.Axis.Y);
        BlockState leaves = NullBlocks.RETRO_LEAVES.get().defaultBlockState();
        for (int t = 0; t < trees; t++) {
            int tx = 3 + random.nextInt(Math.max(1, w - 6));
            int tz = 3 + random.nextInt(Math.max(1, d - 6));
            int i = tx * d + tz;
            if (!beach[i]) {
                TerrainShapes.oldTree(bp, tx, tops[i] - base + 1, tz, log, leaves, random);
            }
        }

        // Loose blocks floating just off the edge, the way old terrain generation left them.
        int loose = 2 + random.nextInt(4);
        for (int k = 0; k < loose; k++) {
            boolean alongX = random.nextBoolean();
            int x = alongX ? random.nextInt(w) : (random.nextBoolean() ? -1 - random.nextInt(2) : w + random.nextInt(2));
            int z = alongX ? (random.nextBoolean() ? -1 - random.nextInt(2) : d + random.nextInt(2)) : random.nextInt(d);
            int nx = Mth.clamp(x, 0, w - 1);
            int nz = Mth.clamp(z, 0, d - 1);
            int y = tops[nx * d + nz] - base - 1 - random.nextInt(3);
            bp.set(x, y, z, random.nextBoolean() ? dirt : stone);
        }

        PlacementJob job = new PlacementJob(level, "old_terrain_fragment").atomic();
        bp.appendTo(job, new BlockPos(x0, base, z0), Rotation.NONE, Mirror.NONE);
        return job;
    }
}
