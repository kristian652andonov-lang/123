package dev.kristian.nullmod.world;

import dev.kristian.nullmod.registry.NullBlocks;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Which block corruption turns a natural block into, and the "outdated" retro equivalents used
 * by old-looking terrain and structures.
 */
public final class CorruptionPalette {
    private static volatile Set<Block> corruptionBlocks;

    private CorruptionPalette() {
    }

    // ------------------------------------------------------------------ corruption

    /** The corrupted_* block matching a natural block, or null if there is none. */
    @Nullable
    public static BlockState corruptedVariant(BlockState natural) {
        Block block = natural.getBlock();
        if (natural.is(BlockTags.LEAVES)) {
            return NullBlocks.CORRUPTED_LEAVES.get().defaultBlockState();
        }
        if (natural.is(BlockTags.LOGS)) {
            BlockState log = NullBlocks.CORRUPTED_LOG.get().defaultBlockState();
            if (natural.hasProperty(BlockStateProperties.AXIS)) {
                log = log.setValue(BlockStateProperties.AXIS, natural.getValue(BlockStateProperties.AXIS));
            }
            return log;
        }
        if (block == Blocks.GRASS_BLOCK || block == Blocks.MYCELIUM || block == Blocks.PODZOL || block == Blocks.MOSS_BLOCK) {
            return NullBlocks.CORRUPTED_GRASS_BLOCK.get().defaultBlockState();
        }
        if (natural.is(BlockTags.DIRT) || block == Blocks.CLAY || block == Blocks.MUD || block == Blocks.COARSE_DIRT) {
            return NullBlocks.CORRUPTED_DIRT.get().defaultBlockState();
        }
        if (natural.is(BlockTags.SAND) || block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) {
            return NullBlocks.CORRUPTED_SAND.get().defaultBlockState();
        }
        if (natural.is(BlockTags.BASE_STONE_OVERWORLD) || block == Blocks.GRAVEL || block == Blocks.CALCITE
                || block == Blocks.NETHERRACK || block == Blocks.END_STONE) {
            return NullBlocks.CORRUPTED_STONE.get().defaultBlockState();
        }
        return null;
    }

    /** One of the "this block failed to load" blocks, weighted by how far the world has fallen. */
    public static BlockState errorBlock(RandomSource random, int stage) {
        int roll = random.nextInt(100);
        Block block;
        if (stage <= 1) {
            block = roll < 58 ? NullBlocks.BLACK_BLOCK.get() : roll < 88 ? NullBlocks.MISSING_BLOCK.get() : NullBlocks.ERROR_BLOCK.get();
        } else if (stage == 2) {
            block = roll < 34 ? NullBlocks.BLACK_BLOCK.get()
                    : roll < 58 ? NullBlocks.MISSING_BLOCK.get()
                    : roll < 73 ? NullBlocks.ERROR_BLOCK.get()
                    : roll < 85 ? NullBlocks.ERROR_STRIPES.get()
                    : roll < 94 ? NullBlocks.ERROR_NOISE.get()
                    : NullBlocks.STATIC_BLOCK.get();
        } else if (stage == 3) {
            block = roll < 24 ? NullBlocks.BLACK_BLOCK.get()
                    : roll < 43 ? NullBlocks.MISSING_BLOCK.get()
                    : roll < 58 ? NullBlocks.ERROR_BLOCK.get()
                    : roll < 70 ? NullBlocks.ERROR_STRIPES.get()
                    : roll < 82 ? NullBlocks.ERROR_NOISE.get()
                    : roll < 91 ? NullBlocks.ERROR_SHIFT.get()
                    : NullBlocks.STATIC_BLOCK.get();
        } else {
            block = roll < 21 ? NullBlocks.BLACK_BLOCK.get()
                    : roll < 37 ? NullBlocks.MISSING_BLOCK.get()
                    : roll < 51 ? NullBlocks.ERROR_BLOCK.get()
                    : roll < 63 ? NullBlocks.ERROR_STRIPES.get()
                    : roll < 77 ? NullBlocks.ERROR_NOISE.get()
                    : roll < 89 ? NullBlocks.ERROR_SHIFT.get()
                    : NullBlocks.STATIC_BLOCK.get();
        }
        return block.defaultBlockState();
    }

    /**
     * What a natural block becomes when corruption reaches it. {@code core} (0..1) is how close
     * it is to the heart of the patch: the heart is mostly error blocks, the rim mostly the
     * matching corrupted_* block.
     */
    public static BlockState corrupt(BlockState natural, RandomSource random, int stage, float core) {
        BlockState variant = corruptedVariant(natural);
        if (variant == null) {
            return errorBlock(random, stage);
        }
        float errorChance = stage <= 1 ? 0.62F : 0.18F + 0.5F * Math.max(0.0F, Math.min(1.0F, core)) + 0.03F * (stage - 2);
        return random.nextFloat() < errorChance ? errorBlock(random, stage) : variant;
    }

    /** True for every block NULL's terrain corruption produces. */
    public static boolean isCorruption(BlockState state) {
        Set<Block> set = corruptionBlocks;
        if (set == null) {
            Set<Block> built = new HashSet<>();
            NullBlocks.errorBlocks().forEach(holder -> built.add(holder.get()));
            built.add(NullBlocks.CORRUPTED_GRASS_BLOCK.get());
            built.add(NullBlocks.CORRUPTED_DIRT.get());
            built.add(NullBlocks.CORRUPTED_STONE.get());
            built.add(NullBlocks.CORRUPTED_SAND.get());
            built.add(NullBlocks.CORRUPTED_LOG.get());
            built.add(NullBlocks.CORRUPTED_LEAVES.get());
            corruptionBlocks = set = built;
        }
        return set.contains(state.getBlock());
    }

    // ------------------------------------------------------------------ retro / non-falling

    /** The outdated-looking retro block for a natural block, or null if there is none. */
    @Nullable
    public static BlockState retroVariant(BlockState natural) {
        Block block = natural.getBlock();
        if (natural.is(BlockTags.LEAVES)) {
            return NullBlocks.RETRO_LEAVES.get().defaultBlockState();
        }
        if (natural.is(BlockTags.LOGS)) {
            return retroLog(natural.hasProperty(BlockStateProperties.AXIS)
                    ? natural.getValue(BlockStateProperties.AXIS) : Direction.Axis.Y);
        }
        if (block == Blocks.GRASS_BLOCK || block == Blocks.MYCELIUM || block == Blocks.PODZOL || block == Blocks.MOSS_BLOCK) {
            return NullBlocks.RETRO_GRASS_BLOCK.get().defaultBlockState();
        }
        if (natural.is(BlockTags.DIRT) || block == Blocks.CLAY || block == Blocks.MUD) {
            return NullBlocks.RETRO_DIRT.get().defaultBlockState();
        }
        if (natural.is(BlockTags.SAND) || block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) {
            return NullBlocks.RETRO_SAND.get().defaultBlockState();
        }
        if (block == Blocks.GRAVEL) {
            return NullBlocks.RETRO_GRAVEL.get().defaultBlockState();
        }
        if (block == Blocks.COBBLESTONE) {
            return NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        }
        if (natural.is(BlockTags.BASE_STONE_OVERWORLD) || block == Blocks.CALCITE) {
            return NullBlocks.RETRO_STONE.get().defaultBlockState();
        }
        return null;
    }

    public static BlockState retroLog(Direction.Axis axis) {
        return NullBlocks.RETRO_LOG.get().defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    /**
     * A copy of a block that can hang in the air: vanilla sand and gravel become the retro
     * blocks (which never fall), and leaves are made persistent so they never decay.
     */
    public static BlockState stableCopy(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof FallingBlock) {
            if (block == Blocks.GRAVEL || block == Blocks.SUSPICIOUS_GRAVEL) {
                return NullBlocks.RETRO_GRAVEL.get().defaultBlockState();
            }
            return NullBlocks.RETRO_SAND.get().defaultBlockState();
        }
        if (state.hasProperty(BlockStateProperties.PERSISTENT)) {
            return state.setValue(BlockStateProperties.PERSISTENT, Boolean.TRUE);
        }
        return state;
    }
}
