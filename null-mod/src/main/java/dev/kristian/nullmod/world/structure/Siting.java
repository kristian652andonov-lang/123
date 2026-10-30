package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.world.BuildProtection;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Shared checks for putting a structure on (or into) the ground. */
final class Siting {
    private Siting() {
    }

    /** World rectangle {x0, z0, x1, z1} covered by local x 0..w-1, z 0..d-1 placed at origin. */
    static int[] rect(BlockPos origin, int w, int d, Rotation rotation) {
        BlockPos a = Blueprint.toWorld(origin, 0, 0, 0, rotation, Mirror.NONE);
        BlockPos b = Blueprint.toWorld(origin, w - 1, 0, d - 1, rotation, Mirror.NONE);
        return new int[]{Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ())};
    }

    /**
     * {min, max} natural ground Y over every {@code step}-th column of a rectangle, or null if
     * any sampled column is unloaded, wet, built on or not natural.
     */
    @Nullable
    static int[] groundRange(ServerLevel level, int[] rect, int step) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int s = Math.max(1, step);
        for (int x = rect[0]; x <= rect[2]; x += s) {
            for (int z = rect[1]; z <= rect[3]; z += s) {
                BlockPos g = LocationFinder.naturalGround(level, x, z);
                if (g == null) {
                    return null;
                }
                lo = Math.min(lo, g.getY());
                hi = Math.max(hi, g.getY());
            }
        }
        // Always include the far corner even when the step skips it.
        BlockPos corner = LocationFinder.naturalGround(level, rect[2], rect[3]);
        if (corner == null) {
            return null;
        }
        return new int[]{Math.min(lo, corner.getY()), Math.max(hi, corner.getY())};
    }

    /** Fills under a floor down to the real ground, at most {@code maxDepth} blocks, into air only. */
    static void foundation(ServerLevel level, PlacementJob job, int[] rect, int floorY, BlockState state, int maxDepth,
                           @Nullable Set<Long> skipColumns) {
        for (int x = rect[0]; x <= rect[2]; x++) {
            for (int z = rect[1]; z <= rect[3]; z++) {
                if (skipColumns != null && skipColumns.contains(BlockPos.asLong(x, 0, z))) {
                    continue;
                }
                int g = LocationFinder.groundY(level, x, z);
                if (g == LocationFinder.NONE) {
                    continue;
                }
                for (int y = Math.max(g + 1, floorY - maxDepth); y < floorY; y++) {
                    job.place(new BlockPos(x, y, z), state);
                }
            }
        }
    }

    /**
     * True if carving the given cells out of the ground is safe: everything loaded, at most
     * {@code maxAir} of them already open (crossing a cave) and {@code maxForeign} of them not
     * natural, no fluid in or next to any of them, and (unless the opening gets lined with solid
     * blocks) nothing that falls sitting right above one.
     */
    static boolean carveSafe(ServerLevel level, Iterable<BlockPos> cells, int maxAir, int maxForeign, boolean lined) {
        Set<Long> set = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            set.add(p.asLong());
            minX = Math.min(minX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (set.isEmpty() || !LocationFinder.areaLoaded(level, minX - 1, minZ - 1, maxX + 1, maxZ + 1)) {
            return false;
        }
        int air = 0;
        int foreign = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockPos p : cells) {
            if (level.isOutsideBuildHeight(p) || p.getY() <= level.getMinBuildHeight() + 4) {
                return false;
            }
            BlockState state = level.getBlockState(p);
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            if (state.isAir()) {
                if (++air > maxAir) {
                    return false;
                }
            } else if (!BuildProtection.isNatural(state) && ++foreign > maxForeign) {
                return false;
            }
            for (Direction side : Direction.values()) {
                cursor.setWithOffset(p, side);
                if (set.contains(cursor.asLong())) {
                    continue;
                }
                BlockState next = level.getBlockState(cursor);
                if (!next.getFluidState().isEmpty()) {
                    return false;
                }
                if (!lined && side == Direction.UP && next.getBlock() instanceof FallingBlock) {
                    return false;
                }
            }
        }
        return true;
    }
}
