package dev.kristian.nullmod.client.visual;

import dev.kristian.nullmod.registry.NullBlocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * What a glitched block turns into. Every replacement is an opaque full cube without a block
 * entity or light emission, so swapping it in and out never disturbs lighting or collision more
 * than the original block did.
 */
final class BlockGlitchPalette {
    private BlockGlitchPalette() {
    }

    static BlockState black() {
        return NullBlocks.BLACK_BLOCK.get().defaultBlockState();
    }

    static BlockState missing() {
        return NullBlocks.MISSING_BLOCK.get().defaultBlockState();
    }

    /** One of the distorted "the game failed to load this" blocks. */
    static BlockState error(RandomSource random) {
        return switch (random.nextInt(4)) {
            case 0 -> NullBlocks.ERROR_BLOCK.get().defaultBlockState();
            case 1 -> NullBlocks.ERROR_STRIPES.get().defaultBlockState();
            case 2 -> NullBlocks.ERROR_NOISE.get().defaultBlockState();
            default -> NullBlocks.ERROR_SHIFT.get().defaultBlockState();
        };
    }

    /**
     * A real vanilla block that is plausible as a texture mix-up but clearly wrong in place:
     * grass becomes netherrack, stone becomes sponge, leaves become nether wart, logs become
     * bone. Pillar axes are kept so a sideways log stays sideways.
     */
    static BlockState wrongFor(BlockState original, RandomSource random) {
        Block block;
        if (original.is(BlockTags.LOGS)) {
            block = pick(random, Blocks.BONE_BLOCK, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM, Blocks.HAY_BLOCK);
        } else if (original.is(BlockTags.LEAVES)) {
            block = pick(random, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.WET_SPONGE);
        } else if (original.is(Blocks.GRASS_BLOCK) || original.is(Blocks.PODZOL) || original.is(Blocks.MYCELIUM)) {
            block = pick(random, Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.NETHERRACK);
        } else if (original.is(BlockTags.DIRT)) {
            block = pick(random, Blocks.SOUL_SOIL, Blocks.NETHERRACK, Blocks.DRIED_KELP_BLOCK, Blocks.SOUL_SOIL);
        } else if (original.is(BlockTags.SAND)) {
            block = pick(random, Blocks.HONEYCOMB_BLOCK, Blocks.END_STONE, Blocks.SPONGE, Blocks.BONE_BLOCK);
        } else if (original.is(BlockTags.BASE_STONE_OVERWORLD)) {
            block = pick(random, Blocks.SPONGE, Blocks.END_STONE, Blocks.WET_SPONGE, Blocks.DRIED_KELP_BLOCK);
        } else {
            block = pick(random, Blocks.SPONGE, Blocks.MELON, Blocks.BOOKSHELF, Blocks.NOTE_BLOCK);
        }
        BlockState state = block.defaultBlockState();
        if (state.getBlock() == original.getBlock()) {
            state = Blocks.SPONGE.defaultBlockState();
        }
        return matchAxis(state, original);
    }

    /** {@code replacement} turned the way {@code original} is turned, if both are pillars. */
    static BlockState matchAxis(BlockState replacement, BlockState original) {
        if (original.hasProperty(BlockStateProperties.AXIS) && replacement.hasProperty(BlockStateProperties.AXIS)) {
            return replacement.setValue(BlockStateProperties.AXIS, original.getValue(BlockStateProperties.AXIS));
        }
        return replacement;
    }

    private static Block pick(RandomSource random, Block a, Block b, Block c, Block d) {
        return switch (random.nextInt(4)) {
            case 0 -> a;
            case 1 -> b;
            case 2 -> c;
            default -> d;
        };
    }
}
