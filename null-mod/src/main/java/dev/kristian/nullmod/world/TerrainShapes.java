package dev.kristian.nullmod.world;

import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.structure.Blueprint;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Shapes shared by the terrain fragments and the floating structures. */
public final class TerrainShapes {
    private TerrainShapes() {
    }

    /**
     * The three layers of a piece of terrain. Every block in here can hang in the air: sand and
     * gravel are always the non-falling retro versions.
     */
    public record IslandPalette(BlockState top, BlockState sub, BlockState core, boolean vanillaGrass, boolean retro) {

        /** Outdated terrain: bright retro grass over retro dirt and stone. */
        public static IslandPalette oldLand() {
            return new IslandPalette(NullBlocks.RETRO_GRASS_BLOCK.get().defaultBlockState(), NullBlocks.RETRO_DIRT.get().defaultBlockState(),
                    NullBlocks.RETRO_STONE.get().defaultBlockState(), false, true);
        }

        /** Terrain that looks like the ground the piece was torn from. */
        public static IslandPalette forGround(BlockState ground, RandomSource random) {
            if (random.nextFloat() < 0.25F) {
                return oldLand();
            }
            Block block = ground.getBlock();
            BlockState dirt = Blocks.DIRT.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            if (ground.is(BlockTags.SAND) || block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) {
                BlockState sand = NullBlocks.RETRO_SAND.get().defaultBlockState();
                return new IslandPalette(sand, sand, Blocks.SANDSTONE.defaultBlockState(), false, false);
            }
            if (block == Blocks.SNOW_BLOCK || (block == Blocks.GRASS_BLOCK && ground.getValue(BlockStateProperties.SNOWY))) {
                return new IslandPalette(Blocks.SNOW_BLOCK.defaultBlockState(), dirt, stone, false, false);
            }
            if (block == Blocks.PODZOL || block == Blocks.MYCELIUM || block == Blocks.MOSS_BLOCK || block == Blocks.COARSE_DIRT) {
                return new IslandPalette(block.defaultBlockState(), dirt, stone, false, false);
            }
            if (ground.is(BlockTags.BASE_STONE_OVERWORLD) || block == Blocks.GRAVEL || block == Blocks.CALCITE) {
                BlockState top = block == Blocks.GRAVEL ? NullBlocks.RETRO_GRAVEL.get().defaultBlockState() : CorruptionPalette.stableCopy(ground);
                return new IslandPalette(top, stone, stone, false, false);
            }
            if (block == Blocks.END_STONE) {
                return new IslandPalette(ground, ground, ground, false, false);
            }
            return new IslandPalette(Blocks.GRASS_BLOCK.defaultBlockState(), dirt, stone, true, false);
        }
    }

    /**
     * An upside-down cone of terrain: a slightly domed top at {@code topY} with a ragged rim,
     * narrowing to a point below, a few blocks dripping from the tip. Coordinates are local to
     * the blueprint; returns how deep the cone goes below {@code topY}.
     */
    public static int island(Blueprint bp, int cx, int topY, int cz, int radius, IslandPalette palette, RandomSource random,
                             long seed, float corruption, int stage) {
        int deepest = 0;
        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                double angle = Math.atan2(z, x);
                double rim = radius * (0.82 + 0.36 * WorldNoise.value(seed, Math.cos(angle) * 1.7 + 3.1, Math.sin(angle) * 1.7 + 3.1));
                double r = Math.sqrt(x * x + z * z) / Math.max(1.0, rim);
                if (r > 1.0) {
                    continue;
                }
                int top = r < 0.45 ? 1 : 0;
                int depth = Math.max(1, (int) Math.round(Math.pow(1.0 - r, 0.85) * (radius * 1.35 + 1.0)
                        + WorldNoise.hash01(seed, x, z) * 1.5));
                for (int y = top; y >= top - depth; y--) {
                    int below = top - y;
                    BlockState state = below == 0 ? palette.top() : below <= 2 ? palette.sub() : palette.core();
                    if (corruption > 0.0F && below > 1 && WorldNoise.hash01(seed ^ 0xC0FFEEL, x, y, z) < corruption) {
                        state = CorruptionPalette.errorBlock(random, stage);
                    }
                    bp.set(cx + x, topY + y, cz + z, state);
                }
                deepest = Math.max(deepest, depth - top);
            }
        }
        int drips = 2 + random.nextInt(3);
        for (int i = 0; i < drips; i++) {
            int x = random.nextInt(3) - 1;
            int z = random.nextInt(3) - 1;
            int low = lowest(bp, cx + x, cz + z, topY + 1, topY - deepest - 2);
            if (low == Integer.MIN_VALUE) {
                continue;
            }
            int length = 1 + random.nextInt(3);
            for (int k = 1; k <= length; k++) {
                bp.set(cx + x, low - k, cz + z, palette.core());
            }
        }
        return deepest;
    }

    /**
     * A small tree the way trees used to be: a straight trunk and a round blob of leaves.
     * {@code y} is the first trunk block.
     */
    public static void oldTree(Blueprint bp, int x, int y, int z, BlockState log, BlockState leaves, RandomSource random) {
        int trunk = 4 + random.nextInt(2);
        int crown = y + trunk - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int dist = dx * dx + dz * dz + dy * dy;
                    if (dist > 6 || (dist > 4 && random.nextFloat() < 0.35F)) {
                        continue;
                    }
                    bp.set(x + dx, crown + dy, z + dz, leaves);
                }
            }
        }
        for (int k = 0; k < trunk; k++) {
            bp.set(x, y + k, z, log);
        }
    }

    /** A plain old oak: vanilla log and persistent leaves (they never decay floating in the air). */
    public static void oakTree(Blueprint bp, int x, int y, int z, RandomSource random) {
        oldTree(bp, x, y, z, Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y),
                Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, Boolean.TRUE), random);
    }

    /** The lowest planned Y in a column between {@code fromY} (inclusive, top) and {@code toY}, or MIN_VALUE. */
    public static int lowest(Blueprint bp, int x, int z, int fromY, int toY) {
        int found = Integer.MIN_VALUE;
        for (int y = fromY; y >= toY; y--) {
            if (bp.has(x, y, z)) {
                found = y;
            }
        }
        return found;
    }

    /** The highest planned Y in a column between {@code fromY} (top) and {@code toY}, or MIN_VALUE. */
    public static int highest(Blueprint bp, int x, int z, int fromY, int toY) {
        for (int y = fromY; y >= toY; y--) {
            if (bp.has(x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }
}
