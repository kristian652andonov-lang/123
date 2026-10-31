package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.CorruptionPalette;
import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;

/**
 * Buildings the game got wrong:
 * <ul>
 * <li>assembled wrong: walls turned or mirrored out of place, windows at foot level, a door
 * hanging from the ceiling or standing on the roof, a door too high to reach, roof stairs facing
 * into the walls;</li>
 * <li>wrong dimensions: a house two blocks wide and twenty tall, an enormous single room;</li>
 * <li>leading nowhere: stairs climbing into empty air, a door that opens onto a solid wall;</li>
 * <li>extending underground: an ordinary house whose floor opens onto a ladder shaft that goes
 * thirty blocks down into the stone and ends at a brick wall.</li>
 * </ul>
 */
final class WrongBuildingBuilder {
    enum Variant { MISASSEMBLED, TALL_NARROW, ENORMOUS_ROOM, STAIRS_TO_NOTHING, DOOR_INTO_WALL, UNDERGROUND_HOUSE }

    /** A blueprint placed on the ground: where it ended up. */
    private record Placed(PlacementJob job, BlockPos origin, int floorY, int[] rect, Rotation rotation) {
    }

    private WrongBuildingBuilder() {
    }

    static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        boolean natural = NullConfig.mayReplaceNatural();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            Variant variant = Variant.values()[random.nextInt(Variant.values().length)];
            if (variant == Variant.UNDERGROUND_HOUSE && !natural) {
                variant = Variant.DOOR_INTO_WALL;
            }
            double min = req.minDistance(50.0, 24.0);
            BlockPos ground = LocationFinder.findGround(level, req.player(), random, min, req.maxDistance(110.0, min),
                    req.view(LocationFinder.View.HIDDEN), 4, pos -> true);
            if (ground == null) {
                continue;
            }
            PlacementJob job = switch (variant) {
                case MISASSEMBLED -> misassembled(level, ground, random, req.forced());
                case TALL_NARROW -> tallNarrow(level, ground, random, req.forced());
                case ENORMOUS_ROOM -> enormousRoom(level, ground, random, req.forced());
                case STAIRS_TO_NOTHING -> stairsToNothing(level, ground, random, req.forced());
                case DOOR_INTO_WALL -> doorIntoWall(level, ground, random, req.forced());
                case UNDERGROUND_HOUSE -> undergroundHouse(level, ground, random, req.forced());
            };
            if (job != null && CorruptedStructureGenerator.submit(req, job, "wrong_building", describe(variant), ground)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Variant variant) {
        return switch (variant) {
            case MISASSEMBLED -> "a house built wrong";
            case TALL_NARROW -> "a house two blocks wide and twenty tall";
            case ENORMOUS_ROOM -> "a room far too big";
            case STAIRS_TO_NOTHING -> "stairs up to nothing";
            case DOOR_INTO_WALL -> "a door into a wall";
            case UNDERGROUND_HOUSE -> "a house with a shaft under its floor";
        };
    }

    // ------------------------------------------------------------------ variants

    @Nullable
    private static PlacementJob misassembled(ServerLevel level, BlockPos ground, RandomSource random, boolean forced) {
        RetroHouse.Spec s = RetroHouse.random(random);
        s.wallHeight = 4;
        int mistakes = 0;
        for (int round = 0; round < 8 && mistakes < 2 + random.nextInt(2); round++) {
            switch (random.nextInt(7)) {
                case 0 -> mistakes += flag(!s.windowsAtFloor, () -> s.windowsAtFloor = true);
                case 1 -> mistakes += flag(!s.doorInCeiling, () -> s.doorInCeiling = true);
                case 2 -> {
                    if (!s.stairsInward) {
                        s.roofStyle = RetroHouse.Roof.GABLE;
                        s.stairsInward = true;
                        mistakes++;
                    }
                }
                case 3 -> mistakes += flag(!s.rotatedSegment && !s.mirroredWall, () -> s.rotatedSegment = true);
                case 4 -> mistakes += flag(!s.mirroredWall && !s.rotatedSegment, () -> s.mirroredWall = true);
                case 5 -> mistakes += flag(!s.raisedDoor, () -> s.raisedDoor = true);
                default -> {
                    if (!s.doorOnRoof && s.roofStyle != RetroHouse.Roof.GABLE) {
                        s.doorOnRoof = true;
                        mistakes++;
                    }
                }
            }
        }
        if (s.doorOnRoof && s.roofStyle == RetroHouse.Roof.GABLE) {
            s.doorOnRoof = false;
        }
        Blueprint bp = RetroHouse.build(s, random);
        Placed placed = placeOnGround(level, "wrong_building", bp, ground, s.width, s.depth, forced ? 5 : 3, Rotation.getRandom(random), null);
        return placed == null ? null : placed.job();
    }

    private static int flag(boolean allowed, Runnable set) {
        if (!allowed) {
            return 0;
        }
        set.run();
        return 1;
    }

    @Nullable
    private static PlacementJob tallNarrow(ServerLevel level, BlockPos ground, RandomSource random, boolean forced) {
        BlockState planks = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        BlockState log = CorruptionPalette.retroLog(Direction.Axis.Y);
        BlockState glass = NullBlocks.RETRO_GLASS.get().defaultBlockState();
        int h = 18 + random.nextInt(5);
        Blueprint bp = new Blueprint();
        bp.fill(0, 0, 0, 3, 0, 3, planks);
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    boolean edgeX = x == 0 || x == 3;
                    boolean edgeZ = z == 0 || z == 3;
                    if (edgeX || edgeZ) {
                        bp.set(x, y, z, edgeX && edgeZ ? log : planks);
                    }
                }
            }
        }
        bp.fill(-1, h + 1, -1, 4, h + 1, 4, planks);
        bp.remove(1, 1, 0);
        bp.remove(1, 2, 0);
        bp.door(1, 1, 0, Blocks.OAK_DOOR, Direction.SOUTH, DoorHingeSide.LEFT, false, true);
        int windows = 3 + random.nextInt(3);
        for (int i = 0; i < windows; i++) {
            int y = 3 + random.nextInt(h - 3);
            switch (random.nextInt(4)) {
                case 0 -> bp.set(1 + random.nextInt(2), y, 0, glass);
                case 1 -> bp.set(1 + random.nextInt(2), y, 3, glass);
                case 2 -> bp.set(0, y, 1 + random.nextInt(2), glass);
                default -> bp.set(3, y, 1 + random.nextInt(2), glass);
            }
        }
        // A ladder up the back wall that stops well short of the top.
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
        for (int y = 1; y <= h - 6; y++) {
            bp.attach(2, y, 2, ladder);
        }
        bp.attach(1, h, 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
        Placed placed = placeOnGround(level, "wrong_building", bp, ground, 4, 4, forced ? 4 : 2, Rotation.getRandom(random), null);
        return placed == null ? null : placed.job();
    }

    @Nullable
    private static PlacementJob enormousRoom(ServerLevel level, BlockPos ground, RandomSource random, boolean forced) {
        BlockState planks = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        BlockState cobble = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        BlockState log = CorruptionPalette.retroLog(Direction.Axis.Y);
        int size = 17 + random.nextInt(4) * 2;
        int h = 7 + random.nextInt(3);
        Blueprint bp = new Blueprint();
        bp.fill(0, 0, 0, size - 1, 0, size - 1, planks);
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean edgeX = x == 0 || x == size - 1;
                    boolean edgeZ = z == 0 || z == size - 1;
                    if (edgeX || edgeZ) {
                        bp.set(x, y, z, edgeX && edgeZ ? log : (y == 1 ? cobble : planks));
                    }
                }
            }
        }
        bp.fill(0, h + 1, 0, size - 1, h + 1, size - 1, planks);
        int mid = size / 2;
        bp.remove(mid, 1, 0);
        bp.remove(mid, 2, 0);
        bp.door(mid, 1, 0, Blocks.OAK_DOOR, Direction.SOUTH, DoorHingeSide.LEFT, false, true);
        // One window, far too high to look out of.
        bp.set(mid, h, size - 1, NullBlocks.RETRO_GLASS.get().defaultBlockState());
        // And one torch in the exact middle of the floor.
        bp.attach(mid, 1, mid, Blocks.TORCH.defaultBlockState());
        Placed placed = placeOnGround(level, "wrong_building", bp, ground, size, size, forced ? 5 : 3, Rotation.getRandom(random), null);
        return placed == null ? null : placed.job();
    }

    @Nullable
    private static PlacementJob stairsToNothing(ServerLevel level, BlockPos ground, RandomSource random, boolean forced) {
        BlockState cobble = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        int steps = 10 + random.nextInt(7);
        Blueprint bp = new Blueprint();
        for (int k = 0; k < steps; k++) {
            for (int x = 0; x < 2; x++) {
                for (int y = 0; y < k; y++) {
                    bp.set(x, y, k, cobble);
                }
                bp.set(x, k, k, Blueprint.stairs(Blocks.COBBLESTONE_STAIRS, Direction.SOUTH, false));
            }
        }
        if (random.nextBoolean()) {
            // A door at the top, standing on its own, opening onto the sky.
            bp.set(0, steps - 1, steps, cobble);
            bp.set(1, steps - 1, steps, cobble);
            bp.door(0, steps, steps, Blocks.OAK_DOOR, Direction.SOUTH, DoorHingeSide.LEFT, false, true);
        }
        Placed placed = placeOnGround(level, "wrong_building", bp, ground, 2, steps + 1, forced ? 5 : 3, Rotation.getRandom(random), null);
        return placed == null ? null : placed.job();
    }

    @Nullable
    private static PlacementJob doorIntoWall(ServerLevel level, BlockPos ground, RandomSource random, boolean forced) {
        RetroHouse.Spec s = RetroHouse.random(random);
        s.solidInterior = true;
        s.door = true;
        s.wall = random.nextBoolean() ? NullBlocks.RETRO_BRICKS.get().defaultBlockState() : s.wall;
        Blueprint bp = RetroHouse.build(s, random);
        Placed placed = placeOnGround(level, "wrong_building", bp, ground, s.width, s.depth, forced ? 5 : 3, Rotation.getRandom(random), null);
        return placed == null ? null : placed.job();
    }

    @Nullable
    private static PlacementJob undergroundHouse(ServerLevel level, BlockPos ground, RandomSource random, boolean forced) {
        RetroHouse.Spec s = RetroHouse.random(random);
        s.width = 5;
        s.depth = 6;
        s.wallHeight = 3;
        s.roofStyle = RetroHouse.Roof.FLAT;
        s.torch = true;
        Blueprint bp = RetroHouse.build(s, random);
        int sx = 2;
        int sz = 3;
        bp.remove(sx, 0, sz);
        Rotation rotation = Rotation.getRandom(random);
        BlockPos center = Blueprint.toWorld(BlockPos.ZERO, s.width / 2, 0, s.depth / 2, rotation, Mirror.NONE);
        BlockPos probe = new BlockPos(ground.getX() - center.getX(), 0, ground.getZ() - center.getZ());
        BlockPos shaftTop = Blueprint.toWorld(probe, sx, 0, sz, rotation, Mirror.NONE);
        Set<Long> skip = new HashSet<>();
        skip.add(BlockPos.asLong(shaftTop.getX(), 0, shaftTop.getZ()));
        Placed placed = placeOnGround(level, "wrong_building", bp, ground, s.width, s.depth, forced ? 4 : 2, rotation, skip);
        if (placed == null) {
            return null;
        }
        int wx = shaftTop.getX();
        int wz = shaftTop.getZ();
        int gs = LocationFinder.groundY(level, wx, wz);
        if (gs == LocationFinder.NONE) {
            return null;
        }
        int depth = 26 + random.nextInt(7);
        int bottom = gs - depth;
        if (bottom <= level.getMinBuildHeight() + 6) {
            return null;
        }
        Direction back = rotation.rotate(Direction.NORTH);
        Direction forward = rotation.rotate(Direction.SOUTH);
        List<BlockPos> carve = new ArrayList<>();
        for (int y = gs; y >= bottom; y--) {
            carve.add(new BlockPos(wx, y, wz));
        }
        BlockPos base = new BlockPos(wx, bottom, wz);
        for (int step = 1; step <= 2; step++) {
            carve.add(base.relative(forward, step));
            carve.add(base.relative(forward, step).above());
        }
        if (!Siting.carveSafe(level, carve, 3, 2, false)) {
            return null;
        }
        PlacementJob job = placed.job();
        for (BlockPos p : carve) {
            job.add(p, Blocks.AIR.defaultBlockState(), SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.NONE);
        }
        // The wall at the end: bricks where only stone should be.
        BlockState bricks = NullBlocks.RETRO_BRICKS.get().defaultBlockState();
        BlockPos end = base.relative(forward, 3);
        job.add(end, bricks, SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.NONE);
        job.add(end.above(), bricks, SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.NONE);
        // The ladder, from the bottom of the shaft up into the house.
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, forward);
        for (int y = bottom; y <= placed.floorY(); y++) {
            job.add(new BlockPos(wx, y, wz), ladder, SafeBlockPlacer.Mode.PLACE_INTO_AIR, SafeBlockPlacer.Support.SURVIVE);
        }
        if (random.nextFloat() < 0.5F) {
            // A note on the bricks, facing back up the shaft.
            Blueprint note = new Blueprint();
            note.wallSign(0, 0, 0, back, Blueprint.text(List.of(Component.empty(),
                    Component.translatable("null.world.sign.not_yet.1"), Component.empty(), Component.empty()), DyeColor.BLACK, false));
            note.appendTo(job, base.relative(forward, 2).above(), Rotation.NONE, Mirror.NONE);
        }
        return job;
    }

    // ------------------------------------------------------------------ placing on the ground

    /**
     * Puts a blueprint with a {@code w} x {@code d} floor on flat-enough natural ground, centred
     * on {@code ground}, with a random rotation and old cobblestone foundations underneath.
     */
    @Nullable
    private static Placed placeOnGround(ServerLevel level, String name, Blueprint bp, BlockPos ground, int w, int d,
                                        int maxSpread, Rotation rotation, @Nullable Set<Long> skipColumns) {
        BlockPos center = Blueprint.toWorld(BlockPos.ZERO, w / 2, 0, d / 2, rotation, Mirror.NONE);
        BlockPos probe = new BlockPos(ground.getX() - center.getX(), 0, ground.getZ() - center.getZ());
        int[] rect = Siting.rect(probe, w, d, rotation);
        if (!LocationFinder.areaLoaded(level, rect[0] - 4, rect[1] - 4, rect[2] + 4, rect[3] + 4)) {
            return null;
        }
        int[] range = Siting.groundRange(level, rect, 2);
        if (range == null || range[1] - range[0] > maxSpread) {
            return null;
        }
        int floorY = range[1] + 1;
        BlockPos origin = probe.atY(floorY);
        PlacementJob job = new PlacementJob(level, name).atomic();
        bp.appendTo(job, origin, rotation, Mirror.NONE);
        Siting.foundation(level, job, rect, floorY, NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState(), 6, skipColumns);
        return new Placed(job, origin, floorY, rect, rotation);
    }
}
