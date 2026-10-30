package dev.kristian.nullmod.world;

import dev.kristian.nullmod.registry.NullDimensions;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Finds places for corruption around a player. Everything here reads only chunks that are
 * already loaded (heightmaps, never a chunk load), so asking is always cheap and never changes
 * what the server keeps in memory.
 */
public final class LocationFinder {
    /** Marker for "no such column / not loaded". */
    public static final int NONE = Integer.MIN_VALUE;

    /** Which part of the circle around the player a position may come from. */
    public enum View {
        /** Any direction. */
        ANY,
        /** Roughly where the player is looking. */
        VISIBLE,
        /** At the edge of the player's view, where something is half noticed. */
        EDGE,
        /** Outside the view cone. */
        HIDDEN,
        /** Behind the player. */
        BEHIND
    }

    /** A block column. */
    public record Column(int x, int z) {
    }

    private LocationFinder() {
    }

    // ------------------------------------------------------------------ loading

    /** Dimensions with an open sky and a real surface (not the Nether roof, not NULL). */
    public static boolean canHaveSurface(ServerLevel level) {
        return !NullDimensions.isNull(level) && !level.dimensionType().hasCeiling();
    }

    public static boolean isLoaded(ServerLevel level, int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    /** True if every chunk overlapping the block rectangle is loaded. */
    public static boolean areaLoaded(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = Math.min(minX, maxX) >> 4; cx <= Math.max(minX, maxX) >> 4; cx++) {
            for (int cz = Math.min(minZ, maxZ) >> 4; cz <= Math.max(minZ, maxZ) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The block at a position, or null if its chunk is not loaded or it is outside the world. */
    @Nullable
    public static BlockState stateIfLoaded(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !isLoaded(level, pos.getX(), pos.getZ())) {
            return null;
        }
        return level.getBlockState(pos);
    }

    // ------------------------------------------------------------------ columns

    /**
     * Y of the highest block that blocks movement, ignoring leaves (water counts, so a lake's
     * surface is its top), or {@link #NONE} if the chunk is not loaded or the column is empty.
     */
    public static int groundY(ServerLevel level, int x, int z) {
        return topOf(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Y of the highest non-air block (leaves and plants included), or {@link #NONE}. */
    public static int surfaceY(ServerLevel level, int x, int z) {
        return topOf(level, Heightmap.Types.WORLD_SURFACE, x, z);
    }

    private static int topOf(ServerLevel level, Heightmap.Types type, int x, int z) {
        if (!isLoaded(level, x, z)) {
            return NONE;
        }
        // Level#getHeight returns the first free Y above the column.
        int y = level.getHeight(type, x, z) - 1;
        return y < level.getMinBuildHeight() ? NONE : y;
    }

    /** Natural terrain that ground corruption may sit on or replace: not trees. */
    public static boolean isGroundBlock(BlockState state) {
        return BuildProtection.isNatural(state) && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)
                && state.getFluidState().isEmpty();
    }

    /**
     * The top block of a column if it is natural, dry ground with room above it (air, grass,
     * flowers or snow), else null.
     */
    @Nullable
    public static BlockPos naturalGround(ServerLevel level, int x, int z) {
        int y = groundY(level, x, z);
        if (y == NONE || y + 1 >= level.getMaxBuildHeight()) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!isGroundBlock(level.getBlockState(pos))) {
            return null;
        }
        BlockState above = level.getBlockState(pos.above());
        if (!above.getFluidState().isEmpty() || !BuildProtection.canPlaceInto(above)) {
            return null;
        }
        return pos;
    }

    // ------------------------------------------------------------------ the player's view

    /** Horizontal unit vector of where the player faces. */
    public static Vec3 forward(ServerPlayer player) {
        double rad = Math.toRadians(player.getYRot());
        return new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
    }

    /** True if the block is inside a cone of {@code halfAngleDegrees} around the player's look direction. */
    public static boolean inView(ServerPlayer player, BlockPos pos, double halfAngleDegrees) {
        Vec3 eye = player.getEyePosition();
        Vec3 to = new Vec3(pos.getX() + 0.5 - eye.x, pos.getY() + 0.5 - eye.y, pos.getZ() + 0.5 - eye.z);
        double length = to.length();
        if (length < 1.0E-3) {
            return true;
        }
        Vec3 look = player.getViewVector(1.0F);
        return look.dot(to) / length >= Math.cos(Math.toRadians(halfAngleDegrees));
    }

    /** Horizontal-only version: is the column in front of the player within the half angle. */
    public static boolean inViewHorizontal(ServerPlayer player, double x, double z, double halfAngleDegrees) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3) {
            return true;
        }
        Vec3 f = forward(player);
        return (f.x * dx + f.z * dz) / length >= Math.cos(Math.toRadians(halfAngleDegrees));
    }

    /** A random column between the two distances, in the part of the circle the view mode allows. */
    public static Column column(ServerPlayer player, RandomSource random, double minDist, double maxDist, View view) {
        Vec3 f = forward(player);
        double base = Math.atan2(f.z, f.x);
        double sign = random.nextBoolean() ? 1.0 : -1.0;
        double offset = switch (view) {
            case ANY -> random.nextDouble() * Math.PI * 2.0;
            case VISIBLE -> (random.nextDouble() * 2.0 - 1.0) * Math.toRadians(35.0);
            case EDGE -> sign * Math.toRadians(48.0 + random.nextDouble() * 32.0);
            case HIDDEN -> sign * Math.toRadians(80.0 + random.nextDouble() * 100.0);
            case BEHIND -> Math.PI + (random.nextDouble() * 2.0 - 1.0) * Math.toRadians(50.0);
        };
        double angle = base + offset;
        double lo = Math.min(minDist, maxDist);
        double hi = Math.max(minDist, maxDist);
        double dist = lo + random.nextDouble() * (hi - lo);
        return new Column(Mth.floor(player.getX() + Math.cos(angle) * dist), Mth.floor(player.getZ() + Math.sin(angle) * dist));
    }

    /**
     * Natural ground at a random spot around the player. Tries {@code attempts} columns and
     * returns the first one that is natural, dry, open above and passes {@code accept}.
     */
    @Nullable
    public static BlockPos findGround(ServerLevel level, ServerPlayer player, RandomSource random, double minDist, double maxDist,
                                      View view, int attempts, Predicate<BlockPos> accept) {
        if (!canHaveSurface(level)) {
            return null;
        }
        for (int i = 0; i < attempts; i++) {
            Column c = column(player, random, minDist, maxDist, view);
            BlockPos ground = naturalGround(level, c.x(), c.z());
            if (ground != null && accept.test(ground)) {
                return ground;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ areas

    /**
     * Counts blocks in the box that are not air (replaceable plants count as open when
     * {@code plantsOpen}). Returns -1 if part of the box is not loaded. Stops counting once
     * {@code limit} is exceeded.
     */
    public static int countBlocked(ServerLevel level, BlockPos min, BlockPos max, boolean plantsOpen, int limit) {
        int x0 = Math.min(min.getX(), max.getX());
        int x1 = Math.max(min.getX(), max.getX());
        int y0 = Math.max(level.getMinBuildHeight(), Math.min(min.getY(), max.getY()));
        int y1 = Math.min(level.getMaxBuildHeight() - 1, Math.max(min.getY(), max.getY()));
        int z0 = Math.min(min.getZ(), max.getZ());
        int z1 = Math.max(min.getZ(), max.getZ());
        if (!areaLoaded(level, x0, z0, x1, z1)) {
            return -1;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int blocked = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockState state = level.getBlockState(cursor.set(x, y, z));
                    if (state.isAir() || (plantsOpen && BuildProtection.canPlaceInto(state))) {
                        continue;
                    }
                    if (++blocked > limit) {
                        return blocked;
                    }
                }
            }
        }
        return blocked;
    }

    /** True if the whole box is loaded and open (air, or plants when allowed). */
    public static boolean isOpen(ServerLevel level, BlockPos min, BlockPos max, boolean plantsOpen) {
        return countBlocked(level, min, max, plantsOpen, 0) == 0;
    }

    /**
     * Samples natural ground heights over a square. Returns {min, max} of the ground Y, or null
     * if any sampled column is unloaded, wet, or not natural ground.
     */
    @Nullable
    public static int[] groundRange(ServerLevel level, int centerX, int centerZ, int radius, int step) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int s = Math.max(1, step);
        for (int dx = -radius; dx <= radius; dx += s) {
            for (int dz = -radius; dz <= radius; dz += s) {
                BlockPos ground = naturalGround(level, centerX + dx, centerZ + dz);
                if (ground == null) {
                    return null;
                }
                lo = Math.min(lo, ground.getY());
                hi = Math.max(hi, ground.getY());
            }
        }
        return new int[]{lo, hi};
    }

    /** True if the player is well below the surface of their column (in a cave or a mine). */
    public static boolean isUnderground(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        // Leaves are ignored so standing in a forest does not count as being underground.
        int top = groundY(level, player.getBlockX(), player.getBlockZ());
        return top != NONE && top > player.getBlockY() + 5;
    }

    /** Uniform integer in [min, max]. */
    public static int between(RandomSource random, int min, int max) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }
}
