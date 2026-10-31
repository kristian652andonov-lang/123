package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.CorruptionPalette;
import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import dev.kristian.nullmod.world.WorldNoise;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Things carved into the ground that should not be there, cut only out of natural stone and
 * earth and lined with old-looking blocks: corridors that turn and end in rooms leading nowhere
 * (stairs climbing into the ceiling, a door that opens onto stone, a ladder to nothing, a sealed
 * box in the middle of the floor, a passage that stops after five blocks), and one very long
 * corridor whose identical sections go on far longer than anything should.
 * <p>
 * A player in a cave finds the opening cut into a nearby cave wall; a player on the surface
 * finds a stairwell going down 40-ish blocks away.
 */
final class UndergroundBuilder {
    private UndergroundBuilder() {
    }

    /** Where the carving starts and which way it goes. */
    private record Entrance(BlockPos origin, Direction direction, boolean surface, int stairs) {
    }

    private record Deco(int x, int y, int z, BlockState state, SafeBlockPlacer.Support support, int group,
                        @Nullable PlacementJob.PostPlace post) {
    }

    /** A plan in local space: everything is built along local +Z from the origin. */
    private static final class Plan {
        final Set<Long> open = new LinkedHashSet<>();
        /** Already-open cells in front of the entrance: never carved, never lined, never walled off. */
        final Set<Long> approach = new LinkedHashSet<>();
        final Map<Long, BlockState> special = new HashMap<>();
        final Set<Long> unlined = new LinkedHashSet<>();
        final List<Deco> decos = new ArrayList<>();
        BlockState wall;
        BlockState floor;
        BlockState ceiling;
        BlockState accent;
        long seed;
        int groups;
        int x;
        int y;
        int z;
        int dx;
        int dz = 1;

        void open(int px, int py, int pz) {
            open.add(BlockPos.asLong(px, py, pz));
        }

        boolean isOpen(int px, int py, int pz) {
            return open.contains(BlockPos.asLong(px, py, pz));
        }

        /** A 3 wide, 3 tall cross-section at the turtle, then one step forward. */
        void step() {
            for (int lateral = -1; lateral <= 1; lateral++) {
                for (int h = 0; h < 3; h++) {
                    open(x + (dz != 0 ? lateral : 0), y + h, z + (dx != 0 ? lateral : 0));
                }
            }
            x += dx;
            z += dz;
        }

        void forward(int n) {
            for (int i = 0; i < n; i++) {
                step();
            }
        }

        /** Turns 90 degrees; from +Z to the side, or from the side back to +Z. */
        void turn(boolean left) {
            if (dz != 0) {
                dx = left ? -1 : 1;
                dz = 0;
            } else {
                dx = 0;
                dz = 1;
            }
        }

        /** Stairs down: every step forward also goes one block lower. */
        void descend(int n) {
            for (int i = 0; i < n; i++) {
                for (int lateral = -1; lateral <= 1; lateral++) {
                    for (int h = 0; h < 4; h++) {
                        open(x + (dz != 0 ? lateral : 0), y + h, z + (dx != 0 ? lateral : 0));
                    }
                }
                x += dx;
                z += dz;
                y--;
            }
        }

        void deco(int px, int py, int pz, BlockState state, SafeBlockPlacer.Support support) {
            decos.add(new Deco(px, py, pz, state, support, 0, null));
        }

        /** A door set into the wall itself, with natural stone right behind it. */
        void doorInWall(int px, int py, int pz, Direction facing) {
            int group = ++groups;
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
                    .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            decos.add(new Deco(px, py, pz, lower, SafeBlockPlacer.Support.SOLID_BELOW, group, null));
            decos.add(new Deco(px, py + 1, pz, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), SafeBlockPlacer.Support.NONE, group, null));
            unlined.add(BlockPos.asLong(px, py, pz));
            unlined.add(BlockPos.asLong(px, py + 1, pz));
        }

        BlockState wallAt(int px, int py, int pz) {
            double n = WorldNoise.hash01(seed, px, py, pz);
            return n < 0.12 && accent != null ? accent : wall;
        }
    }

    // ------------------------------------------------------------------ entry points

    static boolean impossibleRoom(GenerationRequest req) {
        if (!NullConfig.mayReplaceNatural() || !LocationFinder.canHaveSurface(req.level())) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            Entrance entrance = entrance(req, random);
            if (entrance == null) {
                continue;
            }
            Plan plan = roomPlan(entrance, random);
            PlacementJob job = realize(req.level(), plan, entrance, "impossible_room", 0.15);
            if (job != null && CorruptedStructureGenerator.submit(req, job, "impossible_room", "a room under the ground", entrance.origin())) {
                return true;
            }
        }
        return false;
    }

    static boolean endlessCorridor(GenerationRequest req) {
        if (!NullConfig.mayReplaceNatural() || !LocationFinder.canHaveSurface(req.level())) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            Entrance entrance = entrance(req, random);
            if (entrance == null) {
                continue;
            }
            int room = loadedLength(req.level(), entrance, 110);
            int length = Math.min(60 + random.nextInt(41), room - entrance.stairs() - 6);
            if (length < (req.forced() ? 24 : 40)) {
                continue;
            }
            Plan plan = corridorPlan(entrance, length, random);
            PlacementJob job = realize(req.level(), plan, entrance, "endless_corridor", 0.12);
            if (job != null && CorruptedStructureGenerator.submit(req, job, "endless_corridor", "a corridor that does not end", entrance.origin())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ entrances

    @Nullable
    private static Entrance entrance(GenerationRequest req, RandomSource random) {
        ServerPlayer player = req.player();
        if (LocationFinder.isUnderground(player)) {
            Entrance wall = caveWall(req.level(), player, random, req.showcase());
            if (wall != null) {
                return wall;
            }
        }
        double min = req.minDistance(18.0, 10.0);
        BlockPos ground = LocationFinder.findGround(req.level(), player, random, min, req.maxDistance(48.0, min),
                req.view(LocationFinder.View.HIDDEN), 6, pos -> true);
        if (ground == null) {
            return null;
        }
        // In the showcase the stairs lead away from the player, so the opening faces them.
        Direction direction = req.showcase() ? player.getDirection() : Direction.Plane.HORIZONTAL.getRandomDirection(random);
        return new Entrance(ground, direction, true, 9 + random.nextInt(5));
    }

    /**
     * A natural wall of the cave the player is in, two to sixteen blocks away, preferably behind
     * them (in front of them for the showcase).
     */
    @Nullable
    private static Entrance caveWall(ServerLevel level, ServerPlayer player, RandomSource random, boolean inFront) {
        BlockPos feet = player.blockPosition();
        List<Direction> directions = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            directions.add(d);
        }
        // Behind the player first, then the sides, then in front (the reverse for the showcase).
        directions.sort((a, b) -> inFront ? Double.compare(facingDot(player, b), facingDot(player, a))
                : Double.compare(facingDot(player, a), facingDot(player, b)));
        for (Direction dir : directions) {
            for (int i = 2; i <= 16; i++) {
                BlockPos p = feet.relative(dir, i);
                BlockState state = LocationFinder.stateIfLoaded(level, p);
                BlockState head = LocationFinder.stateIfLoaded(level, p.above());
                if (state == null || head == null) {
                    break;
                }
                if (state.isAir() && head.isAir()) {
                    continue;
                }
                if (LocationFinder.isGroundBlock(state) && LocationFinder.isGroundBlock(head)) {
                    return new Entrance(p, dir, false, 0);
                }
                break;
            }
        }
        return null;
    }

    private static double facingDot(ServerPlayer player, Direction d) {
        var f = LocationFinder.forward(player);
        return f.x * d.getStepX() + f.z * d.getStepZ();
    }

    /** How many blocks from the entrance along its direction stay inside loaded chunks. */
    private static int loadedLength(ServerLevel level, Entrance entrance, int max) {
        BlockPos o = entrance.origin();
        Direction d = entrance.direction();
        for (int i = 0; i <= max; i += 4) {
            BlockPos p = o.relative(d, i);
            if (!LocationFinder.areaLoaded(level, p.getX() - 6, p.getZ() - 6, p.getX() + 6, p.getZ() + 6)) {
                return Math.max(0, i - 4);
            }
        }
        return max;
    }

    // ------------------------------------------------------------------ plans

    private static Plan basePlan(Entrance entrance, RandomSource random) {
        Plan plan = new Plan();
        plan.seed = random.nextLong();
        int bottom;
        if (entrance.surface()) {
            // Local y=0 is the ground block: walk down from on top of it.
            plan.y = 1;
            plan.descend(entrance.stairs() + 1);
            bottom = 1;
        } else {
            // Local y=0 is the player's foot level at the foot of the cave wall.
            bottom = 0;
        }
        // Keep the way in open so nothing ever walls off the entrance.
        for (int lateral = -1; lateral <= 1; lateral++) {
            for (int h = 0; h < 3; h++) {
                plan.approach.add(BlockPos.asLong(lateral, bottom + h, -1));
            }
        }
        return plan;
    }

    private static Plan roomPlan(Entrance entrance, RandomSource random) {
        Plan plan = basePlan(entrance, random);
        plan.wall = NullBlocks.RETRO_STONE.get().defaultBlockState();
        plan.accent = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        plan.floor = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        plan.ceiling = NullBlocks.RETRO_STONE.get().defaultBlockState();

        int corridorStart = plan.z;
        plan.forward(5 + random.nextInt(6));
        boolean left = random.nextBoolean();
        plan.turn(left);
        plan.forward(3 + random.nextInt(6));
        plan.turn(left);
        plan.forward(3 + random.nextInt(4));
        torches(plan, corridorStart);

        // The room, straight ahead.
        int rw = 5 + random.nextInt(3) * 2;
        int rd = 5 + random.nextInt(3) * 2;
        int rh = 4 + random.nextInt(2);
        int cx = plan.x;
        int fy = plan.y;
        int z0 = plan.z;
        int x0 = cx - rw / 2;
        int x1 = cx + rw / 2;
        int z1 = z0 + rd - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int h = 0; h < rh; h++) {
                    plan.open(x, fy + h, z);
                }
            }
        }
        boolean planks = random.nextBoolean();
        BlockState roomWall = (planks ? NullBlocks.RETRO_PLANKS : NullBlocks.RETRO_BRICKS).get().defaultBlockState();
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int h = -1; h <= rh; h++) {
                    if (!plan.isOpen(x, fy + h, z)) {
                        plan.special.put(BlockPos.asLong(x, fy + h, z), roomWall);
                    }
                }
            }
        }

        List<Integer> features = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6));
        int count = 2 + random.nextInt(2);
        boolean centreTaken = false;
        for (int i = 0; i < count && !features.isEmpty(); i++) {
            int feature = features.remove(random.nextInt(features.size()));
            switch (feature) {
                case 0 -> {
                    // Stairs climbing the right-hand wall straight into the ceiling.
                    BlockState cobble = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
                    for (int k = 0; k < Math.min(rh, rd - 1); k++) {
                        for (int h = 0; h < k; h++) {
                            plan.deco(x1, fy + h, z0 + 1 + k, cobble, SafeBlockPlacer.Support.NONE);
                        }
                        plan.deco(x1, fy + k, z0 + 1 + k, Blueprint.stairs(Blocks.COBBLESTONE_STAIRS, Direction.SOUTH, false),
                                SafeBlockPlacer.Support.NONE);
                    }
                }
                case 1 -> plan.doorInWall(cx, fy, z1 + 1, Direction.SOUTH);
                case 2 -> {
                    BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST);
                    for (int h = 0; h < rh; h++) {
                        plan.deco(x0, fy + h, z0 + rd / 2, ladder, SafeBlockPlacer.Support.SURVIVE);
                    }
                }
                case 3 -> {
                    if (!centreTaken) {
                        centreTaken = true;
                        sign(plan, cx, fy, z0 + rd / 2, random);
                    }
                }
                case 4 -> {
                    if (!centreTaken && rw >= 7 && rd >= 7) {
                        centreTaken = true;
                        sealedBox(plan, cx, fy, z0 + rd / 2);
                    }
                }
                case 5 -> {
                    BlockState torch = Blocks.TORCH.defaultBlockState();
                    plan.deco(x0, fy, z0, torch, SafeBlockPlacer.Support.SURVIVE);
                    plan.deco(x1, fy, z0, torch, SafeBlockPlacer.Support.SURVIVE);
                    plan.deco(x0, fy, z1, torch, SafeBlockPlacer.Support.SURVIVE);
                    plan.deco(x1, fy, z1, torch, SafeBlockPlacer.Support.SURVIVE);
                }
                default -> {
                    // A narrow passage out of the left wall, near the front, that just stops.
                    int len = 4 + random.nextInt(3);
                    for (int k = 1; k <= len; k++) {
                        plan.open(x0 - k, fy, z0 + 1);
                        plan.open(x0 - k, fy + 1, z0 + 1);
                    }
                }
            }
        }
        return plan;
    }

    private static Plan corridorPlan(Entrance entrance, int length, RandomSource random) {
        Plan plan = basePlan(entrance, random);
        plan.wall = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
        plan.accent = NullBlocks.RETRO_MOSSY_COBBLESTONE.get().defaultBlockState();
        plan.floor = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        plan.ceiling = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        int start = plan.z;
        int fy = plan.y;
        plan.forward(length);
        int end = plan.z;
        BlockState log = CorruptionPalette.retroLog(Direction.Axis.Y);
        BlockState beam = CorruptionPalette.retroLog(Direction.Axis.X);
        int lit = start + (int) ((end - start) * 0.7);
        for (int z = start + 4; z < end - 1; z += 8) {
            // The same support frame every eight blocks, for as long as it goes on.
            for (int h = 0; h < 3; h++) {
                plan.special.put(BlockPos.asLong(-2, fy + h, z), log);
                plan.special.put(BlockPos.asLong(2, fy + h, z), log);
            }
            for (int x = -2; x <= 2; x++) {
                plan.special.put(BlockPos.asLong(x, fy + 3, z), beam);
            }
            if (z < lit) {
                plan.deco(-1, fy + 1, z + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST),
                        SafeBlockPlacer.Support.SURVIVE);
                plan.deco(1, fy + 1, z + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.WEST),
                        SafeBlockPlacer.Support.SURVIVE);
            }
            if ((z - start) % 16 == 12) {
                // Identical doors in identical alcoves, all opening onto stone.
                int side = ((z - start) / 16) % 2 == 0 ? -1 : 1;
                plan.open(side * 2, fy, z + 2);
                plan.open(side * 2, fy + 1, z + 2);
                plan.doorInWall(side * 3, fy, z + 2, side < 0 ? Direction.WEST : Direction.EAST);
            }
        }
        // One last torch right at the end, where nobody should have been.
        plan.deco(0, fy, end - 1, Blocks.TORCH.defaultBlockState(), SafeBlockPlacer.Support.SURVIVE);
        // The end is raw stone, as if the corridor simply had not loaded any further.
        for (int x = -1; x <= 1; x++) {
            for (int h = 0; h < 3; h++) {
                plan.unlined.add(BlockPos.asLong(x, fy + h, end));
            }
        }
        return plan;
    }

    /** Wall torches every six blocks along the straight +Z parts of a corridor. */
    private static void torches(Plan plan, int startZ) {
        for (Long key : new ArrayList<>(plan.open)) {
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            if (z <= startZ || Math.floorMod(z - startZ, 6) != 3 || !plan.isOpen(x, y - 1, z) || plan.isOpen(x, y - 2, z)) {
                continue;
            }
            // y is one above the floor here; a torch where a wall is directly to the side.
            if (!plan.isOpen(x - 1, y, z) && plan.isOpen(x + 1, y, z)) {
                plan.deco(x, y, z, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST),
                        SafeBlockPlacer.Support.SURVIVE);
            } else if (!plan.isOpen(x + 1, y, z) && plan.isOpen(x - 1, y, z)) {
                plan.deco(x, y, z, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.WEST),
                        SafeBlockPlacer.Support.SURVIVE);
            }
        }
    }

    private static void sign(Plan plan, int x, int y, int z, RandomSource random) {
        List<Component> lines = random.nextBoolean()
                ? List.of(Component.empty(), Component.translatable("null.world.sign.not_generated.1"),
                Component.translatable("null.world.sign.not_generated.2"), Component.empty())
                : List.of(Component.empty(), Component.translatable("null.world.sign.how.1"),
                Component.translatable("null.world.sign.how.2"), Component.empty());
        BlockState state = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8);
        plan.decos.add(new Deco(x, y, z, state, SafeBlockPlacer.Support.SURVIVE, 0,
                Blueprint.signWriter(Blueprint.text(lines, DyeColor.BLACK, false), null)));
    }

    /** A closed box of bricks in the middle of the floor, with a torch burning inside it. */
    private static void sealedBox(Plan plan, int cx, int fy, int cz) {
        BlockState bricks = NullBlocks.RETRO_BRICKS.get().defaultBlockState();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int h = 0; h < 3; h++) {
                    if (x == 0 && z == 0 && h == 1) {
                        continue;
                    }
                    plan.deco(cx + x, fy + h, cz + z, bricks, SafeBlockPlacer.Support.NONE);
                }
            }
        }
        plan.deco(cx, fy + 1, cz, Blocks.TORCH.defaultBlockState(), SafeBlockPlacer.Support.SURVIVE);
    }

    // ------------------------------------------------------------------ into the world

    /**
     * Turns a plan into a job at the entrance: open cells below the ground are carved, their
     * neighbours lined (natural ground or cave air only), decorations placed last. Returns null if
     * the rock is not suitable (fluids, too much open cave, built things, unloaded chunks).
     */
    @Nullable
    private static PlacementJob realize(ServerLevel level, Plan plan, Entrance entrance, String name, double maxAirShare) {
        Rotation rotation = Blueprint.rotationFor(entrance.direction());
        BlockPos origin = entrance.origin();
        Map<Long, BlockPos> world = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Long key : plan.open) {
            BlockPos p = Blueprint.toWorld(origin, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), rotation, Mirror.NONE);
            world.put(p.asLong(), p);
            minX = Math.min(minX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (world.isEmpty() || !LocationFinder.areaLoaded(level, minX - 2, minZ - 2, maxX + 2, maxZ + 2)) {
            return null;
        }
        Set<Long> keepOpen = new LinkedHashSet<>(world.keySet());
        for (Long key : plan.approach) {
            keepOpen.add(Blueprint.toWorld(origin, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), rotation, Mirror.NONE).asLong());
        }
        Map<Long, Integer> groundCache = new HashMap<>();
        List<BlockPos> carve = new ArrayList<>();
        int shallow = 0;
        for (BlockPos p : world.values()) {
            int g = ground(level, groundCache, p.getX(), p.getZ());
            if (entrance.surface() && p.getY() > g) {
                continue;
            }
            if (g - p.getY() < 2) {
                shallow++;
            }
            carve.add(p);
        }
        int allowedShallow = entrance.surface() ? 3 * (entrance.stairs() + 4) : 8;
        int maxAir = (int) Math.max(4, carve.size() * maxAirShare);
        if (carve.size() < 12 || shallow > allowedShallow || !Siting.carveSafe(level, carve, maxAir, carve.size() / 20 + 2, true)) {
            return null;
        }

        Set<Long> unlined = new LinkedHashSet<>();
        for (Long key : plan.unlined) {
            unlined.add(Blueprint.toWorld(origin, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), rotation, Mirror.NONE).asLong());
        }
        Map<Long, BlockState> special = new HashMap<>();
        for (Map.Entry<Long, BlockState> e : plan.special.entrySet()) {
            long key = e.getKey();
            special.put(Blueprint.toWorld(origin, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), rotation, Mirror.NONE).asLong(),
                    e.getValue().rotate(rotation));
        }

        PlacementJob job = new PlacementJob(level, name).atomic();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos p : carve) {
            job.add(p, air, SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.NONE);
        }
        Set<Long> lined = new LinkedHashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockPos p : carve) {
            for (Direction side : Direction.values()) {
                cursor.setWithOffset(p, side);
                long key = cursor.asLong();
                if (keepOpen.contains(key) || unlined.contains(key) || !lined.add(key)) {
                    continue;
                }
                if (entrance.surface() && cursor.getY() > ground(level, groundCache, cursor.getX(), cursor.getZ())) {
                    continue;
                }
                BlockState material = special.get(key);
                if (material == null) {
                    material = side == Direction.DOWN ? plan.floor : side == Direction.UP ? plan.ceiling
                            : plan.wallAt(cursor.getX(), cursor.getY(), cursor.getZ());
                }
                job.add(cursor.immutable(), material, SafeBlockPlacer.Mode.NATURAL_OR_AIR, SafeBlockPlacer.Support.NONE);
            }
        }
        if (entrance.surface()) {
            entranceFrame(job, origin, rotation);
        }
        Map<Integer, Integer> groups = new HashMap<>();
        for (Deco deco : plan.decos) {
            BlockPos p = Blueprint.toWorld(origin, deco.x(), deco.y(), deco.z(), rotation, Mirror.NONE);
            int group = deco.group() == 0 ? 0 : groups.computeIfAbsent(deco.group(), g -> job.newGroup());
            SafeBlockPlacer.Mode mode = deco.group() != 0 ? SafeBlockPlacer.Mode.NATURAL_OR_AIR : SafeBlockPlacer.Mode.PLACE_INTO_AIR;
            job.add(p, deco.state().rotate(rotation), mode, deco.support(), group, deco.post());
        }
        return job;
    }

    /** Old planks around the top of the stairwell, like the entrance of a forgotten mine. */
    private static void entranceFrame(PlacementJob job, BlockPos origin, Rotation rotation) {
        BlockState post = CorruptionPalette.retroLog(Direction.Axis.Y);
        BlockState planks = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        for (int h = 1; h <= 3; h++) {
            job.place(Blueprint.toWorld(origin, -2, h, 0, rotation, Mirror.NONE), post);
            job.place(Blueprint.toWorld(origin, 2, h, 0, rotation, Mirror.NONE), post);
        }
        for (int x = -2; x <= 2; x++) {
            job.place(Blueprint.toWorld(origin, x, 4, 0, rotation, Mirror.NONE), planks);
        }
    }

    private static int ground(ServerLevel level, Map<Long, Integer> cache, int x, int z) {
        return cache.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> {
            int g = LocationFinder.groundY(level, x, z);
            return g == LocationFinder.NONE ? Integer.MAX_VALUE : g;
        });
    }
}
