package dev.kristian.nullmod.field;

import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * One of the few old wooden buildings of the field: a farmhouse, a barn or a shed. Each is a closed
 * shelter: a floor, walls on every side (windows are glass panes), a door two blocks high, a
 * ceiling a few blocks above the floor and a roof over everything.
 * <p>
 * Buildings are drawn in a canonical orientation, door in the front wall on the south side (+Z),
 * and turned in 90 degree steps about their centre column, so they always stay aligned with the
 * block grid. {@code (x, z)} is the centre column in the world and {@code floor} the height of the
 * floor (the ground level of the flattened yard around it).
 *
 * @param key      deterministic identity (hash of the lattice cell)
 * @param rotation 0..3: quarter turns clockwise (0 = door facing south)
 * @param palette  0..1: which woods it is built of
 */
public record FieldHouse(long key, int x, int z, int floor, Type type, int rotation, int palette) {
    /** The kinds of building, with their wall half-extents (canonical X and Z) and wall height. */
    public enum Type {
        FARMHOUSE(3, 4, 4),
        BARN(4, 4, 4),
        SHED(2, 3, 3);

        final int halfX;
        final int halfZ;
        final int wallTop;

        Type(int halfX, int halfZ, int wallTop) {
            this.halfX = halfX;
            this.halfZ = halfZ;
            this.wallTop = wallTop;
        }

        /** Highest layer above the floor that holds a block (the ridge). */
        int roofTop() {
            return this == SHED ? wallTop + 1 : wallTop + halfX + 1;
        }
    }

    /** Ground around the walls that is cleared and levelled (no wheat). */
    public static final int YARD = 3;

    private static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90};
    /** Direction the front (door) faces for each rotation. */
    private static final Direction[] FRONT = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};

    public FieldHouse withRotation(int newRotation) {
        return new FieldHouse(key, x, z, floor, type, Math.floorMod(newRotation, 4), palette);
    }

    // ------------------------------------------------------------------ geometry in the world

    /** Half-extent of the walls along world X. */
    public int halfWidthX() {
        return (rotation & 1) == 0 ? type.halfX : type.halfZ;
    }

    /** Half-extent of the walls along world Z. */
    public int halfWidthZ() {
        return (rotation & 1) == 0 ? type.halfZ : type.halfX;
    }

    /** Direction the door faces (out of the house). */
    public Direction front() {
        return FRONT[rotation];
    }

    public Rotation blockRotation() {
        return ROTATIONS[rotation];
    }

    /** Column of the door. */
    public int doorX() {
        return x + front().getStepX() * type.halfZ;
    }

    public int doorZ() {
        return z + front().getStepZ() * type.halfZ;
    }

    /** Height of the door's lower half (the first block above the floor). */
    public int doorY() {
        return floor + 1;
    }

    /** True if the column is inside the walls (walls included). */
    public boolean inFootprint(int wx, int wz) {
        return Math.abs(wx - x) <= halfWidthX() && Math.abs(wz - z) <= halfWidthZ();
    }

    /** True if the column is strictly inside the walls: the floor a player can stand on. */
    public boolean inInterior(int wx, int wz) {
        return Math.abs(wx - x) < halfWidthX() && Math.abs(wz - z) < halfWidthZ();
    }

    public int padMinX() {
        return x - halfWidthX() - YARD;
    }

    public int padMaxX() {
        return x + halfWidthX() + YARD;
    }

    public int padMinZ() {
        return z - halfWidthZ() - YARD;
    }

    public int padMaxZ() {
        return z + halfWidthZ() + YARD;
    }

    /** Distance from a column to the levelled yard (0 inside it). */
    public double padDistance(int wx, int wz) {
        int dx = Math.max(0, Math.max(padMinX() - wx, wx - padMaxX()));
        int dz = Math.max(0, Math.max(padMinZ() - wz, wz - padMaxZ()));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** True if the column lies on the dirt path that runs from the door across the yard. */
    public boolean onPath(int wx, int wz) {
        Direction out = front();
        int along = (wx - doorX()) * out.getStepX() + (wz - doorZ()) * out.getStepZ();
        int side = (wx - doorX()) * out.getStepZ() - (wz - doorZ()) * out.getStepX();
        return side == 0 && along >= 1 && along <= YARD;
    }

    /** Bounds of everything the building itself places (walls plus the roof overhang). */
    public int minX() {
        return x - halfWidthX() - 1;
    }

    public int maxX() {
        return x + halfWidthX() + 1;
    }

    public int minZ() {
        return z - halfWidthZ() - 1;
    }

    public int maxZ() {
        return z + halfWidthZ() + 1;
    }

    public int topY() {
        return floor + type.roofTop();
    }

    // ------------------------------------------------------------------ blocks

    /**
     * The block of this building at a world position, or null where the building places nothing
     * (the yard keeps its ground, the inside stays air).
     */
    @Nullable
    public BlockState blockAt(int wx, int wy, int wz) {
        int dx = wx - x;
        int dz = wz - z;
        // World offset -> canonical offset (the inverse of the rotation).
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
        BlockState state = canonical(lx, wy - floor, lz);
        return state == null || rotation == 0 ? state : state.rotate(ROTATIONS[rotation]);
    }

    /** The building in its canonical orientation (door in the south wall at lz = +halfZ). */
    @Nullable
    private BlockState canonical(int lx, int ly, int lz) {
        int hx = type.halfX;
        int hz = type.halfZ;
        int top = type.wallTop;
        int ax = Math.abs(lx);
        int az = Math.abs(lz);
        if (ly < 0 || ly > type.roofTop() || ax > hx + 1 || az > hz + 1) {
            return null;
        }
        BlockState roof = type == Type.SHED ? flatRoof(lx, ly, lz, hx, hz, top) : gableRoof(lx, ly, lz, hx, hz, top);
        if (roof != null) {
            return roof;
        }
        if (ax > hx || az > hz) {
            return null;
        }
        boolean perimeter = ax == hx || az == hz;
        if (ly == 0) {
            if (perimeter) {
                return FieldGeneration.hash01(key, lx * 31 + lz, 7) < 0.3F ? FieldBlocks.MOSSY_COBBLESTONE : FieldBlocks.COBBLESTONE;
            }
            return type == Type.BARN ? FieldBlocks.COARSE_DIRT : floorBlock();
        }
        if (ly <= top) {
            if (!perimeter) {
                return furniture(lx, ly, lz);
            }
            if (ax == hx && az == hz) {
                return FieldBlocks.log(postBlock(), Direction.Axis.Y);
            }
            if (lx == 0 && lz == hz && ly <= 2) {
                return FieldBlocks.door(doorBlock(), Direction.NORTH, ly == 1);
            }
            if (ly == top) {
                return FieldBlocks.log(plateBlock(), az == hz ? Direction.Axis.X : Direction.Axis.Z);
            }
            if (window(lx, ly, lz)) {
                return FieldBlocks.pane(az == hz);
            }
            return wallBlock(lx, ly, lz);
        }
        // Above the walls (gable buildings only; the shed's roof was handled above).
        if (ly == top + 1 && ax < hx && az < hz) {
            return ceilingBlock();
        }
        if (az == hz && ax <= hx - (ly - top)) {
            // The triangle of the gable end, with a small window high in the front one.
            if (lz == hz && lx == 0 && ly == top + 2) {
                return FieldBlocks.pane(true);
            }
            return wallBlock(lx, ly, lz);
        }
        return null;
    }

    /** Gable roof with its ridge along Z, overhanging the walls by one block all round. */
    @Nullable
    private BlockState gableRoof(int lx, int ly, int lz, int hx, int hz, int top) {
        if (Math.abs(lz) > hz + 1) {
            return null;
        }
        if (lx == 0) {
            return ly == top + hx + 1 ? FieldBlocks.slab(roofSlabBlock(), SlabType.BOTTOM) : null;
        }
        int step = hx + 1 - Math.abs(lx);
        if (step < 0 || ly != top + step) {
            return null;
        }
        return FieldBlocks.stairs(roofStairsBlock(), lx < 0 ? Direction.EAST : Direction.WEST);
    }

    /** The shed's flat roof of slabs, overhanging by one block. */
    @Nullable
    private BlockState flatRoof(int lx, int ly, int lz, int hx, int hz, int top) {
        if (ly == top + 1 && Math.abs(lx) <= hx + 1 && Math.abs(lz) <= hz + 1) {
            return FieldBlocks.slab(roofSlabBlock(), SlabType.BOTTOM);
        }
        return null;
    }

    private boolean window(int lx, int ly, int lz) {
        int hx = type.halfX;
        int hz = type.halfZ;
        switch (type) {
            case SHED -> {
                return ly == 2 && (Math.abs(lx) == hx && lz == 0 || lz == -hz && lx == 0);
            }
            case BARN -> {
                if (ly != 2 && ly != 3) {
                    return false;
                }
                return Math.abs(lx) == hx && Math.abs(lz) == 2 || Math.abs(lz) == hz && Math.abs(lx) == 2;
            }
            default -> {
                if (ly != 2 && ly != 3) {
                    return false;
                }
                return Math.abs(lx) == hx && Math.abs(lz) == 2 || lz == -hz && lx == 0 || lz == hz && Math.abs(lx) == 2;
            }
        }
    }

    /** What stands inside: a little furniture, a candle burning, cobwebs high in the corners. */
    @Nullable
    private BlockState furniture(int lx, int ly, int lz) {
        switch (type) {
            case FARMHOUSE -> {
                if (lx == -2 && lz == -3) {
                    return ly == 1 ? FieldBlocks.CRAFTING_TABLE : ly == 2 ? FieldBlocks.candles(2) : null;
                }
                if (lx == 2 && lz == -3 && ly == 1) {
                    return FieldBlocks.CAULDRON;
                }
                if (lx == 2 && lz == 3 && ly == 1) {
                    return FieldBlocks.hay(Direction.Axis.Y);
                }
                if (ly == 4 && (lx == -2 && lz == 3 || lx == 2 && lz == -3)) {
                    return FieldBlocks.COBWEB;
                }
            }
            case BARN -> {
                if (lz == -3 && ly == 1 && lx <= -1) {
                    return FieldBlocks.hay(Direction.Axis.X);
                }
                if (lx == -3 && lz == -3 && ly == 2) {
                    return FieldBlocks.hay(Direction.Axis.X);
                }
                if (lx == -2 && lz == -3 && ly == 2) {
                    return FieldBlocks.candles(3);
                }
                if (lx == -3 && lz == -2 && ly == 1 || lx == 3 && lz <= -2 && ly == 1) {
                    return FieldBlocks.hay(Direction.Axis.Z);
                }
                if (ly == 4 && (lx == 3 && lz == 3 || lx == -3 && lz == 1)) {
                    return FieldBlocks.COBWEB;
                }
            }
            case SHED -> {
                if (lx == -1 && lz == -2 && ly == 1) {
                    return FieldBlocks.COMPOSTER;
                }
                if (lx == 1 && lz == -2) {
                    return ly == 1 ? FieldBlocks.slab(palette == 0 ? Blocks.SPRUCE_SLAB : Blocks.DARK_OAK_SLAB, SlabType.TOP)
                            : ly == 2 ? FieldBlocks.candles(1) : null;
                }
                if (lx == -1 && lz == 2 && ly == 3) {
                    return FieldBlocks.COBWEB;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ materials

    private boolean darkBarn() {
        return type == Type.BARN;
    }

    private BlockState wallBlock(int lx, int ly, int lz) {
        Block planks = darkBarn() || palette == 1 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS;
        // Here and there a board has been replaced with whatever was at hand.
        if (FieldGeneration.hash01(key, (lx * 17 + ly) * 31 + lz, 11) < 0.08F) {
            return planks == Blocks.SPRUCE_PLANKS ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
        }
        return planks.defaultBlockState();
    }

    private BlockState floorBlock() {
        return (palette == 1 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS).defaultBlockState();
    }

    private BlockState ceilingBlock() {
        return (darkBarn() || palette == 1 ? Blocks.SPRUCE_PLANKS : Blocks.DARK_OAK_PLANKS).defaultBlockState();
    }

    private Block postBlock() {
        return palette == 1 && !darkBarn() ? Blocks.SPRUCE_LOG : Blocks.DARK_OAK_LOG;
    }

    private Block plateBlock() {
        return palette == 1 && !darkBarn() ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.STRIPPED_DARK_OAK_LOG;
    }

    private Block roofStairsBlock() {
        return palette == 1 ? Blocks.SPRUCE_STAIRS : Blocks.DARK_OAK_STAIRS;
    }

    private Block roofSlabBlock() {
        return palette == 1 ? Blocks.SPRUCE_SLAB : Blocks.DARK_OAK_SLAB;
    }

    private Block doorBlock() {
        return palette == 1 || darkBarn() ? Blocks.DARK_OAK_DOOR : Blocks.SPRUCE_DOOR;
    }
}
