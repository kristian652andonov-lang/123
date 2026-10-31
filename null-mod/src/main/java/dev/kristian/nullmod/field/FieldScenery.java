package dev.kristian.nullmod.field;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * A little scenery out in the wheat: hay bales, a scarecrow, a broken fence line, a dead tree or an
 * abandoned cart. Placed by {@link FieldGeneration} on level ground well away from the houses.
 *
 * @param key      deterministic identity (hash of its scenery cell), also the order of writing
 * @param kind     what it is
 * @param x        anchor column (centre; the first post of a fence line)
 * @param z        anchor column
 * @param ground   ground level at the anchor (the farmland height)
 * @param rotation 0..3: facing / orientation (fence lines: 0 runs along X, 1 along Z)
 * @param size     bales, fence length or tree height
 */
public record FieldScenery(long key, Kind kind, int x, int z, int ground, int rotation, int size) {
    public enum Kind { HAY, SCARECROW, FENCE, TREE, CART }

    private static final Direction[] FACINGS = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};

    public int minX() {
        return switch (kind) {
            case FENCE -> x;
            case TREE -> x - 3;
            case SCARECROW -> x - 1;
            default -> x - 2;
        };
    }

    public int maxX() {
        return switch (kind) {
            case FENCE -> rotation == 0 ? x + size - 1 : x;
            case TREE -> x + 3;
            case SCARECROW -> x + 1;
            default -> x + 2;
        };
    }

    public int minZ() {
        return switch (kind) {
            case FENCE -> z;
            case TREE -> z - 3;
            case SCARECROW -> z - 1;
            default -> z - 2;
        };
    }

    public int maxZ() {
        return switch (kind) {
            case FENCE -> rotation == 0 ? z : z + size - 1;
            case TREE -> z + 3;
            case SCARECROW -> z + 1;
            default -> z + 2;
        };
    }

    /** Columns that must all share the anchor's ground height (checked before placing). */
    boolean needsLevelGround(int wx, int wz) {
        return switch (kind) {
            case HAY, CART -> Math.abs(wx - x) <= 2 && Math.abs(wz - z) <= 2;
            case TREE, SCARECROW -> Math.abs(wx - x) <= 1 && Math.abs(wz - z) <= 1;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes this item's blocks in one column. {@code columnGround} is that column's ground level;
     * the sink already holds the terrain and anything written before this item.
     */
    void writeColumn(long seed, int wx, int wz, int columnGround, FieldGeneration.Sink sink) {
        switch (kind) {
            case HAY -> hay(wx, wz, sink);
            case SCARECROW -> scarecrow(wx, wz, sink);
            case FENCE -> fence(seed, wx, wz, columnGround, sink);
            case TREE -> tree(wx, wz, columnGround, sink);
            case CART -> cart(wx, wz, sink);
        }
    }

    /** One to four bales, some stacked, lying in different directions. */
    private void hay(int wx, int wz, FieldGeneration.Sink sink) {
        int dx = wx - x;
        int dz = wz - z;
        // Bale i sits at one of these offsets around the anchor.
        int[][] spots = {{0, 0}, {1, 0}, {0, 1}, {-1, 1}};
        for (int i = 0; i < Math.min(size, spots.length); i++) {
            int[] spot = spots[(i + rotation) % spots.length];
            if (dx != spot[0] || dz != spot[1]) {
                continue;
            }
            Direction.Axis axis = FieldGeneration.hash01(key, i, 3) < 0.5F ? Direction.Axis.X : Direction.Axis.Z;
            sink.set(wx, ground, wz, FieldBlocks.COARSE_DIRT);
            sink.set(wx, ground + 1, wz, FieldBlocks.hay(axis));
            if (i == 0 && size >= 3) {
                sink.set(wx, ground + 2, wz, FieldBlocks.hay(axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X));
            }
        }
    }

    /** A post, a straw body, arms, and a carved face; most of them watch the way people come from. */
    private void scarecrow(int wx, int wz, FieldGeneration.Sink sink) {
        Direction facing = FACINGS[rotation];
        boolean armsAlongX = facing.getAxis() == Direction.Axis.Z;
        int dx = wx - x;
        int dz = wz - z;
        int post = 1 + (size & 1);
        int body = ground + post + 1;
        if (dx == 0 && dz == 0) {
            sink.set(wx, ground, wz, FieldBlocks.COARSE_DIRT);
            for (int y = ground + 1; y < body; y++) {
                sink.set(wx, y, wz, FieldBlocks.fence(Blocks.SPRUCE_FENCE, false, false, false, false));
            }
            sink.set(wx, body, wz, FieldBlocks.hay(Direction.Axis.Y));
            sink.set(wx, body + 1, wz, FieldBlocks.pumpkin(facing));
            return;
        }
        BlockState arm = null;
        if (armsAlongX && dz == 0 && Math.abs(dx) == 1) {
            // An arm: a fence piece joined to the body.
            arm = FieldBlocks.fence(Blocks.SPRUCE_FENCE, false, dx < 0, false, dx > 0);
        } else if (!armsAlongX && dx == 0 && Math.abs(dz) == 1) {
            arm = FieldBlocks.fence(Blocks.SPRUCE_FENCE, dz > 0, false, dz < 0, false);
        }
        if (arm != null) {
            // Nothing grows in the shadow of the arms (crops need the open sky).
            clearCrop(wx, wz, ground, sink);
            sink.set(wx, body, wz, arm);
        }
    }

    /**
     * An old fence line across the field, following the ground, with posts missing here and there.
     * Joints are decided from the line alone (a neighbour post at the same height, not missing), so
     * every chunk agrees on them.
     */
    private void fence(long seed, int wx, int wz, int columnGround, FieldGeneration.Sink sink) {
        int index = rotation == 0 ? wx - x : wz - z;
        if (index < 0 || index >= size || missingPost(index)) {
            return;
        }
        int y = columnGround + 1;
        if (!FieldBlocks.isOpenGround(sink.get(wx, y, wz)) || !FieldBlocks.isOpenGround(sink.get(wx, y + 1, wz))) {
            return;
        }
        // Fence lines keep clear of the houses' levelled yards, so their ground is the natural one.
        int stepX = rotation == 0 ? 1 : 0;
        int stepZ = rotation == 0 ? 0 : 1;
        boolean before = joins(index - 1, columnGround, FieldGeneration.rawGround(seed, wx - stepX, wz - stepZ));
        boolean after = joins(index + 1, columnGround, FieldGeneration.rawGround(seed, wx + stepX, wz + stepZ));
        BlockState state = rotation == 0
                ? FieldBlocks.fence(Blocks.OAK_FENCE, false, after, false, before)
                : FieldBlocks.fence(Blocks.OAK_FENCE, before, false, after, false);
        sink.set(wx, columnGround, wz, FieldBlocks.COARSE_DIRT);
        sink.set(wx, y, wz, state);
        // Every few posts a second rail-post stands taller, as old fences do.
        if (index % 6 == 0 && FieldGeneration.hash01(key, index, 5) < 0.5F) {
            sink.set(wx, y + 1, wz, FieldBlocks.fence(Blocks.OAK_FENCE, false, false, false, false));
        }
    }

    private boolean joins(int neighbour, int ground, int neighbourGround) {
        return neighbour >= 0 && neighbour < size && !missingPost(neighbour) && neighbourGround == ground;
    }

    private boolean missingPost(int index) {
        return index > 0 && index < size - 1 && FieldGeneration.hash01(key, index, 9) < 0.12F;
    }

    /** A dead tree: a black trunk, a few bare branches, roots breaking the ground. */
    private void tree(int wx, int wz, int columnGround, FieldGeneration.Sink sink) {
        int dx = wx - x;
        int dz = wz - z;
        int height = size;
        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            sink.set(wx, ground, wz, (dx == 0 && dz == 0) || FieldGeneration.hash01(key, dx * 3 + dz, 13) < 0.5F
                    ? FieldBlocks.ROOTED_DIRT : FieldBlocks.COARSE_DIRT);
            if (!(dx == 0 && dz == 0) && FieldBlocks.isOpenGround(sink.get(wx, ground + 1, wz))) {
                sink.set(wx, ground + 1, wz, FieldGeneration.hash01(key, dx * 3 + dz, 17) < 0.3F
                        ? FieldBlocks.DEAD_BUSH : FieldBlocks.AIR);
            }
        }
        if (dx == 0 && dz == 0) {
            for (int y = ground + 1; y <= ground + height; y++) {
                sink.set(wx, y, wz, FieldBlocks.log(Blocks.DARK_OAK_LOG, Direction.Axis.Y));
            }
            return;
        }
        // Branches: up to four, each reaching out 1-3 blocks from the upper trunk and ending in a twig.
        for (int b = 0; b < 4; b++) {
            if (FieldGeneration.hash01(key, b, 21) < 0.25F) {
                continue;
            }
            Direction dir = FACINGS[(b + rotation) & 3];
            int length = 1 + (int) (FieldGeneration.hash01(key, b, 23) * 3.0F);
            // High enough to walk under, and nothing grows beneath it.
            int y = Math.max(ground + 3, ground + height - 1 - (int) (FieldGeneration.hash01(key, b, 29) * 3.0F));
            for (int step = 1; step <= length; step++) {
                if (wx == x + dir.getStepX() * step && wz == z + dir.getStepZ() * step) {
                    clearCrop(wx, wz, columnGround, sink);
                    sink.set(wx, y, wz, FieldBlocks.log(Blocks.DARK_OAK_LOG, dir.getAxis()));
                    if (step == length) {
                        sink.set(wx, y + 1, wz, FieldBlocks.log(Blocks.DARK_OAK_LOG, Direction.Axis.Y));
                    }
                }
            }
        }
    }

    /** Bare ground in one column: the crop goes, and its farmland with it (it would dry out to dirt). */
    private static void clearCrop(int wx, int wz, int columnGround, FieldGeneration.Sink sink) {
        if (sink.get(wx, columnGround, wz).is(Blocks.FARMLAND)) {
            sink.set(wx, columnGround, wz, FieldBlocks.COARSE_DIRT);
        }
        if (FieldBlocks.isOpenGround(sink.get(wx, columnGround + 1, wz))) {
            sink.set(wx, columnGround + 1, wz, FieldBlocks.AIR);
        }
    }

    /**
     * A small wooden cart left in the field: a raised bed of boards and rusted plates, upright
     * planks for wheels, a chain lying where the shaft was.
     */
    private void cart(int wx, int wz, FieldGeneration.Sink sink) {
        // Canonical cart: bed at local x 0..2, z 0..1, wheels beside it at z -1 and 2.
        int dx = wx - x;
        int dz = wz - z;
        int lx;
        int lz;
        switch (rotation) {
            case 1 -> {
                lx = dz;
                lz = -dx;
            }
            case 2 -> {
                lx = -dx;
                lz = -dz;
            }
            case 3 -> {
                lx = -dz;
                lz = dx;
            }
            default -> {
                lx = dx;
                lz = dz;
            }
        }
        if (Math.abs(dx) > 2 || Math.abs(dz) > 2) {
            return;
        }
        sink.set(wx, ground, wz, FieldBlocks.COARSE_DIRT);
        if (FieldBlocks.isOpenGround(sink.get(wx, ground + 1, wz))) {
            sink.set(wx, ground + 1, wz, FieldBlocks.AIR);
        }
        BlockState state = null;
        if (lx >= 0 && lx <= 2 && lz >= 0 && lz <= 1) {
            boolean rusted = FieldGeneration.hash01(key, lx * 2 + lz, 31) < 0.4F;
            state = FieldBlocks.slab(rusted ? Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB : Blocks.SPRUCE_SLAB, SlabType.TOP);
        } else if ((lx == 0 || lx == 2) && (lz == -1 || lz == 2)) {
            state = FieldBlocks.upright(Blocks.DARK_OAK_TRAPDOOR, lz < 0 ? Direction.NORTH : Direction.SOUTH);
        } else if (lx == -1 && lz == 0) {
            state = FieldBlocks.chain(Direction.Axis.X);
        }
        if (state != null) {
            sink.set(wx, ground + 1, wz, rotation == 0 ? state : state.rotate(FieldGeneration.ROTATIONS[rotation]));
        }
    }
}
