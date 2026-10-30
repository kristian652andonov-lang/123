package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.CorruptionPalette;
import dev.kristian.nullmod.world.WorldNoise;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;

/**
 * A small house the way the game used to build them: a box of planks or cobblestone with a flat
 * or simple gabled roof, one door, glass windows. Local coordinates: floor at y=0, x from 0 to
 * width-1, z from 0 to depth-1; the door is in the front wall (z=0) and one walks in facing +Z.
 * The {@link Spec} switches also build it wrong in the specific ways NULL needs.
 */
final class RetroHouse {
    enum Roof { FLAT, GABLE, NONE, PARTIAL }

    static final class Spec {
        int width = 5;
        int depth = 5;
        int wallHeight = 3;
        BlockState floor;
        BlockState wall;
        BlockState corner;
        BlockState base;
        BlockState roof;
        BlockState window;
        Block stairs = Blocks.OAK_STAIRS;
        Roof roofStyle = Roof.FLAT;
        boolean door = true;
        boolean torch;
        boolean floorBlocks = true;
        float cobwebs;
        float gaps;
        float moss;
        long seed;
        // ---- built wrong ----
        boolean windowsAtFloor;
        boolean doorInCeiling;
        boolean raisedDoor;
        boolean stairsInward;
        boolean solidInterior;
        boolean rotatedSegment;
        boolean mirroredWall;
        boolean doorOnRoof;
    }

    private RetroHouse() {
    }

    /** An ordinary old house with random proportions and materials. */
    static Spec random(RandomSource random) {
        Spec s = new Spec();
        s.width = 5 + random.nextInt(2) * 2;
        s.depth = 5 + random.nextInt(3);
        s.wallHeight = 3 + (random.nextFloat() < 0.3F ? 1 : 0);
        s.seed = random.nextLong();
        int kind = random.nextInt(10);
        BlockState planks = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        BlockState cobble = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        if (kind < 6) {
            s.floor = planks;
            s.wall = planks;
            s.corner = CorruptionPalette.retroLog(Direction.Axis.Y);
            s.base = cobble;
        } else if (kind < 9) {
            s.floor = cobble;
            s.wall = cobble;
            s.corner = cobble;
            s.base = cobble;
        } else {
            s.floor = planks;
            s.wall = NullBlocks.RETRO_BRICKS.get().defaultBlockState();
            s.corner = NullBlocks.RETRO_BRICKS.get().defaultBlockState();
            s.base = cobble;
        }
        s.roof = planks;
        s.window = NullBlocks.RETRO_GLASS.get().defaultBlockState();
        s.roofStyle = random.nextFloat() < 0.35F ? Roof.GABLE : Roof.FLAT;
        return s;
    }

    /** Total height above the floor block, roof included. */
    static int height(Spec s) {
        int roof = switch (s.roofStyle) {
            case FLAT, PARTIAL -> 1;
            case GABLE -> (s.width + 1) / 2 + 1;
            case NONE -> 0;
        };
        return s.wallHeight + 1 + roof + (s.doorOnRoof ? 2 : 0);
    }

    static Blueprint build(Spec s, RandomSource random) {
        Blueprint bp = new Blueprint();
        build(bp, s, random);
        return bp;
    }

    static void build(Blueprint bp, Spec s, RandomSource random) {
        int w = Math.max(4, s.width);
        int d = Math.max(4, s.depth);
        int h = Math.max(3, s.wallHeight);

        if (s.floorBlocks) {
            bp.fill(0, 0, 0, w - 1, 0, d - 1, s.floor);
        }
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edgeX = x == 0 || x == w - 1;
                    boolean edgeZ = z == 0 || z == d - 1;
                    if (!edgeX && !edgeZ) {
                        continue;
                    }
                    BlockState state = edgeX && edgeZ ? s.corner : (y == 1 ? s.base : s.wall);
                    bp.set(x, y, z, state);
                }
            }
        }

        // Windows, at the usual height or, built wrong, at the player's feet.
        int windowY = s.windowsAtFloor ? 1 : 2;
        bp.set(0, windowY, d / 2, s.window);
        bp.set(w - 1, windowY, d / 2, s.window);
        bp.set(w / 2, windowY, d - 1, s.window);
        if (d >= 7) {
            bp.set(0, windowY, d / 2 - 2, s.window);
            bp.set(w - 1, windowY, d / 2 - 2, s.window);
        }

        // The roof.
        switch (s.roofStyle) {
            case FLAT -> bp.fill(-1, h + 1, -1, w, h + 1, d, s.roof);
            case PARTIAL -> {
                for (int x = -1; x <= w; x++) {
                    for (int z = -1; z <= d; z++) {
                        if (WorldNoise.hash01(s.seed, x >> 1, z >> 1) > 0.45) {
                            bp.set(x, h + 1, z, s.roof);
                        }
                    }
                }
            }
            case GABLE -> gable(bp, s, w, d, h);
            case NONE -> {
            }
        }

        // The door.
        int doorX = w / 2;
        if (s.raisedDoor && h >= 4) {
            bp.remove(doorX, h - 1, 0);
            bp.remove(doorX, h, 0);
            bp.door(doorX, h - 1, 0, Blocks.OAK_DOOR, Direction.SOUTH, DoorHingeSide.LEFT, false, false);
        } else {
            bp.remove(doorX, 1, 0);
            bp.remove(doorX, 2, 0);
            if (s.door) {
                bp.door(doorX, 1, 0, Blocks.OAK_DOOR, Direction.SOUTH, DoorHingeSide.LEFT, false, s.floorBlocks);
            }
        }

        // Inside.
        if (s.solidInterior) {
            bp.fill(1, 1, 1, w - 2, h, d - 2, s.wall);
        } else {
            if (s.torch) {
                bp.attach(1, 2, d - 2, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
            }
            if (s.cobwebs > 0.0F) {
                int[][] corners = {{1, 1}, {w - 2, 1}, {1, d - 2}, {w - 2, d - 2}};
                for (int[] c : corners) {
                    if (random.nextFloat() < s.cobwebs) {
                        bp.loose(c[0], h, c[1], Blocks.COBWEB.defaultBlockState());
                    }
                }
                for (int x = 1; x <= w - 2; x++) {
                    for (int z = 1; z <= d - 2; z++) {
                        if (random.nextFloat() < s.cobwebs * 0.08F) {
                            bp.loose(x, 1 + random.nextInt(h), z, Blocks.COBWEB.defaultBlockState());
                        }
                    }
                }
            }
            if (s.doorInCeiling) {
                // A door hanging from the ceiling, opening onto nothing.
                bp.door(w / 2, h - 1, d / 2, Blocks.OAK_DOOR, Direction.EAST, DoorHingeSide.RIGHT, false, false);
            }
        }
        if (s.doorOnRoof && (s.roofStyle == Roof.FLAT || s.roofStyle == Roof.PARTIAL)) {
            bp.set(w / 2, h + 1, d / 2, s.roof);
            bp.door(w / 2, h + 2, d / 2, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT, false, true);
        }
        if (s.rotatedSegment) {
            rotateSideWall(bp, s, w, d, h);
        }
        if (s.mirroredWall) {
            mirrorSideWall(bp, d, h);
        }
        if (s.moss > 0.0F) {
            BlockState cobble = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
            BlockState mossy = NullBlocks.RETRO_MOSSY_COBBLESTONE.get().defaultBlockState();
            for (Blueprint.Cell cell : bp.cells()) {
                if (cell.state() == cobble && WorldNoise.hash01(s.seed ^ 0x6D6F7373L, cell.x(), cell.y(), cell.z()) < s.moss) {
                    bp.set(cell.x(), cell.y(), cell.z(), mossy, cell.mode());
                }
            }
        }
        if (s.gaps > 0.0F) {
            long seed = s.seed ^ 0x67617073L;
            bp.removeIf(cell -> cell.y() > 0 && WorldNoise.hash01(seed, cell.x() >> 1, cell.y() >> 1, cell.z() >> 1) < s.gaps);
        }
    }

    private static void gable(Blueprint bp, Spec s, int w, int d, int h) {
        Direction west = s.stairsInward ? Direction.WEST : Direction.EAST;
        Direction east = s.stairsInward ? Direction.EAST : Direction.WEST;
        for (int i = 0; ; i++) {
            int y = h + 1 + i;
            int left = -1 + i;
            int right = w - i;
            if (left > right) {
                break;
            }
            for (int z = -1; z <= d; z++) {
                if (left == right) {
                    bp.set(left, y, z, s.roof);
                } else {
                    bp.set(left, y, z, Blueprint.stairs(s.stairs, west, false));
                    bp.set(right, y, z, Blueprint.stairs(s.stairs, east, false));
                }
            }
            // Fill the triangle of the front and back walls under this roof layer.
            for (int x = left + 1; x <= right - 1; x++) {
                bp.set(x, y, 0, s.wall);
                bp.set(x, y, d - 1, s.wall);
            }
            if (i == 0) {
                // Raise the side walls by one so the eaves close against them.
                for (int z = 0; z < d; z++) {
                    bp.set(0, y, z, s.wall);
                    bp.set(w - 1, y, z, s.wall);
                }
            }
            if (left + 1 >= right) {
                break;
            }
        }
    }

    /**
     * The left wall was mirrored to the wrong side of a line two blocks outside the house: it
     * stands there on its own, and the house is open where it should be.
     */
    private static void mirrorSideWall(Blueprint bp, int d, int h) {
        for (int y = 1; y <= h; y++) {
            for (int z = 1; z <= d - 2; z++) {
                BlockState state = bp.get(0, y, z);
                bp.remove(0, y, z);
                if (state != null) {
                    bp.set(-3, y, d - 1 - z, state);
                }
            }
        }
    }

    /**
     * The right wall is not where it belongs: it was built turned by ninety degrees, sticking out
     * sideways from the front corner, and the house is open on that side.
     */
    private static void rotateSideWall(Blueprint bp, Spec s, int w, int d, int h) {
        int x = w - 1;
        for (int y = 1; y <= h; y++) {
            for (int z = 1; z <= d - 2; z++) {
                BlockState state = bp.get(x, y, z);
                bp.remove(x, y, z);
                if (state != null) {
                    bp.set(x + z, y, 0, state);
                }
            }
        }
    }
}
