package dev.kristian.nullmod.field;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The field's own copy of "is this spot indoors": room to stand, something solid overhead within a
 * few blocks, and in every horizontal direction (diagonals too) something solid within a few blocks
 * at both foot and head height. It is the shape of shelter IT respects ({@code ItEntity.isSheltered}),
 * written against plain block lookups so the generator's houses can be checked without a world:
 * every house must pass it from every spot of its floor.
 */
public final class FieldShelter {
    /** How far up a roof may be above the head. */
    public static final int ROOF_REACH = 8;
    /** How far away a wall may be in each direction. */
    public static final int WALL_REACH = 8;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Block lookup in world coordinates. */
    @FunctionalInterface
    public interface Blocks {
        BlockState get(int x, int y, int z);
    }

    private FieldShelter() {
    }

    /** True if something stops a player at this block (doors, panes and fences included). */
    public static boolean solid(BlockState state) {
        return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /** True if a player standing with their feet in block (x, y, z) is indoors. */
    public static boolean isIndoors(Blocks blocks, int x, int y, int z) {
        if (solid(blocks.get(x, y, z)) || solid(blocks.get(x, y + 1, z))) {
            return false;
        }
        boolean roof = false;
        for (int dy = 2; dy <= ROOF_REACH + 1 && !roof; dy++) {
            roof = solid(blocks.get(x, y + dy, z));
        }
        if (!roof) {
            return false;
        }
        for (int[] dir : DIRECTIONS) {
            for (int h = 0; h <= 1; h++) {
                boolean wall = false;
                for (int step = 1; step <= WALL_REACH && !wall; step++) {
                    wall = solid(blocks.get(x + dir[0] * step, y + h, z + dir[1] * step));
                }
                if (!wall) {
                    return false;
                }
            }
        }
        return true;
    }
}
