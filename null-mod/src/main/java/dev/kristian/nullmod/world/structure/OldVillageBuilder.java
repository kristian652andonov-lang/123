package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An old village that should not be here: a handful of small plank and cobblestone houses
 * around a dry well, joined by gravel paths. It comes in three states:
 * <ul>
 * <li>abandoned: roofs missing or half gone, cobwebs, doors gone, moss on the stone;</li>
 * <li>buried: every house sunk two to four blocks into the ground (natural ground only);</li>
 * <li>broken: random gaps through walls and roofs, pieces hanging displaced in the air.</li>
 * </ul>
 */
final class OldVillageBuilder {
    enum Variant { ABANDONED, BURIED, BROKEN }

    private static final int RADIUS = 20;

    private OldVillageBuilder() {
    }

    static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        boolean natural = NullConfig.mayReplaceNatural();
        Variant variant = Variant.values()[random.nextInt(Variant.values().length)];
        if (variant == Variant.BURIED && !natural) {
            variant = random.nextBoolean() ? Variant.ABANDONED : Variant.BROKEN;
        }
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            double min = req.minDistance(60.0, 34.0);
            BlockPos center = LocationFinder.findGround(level, req.player(), random, min, req.maxDistance(110.0, min),
                    req.view(LocationFinder.View.HIDDEN), 4, pos -> true);
            if (center == null) {
                continue;
            }
            PlacementJob job = plan(level, center, variant, random, req.forced(), natural);
            if (job != null && CorruptedStructureGenerator.submit(req, job, "village", describe(variant), center)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Variant variant) {
        return switch (variant) {
            case ABANDONED -> "an abandoned village";
            case BURIED -> "a buried village";
            case BROKEN -> "a broken village";
        };
    }

    @Nullable
    static PlacementJob plan(ServerLevel level, BlockPos center, Variant variant, RandomSource random, boolean forced, boolean natural) {
        int cx = center.getX();
        int cz = center.getZ();
        if (!LocationFinder.areaLoaded(level, cx - RADIUS - 2, cz - RADIUS - 2, cx + RADIUS + 2, cz + RADIUS + 2)) {
            return null;
        }
        int[] range = LocationFinder.groundRange(level, cx, cz, 14, 4);
        if (range == null || range[1] - range[0] > (forced ? 10 : 6)) {
            return null;
        }
        PlacementJob job = new PlacementJob(level, "abandoned_village").atomic();
        List<int[]> taken = new ArrayList<>();
        taken.add(new int[]{cx - 4, cz - 4, cx + 4, cz + 4});

        well(level, center, variant, random, natural).appendTo(job, center.offset(-2, 1, -2), Rotation.NONE, Mirror.NONE);

        int houses = 3 + random.nextInt(4);
        double start = random.nextDouble() * Math.PI * 2.0;
        int built = 0;
        List<BlockPos> doors = new ArrayList<>();
        for (int i = 0; i < houses; i++) {
            double angle = start + i * Math.PI * 2.0 / houses + (random.nextDouble() - 0.5) * 0.5;
            double dist = 9.0 + random.nextDouble() * 6.0;
            int hx = cx + Mth.floor(Math.cos(angle) * dist);
            int hz = cz + Mth.floor(Math.sin(angle) * dist);
            BlockPos door = house(level, job, hx, hz, cx, cz, variant, random, forced, taken);
            if (door != null) {
                doors.add(door);
                built++;
            }
        }
        if (built < 2) {
            return null;
        }
        if (natural) {
            for (BlockPos door : doors) {
                path(level, job, cx, cz, door, random);
            }
        }
        if (variant == Variant.ABANDONED) {
            debris(level, job, cx, cz, random);
        }
        return job;
    }

    /** One house facing the well. Returns the spot just outside its door, or null if it did not fit. */
    @Nullable
    private static BlockPos house(ServerLevel level, PlacementJob job, int hx, int hz, int cx, int cz, Variant variant,
                                  RandomSource random, boolean forced, List<int[]> taken) {
        RetroHouse.Spec spec = RetroHouse.random(random);
        switch (variant) {
            case ABANDONED -> {
                float roll = random.nextFloat();
                spec.roofStyle = roll < 0.4F ? RetroHouse.Roof.NONE : roll < 0.75F ? RetroHouse.Roof.PARTIAL : spec.roofStyle;
                spec.door = random.nextFloat() < 0.35F;
                spec.cobwebs = 0.75F;
                spec.moss = 0.4F;
            }
            case BURIED -> {
                // Below the ground line there is only earth: no door, nothing hanging inside.
                spec.door = false;
                spec.moss = 0.25F;
            }
            case BROKEN -> {
                spec.gaps = 0.2F + random.nextFloat() * 0.15F;
                spec.door = random.nextBoolean();
                spec.cobwebs = 0.2F;
                if (random.nextBoolean()) {
                    spec.roofStyle = RetroHouse.Roof.PARTIAL;
                }
            }
        }
        // Front (local -Z) faces the well.
        int dx = cx - hx;
        int dz = cz - hz;
        Direction toWell = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        Rotation rotation = frontRotation(toWell);
        BlockPos offset = Blueprint.toWorld(BlockPos.ZERO, spec.width / 2, 0, spec.depth / 2, rotation, Mirror.NONE);
        BlockPos[] corners = {
                Blueprint.toWorld(BlockPos.ZERO, 0, 0, 0, rotation, Mirror.NONE),
                Blueprint.toWorld(BlockPos.ZERO, spec.width - 1, 0, spec.depth - 1, rotation, Mirror.NONE)};
        int x0 = hx - offset.getX() + Math.min(corners[0].getX(), corners[1].getX());
        int x1 = hx - offset.getX() + Math.max(corners[0].getX(), corners[1].getX());
        int z0 = hz - offset.getZ() + Math.min(corners[0].getZ(), corners[1].getZ());
        int z1 = hz - offset.getZ() + Math.max(corners[0].getZ(), corners[1].getZ());
        for (int[] r : taken) {
            if (x0 - 2 <= r[2] && x1 + 2 >= r[0] && z0 - 2 <= r[3] && z1 + 2 >= r[1]) {
                return null;
            }
        }
        // Ground under the corners and the middle.
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int[][] samples = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {(x0 + x1) / 2, (z0 + z1) / 2}};
        for (int[] s : samples) {
            BlockPos g = LocationFinder.naturalGround(level, s[0], s[1]);
            if (g == null) {
                return null;
            }
            lo = Math.min(lo, g.getY());
            hi = Math.max(hi, g.getY());
        }
        if (hi - lo > (forced ? 5 : 3)) {
            return null;
        }
        taken.add(new int[]{x0, z0, x1, z1});

        Blueprint bp = new Blueprint();
        int floorY;
        if (variant == Variant.BURIED) {
            bp.mode(SafeBlockPlacer.Mode.NATURAL_OR_AIR);
            floorY = lo - (2 + random.nextInt(3));
        } else {
            floorY = hi + 1;
        }
        RetroHouse.build(bp, spec, random);
        if (variant == Variant.BROKEN) {
            displace(bp, random);
        }
        BlockPos origin = new BlockPos(hx - offset.getX(), floorY, hz - offset.getZ());
        bp.appendTo(job, origin, rotation, Mirror.NONE);

        if (variant != Variant.BURIED) {
            // Old foundations: cobblestone down to wherever the ground actually is.
            BlockState base = NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState();
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    int g = LocationFinder.groundY(level, x, z);
                    if (g == LocationFinder.NONE) {
                        continue;
                    }
                    for (int y = g + 1; y < floorY && y > floorY - 6; y++) {
                        job.place(new BlockPos(x, y, z), base);
                    }
                }
            }
        }
        return Blueprint.toWorld(origin, spec.width / 2, 0, -1, rotation, Mirror.NONE);
    }

    /** The rotation that turns the local front (-Z, north) toward {@code facing}. */
    static Rotation frontRotation(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** Broken houses: pieces of wall hang next to where they belong. */
    private static void displace(Blueprint bp, RandomSource random) {
        List<Blueprint.Cell> cells = bp.cells();
        if (cells.isEmpty()) {
            return;
        }
        int pieces = 4 + random.nextInt(6);
        for (int i = 0; i < pieces; i++) {
            Blueprint.Cell cell = cells.get(random.nextInt(cells.size()));
            if (cell.y() < 2 || cell.phase() != Blueprint.PHASE_SOLID) {
                continue;
            }
            int nx = cell.x() + random.nextInt(3) - 1;
            int ny = cell.y() + 1 + random.nextInt(3);
            int nz = cell.z() + random.nextInt(3) - 1;
            if (!bp.has(nx, ny, nz)) {
                bp.set(nx, ny, nz, cell.state(), SafeBlockPlacer.Mode.PLACE_INTO_AIR);
                bp.remove(cell.x(), cell.y(), cell.z());
            }
        }
    }

    /** A dry well: a cobblestone ring, four posts and a plank roof over a pit into the ground. */
    private static Blueprint well(ServerLevel level, BlockPos center, Variant variant, RandomSource random, boolean natural) {
        Blueprint bp = new Blueprint();
        BlockState ring = (variant == Variant.ABANDONED ? NullBlocks.RETRO_MOSSY_COBBLESTONE : NullBlocks.RETRO_COBBLESTONE).get().defaultBlockState();
        BlockState roof = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                boolean edge = x == 0 || x == 3 || z == 0 || z == 3;
                if (edge) {
                    bp.set(x, 0, z, ring);
                }
                if (variant != Variant.BROKEN || random.nextFloat() < 0.6F) {
                    bp.set(x, 4, z, roof);
                }
            }
        }
        int[][] posts = {{0, 0}, {3, 0}, {0, 3}, {3, 3}};
        for (int[] p : posts) {
            bp.set(p[0], 1, p[1], Blocks.OAK_FENCE.defaultBlockState());
            bp.set(p[0], 2, p[1], Blocks.OAK_FENCE.defaultBlockState());
            bp.set(p[0], 3, p[1], Blocks.OAK_FENCE.defaultBlockState());
        }
        if (natural) {
            // The pit: straight down through natural ground, as deep as it goes dry.
            int depth = 5 + random.nextInt(5);
            for (int x = 1; x <= 2; x++) {
                for (int z = 1; z <= 2; z++) {
                    // Clear grass over the opening, then the shaft itself.
                    bp.set(x, 0, z, Blocks.AIR.defaultBlockState(), SafeBlockPlacer.Mode.PLACE_INTO_AIR);
                    for (int y = 0; y >= -depth; y--) {
                        bp.carve(x, y - 1, z);
                    }
                }
            }
            if (variant == Variant.ABANDONED) {
                bp.loose(1, -2, 1, Blocks.COBWEB.defaultBlockState());
                bp.loose(2, -4, 2, Blocks.COBWEB.defaultBlockState());
            }
        }
        return bp;
    }

    /** A gravel path from the well to a door, over natural ground only. */
    private static void path(ServerLevel level, PlacementJob job, int cx, int cz, BlockPos door, RandomSource random) {
        BlockState gravel = NullBlocks.RETRO_GRAVEL.get().defaultBlockState();
        int x = cx;
        int z = cz;
        int tx = door.getX();
        int tz = door.getZ();
        int dx = Math.abs(tx - x);
        int dz = Math.abs(tz - z);
        int sx = x < tx ? 1 : -1;
        int sz = z < tz ? 1 : -1;
        int err = dx - dz;
        int steps = 0;
        while (steps++ < 64) {
            if (Math.abs(x - cx) > 2 || Math.abs(z - cz) > 2) {
                BlockPos ground = LocationFinder.naturalGround(level, x, z);
                if (ground != null && random.nextFloat() < 0.92F) {
                    job.replace(ground, gravel);
                }
            }
            if (x == tx && z == tz) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }

    /** Planks and cobblestone lying about where roofs and walls fell in. */
    private static void debris(ServerLevel level, PlacementJob job, int cx, int cz, RandomSource random) {
        BlockState[] pieces = {
                NullBlocks.RETRO_PLANKS.get().defaultBlockState(),
                NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState(),
                NullBlocks.RETRO_MOSSY_COBBLESTONE.get().defaultBlockState()};
        int count = 6 + random.nextInt(8);
        for (int i = 0; i < count; i++) {
            int x = cx + random.nextInt(RADIUS * 2 + 1) - RADIUS;
            int z = cz + random.nextInt(RADIUS * 2 + 1) - RADIUS;
            BlockPos ground = LocationFinder.naturalGround(level, x, z);
            if (ground != null) {
                job.add(ground.above(), pieces[random.nextInt(pieces.length)], SafeBlockPlacer.Mode.PLACE_INTO_AIR,
                        SafeBlockPlacer.Support.SOLID_BELOW);
            }
        }
    }
}
