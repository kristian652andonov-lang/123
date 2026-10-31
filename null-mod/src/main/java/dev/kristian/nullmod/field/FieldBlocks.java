package dev.kristian.nullmod.field;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * The block states the field is made of. A separate class so they are created on first use (by
 * the chunk generator or a test), long after the game's blocks exist.
 * <p>
 * Every state is complete on its own: fences and panes already know their connections, doors
 * have both halves, crops are fully grown. The generator writes straight into chunk sections,
 * where no neighbour updates run, so nothing would fix a wrong state later.
 */
final class FieldBlocks {
    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    static final BlockState ROOTED_DIRT = Blocks.ROOTED_DIRT.defaultBlockState();
    static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();
    static final BlockState DIRT_PATH = Blocks.DIRT_PATH.defaultBlockState();
    /**
     * Dry farmland. Under a crop it never turns back to dirt ({@code #minecraft:maintains_farmland}),
     * and with no water and no rain it never changes at all, so the field sends no block updates.
     */
    static final BlockState FARMLAND = Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 0);
    /** Fully grown wheat: it never grows again, so it never random-ticks. */
    static final BlockState WHEAT = Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
    static final BlockState SHORT_GRASS = Blocks.SHORT_GRASS.defaultBlockState();
    static final BlockState DEAD_BUSH = Blocks.DEAD_BUSH.defaultBlockState();
    static final BlockState COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    static final BlockState MOSSY_COBBLESTONE = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    static final BlockState GLASS_PANE = Blocks.GLASS_PANE.defaultBlockState();
    static final BlockState CRAFTING_TABLE = Blocks.CRAFTING_TABLE.defaultBlockState();
    static final BlockState CAULDRON = Blocks.CAULDRON.defaultBlockState();
    static final BlockState COMPOSTER = Blocks.COMPOSTER.defaultBlockState();
    static final BlockState COBWEB = Blocks.COBWEB.defaultBlockState();
    static final BlockState CARVED_PUMPKIN = Blocks.CARVED_PUMPKIN.defaultBlockState();
    static final BlockState CHAIN = Blocks.CHAIN.defaultBlockState();

    private FieldBlocks() {
    }

    static BlockState log(Block block, Direction.Axis axis) {
        return block.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    static BlockState hay(Direction.Axis axis) {
        return log(Blocks.HAY_BLOCK, axis);
    }

    /** Roof stairs rising towards {@code facing}. */
    static BlockState stairs(Block block, Direction facing) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    static BlockState slab(Block block, SlabType type) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, type)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /** One half of a closed door. */
    static BlockState door(Block block, Direction facing, boolean lower) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, lower ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
    }

    /** A window pane joined to the wall on both sides: along X for walls facing north or south. */
    static BlockState pane(boolean alongX) {
        return GLASS_PANE
                .setValue(BlockStateProperties.NORTH, !alongX)
                .setValue(BlockStateProperties.SOUTH, !alongX)
                .setValue(BlockStateProperties.EAST, alongX)
                .setValue(BlockStateProperties.WEST, alongX)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    static BlockState fence(Block block, boolean north, boolean east, boolean south, boolean west) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.NORTH, north)
                .setValue(BlockStateProperties.EAST, east)
                .setValue(BlockStateProperties.SOUTH, south)
                .setValue(BlockStateProperties.WEST, west)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /** Lit candles: a dim, warm light that shows in the windows at night. */
    static BlockState candles(int count) {
        return Blocks.CANDLE.defaultBlockState()
                .setValue(BlockStateProperties.CANDLES, Math.max(1, Math.min(4, count)))
                .setValue(BlockStateProperties.LIT, true)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /** An open trapdoor standing upright against the side opposite {@code facing}. */
    static BlockState upright(Block block, Direction facing) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    static BlockState pumpkin(Direction facing) {
        return CARVED_PUMPKIN.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    static BlockState chain(Direction.Axis axis) {
        return CHAIN.setValue(BlockStateProperties.AXIS, axis).setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /** True for what the generator puts on top of the field (wheat, plants, air), which a structure may replace. */
    static boolean isOpenGround(BlockState state) {
        return state.isAir() || state.is(Blocks.WHEAT) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.DEAD_BUSH);
    }
}
