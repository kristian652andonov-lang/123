package dev.kristian.nullmod.entity;

import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.BuildProtection;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finds places where something player-shaped can stand: at the edge of a player's view,
 * behind them, in caves, under trees, inside abandoned structures, on tree tops, on water or
 * on bedrock floating in the sky. Every finder samples a bounded number of columns around
 * one player, only ever reads blocks in chunks that are already loaded and entity-ticking,
 * and never loads or changes anything.
 */
public final class PlayerLikeSpawns {
    /** How far from the player's eye height the vertical scans look. */
    private static final int SCAN_UP = 10;
    private static final int SCAN_DOWN = 14;

    /**
     * @param pos      feet position (block centre)
     * @param floating true when it stands on something that cannot carry it (water): spawn it without gravity
     */
    public record Spot(Vec3 pos, boolean floating) {
        public BlockPos blockPos() {
            return BlockPos.containing(pos);
        }
    }

    private PlayerLikeSpawns() {
    }

    // ------------------------------------------------------------------ finders

    /** Open ground {@code min..max} blocks away near the edge of the player's view, preferably visible. */
    public static Optional<Spot> edgeOfView(ServerPlayer player, RandomSource random, double min, double max, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        float yaw = player.getYHeadRot();
        Spot fallback = null;
        int attempts = relaxed ? 26 : 14;
        for (int i = 0; i < attempts; i++) {
            float side = random.nextBoolean() ? 1.0F : -1.0F;
            float angle = yaw + side * (26.0F + random.nextFloat() * 30.0F);
            double distance = Mth.lerp(random.nextDouble(), min, max);
            Optional<Spot> spot = groundAt(level, player, angle, distance, 24);
            if (spot.isEmpty() || nearAnyPlayer(level, spot.get().pos(), Math.min(10.0, min * 0.6))) {
                continue;
            }
            if (visibleFrom(player, spot.get().pos())) {
                return spot;
            }
            if (fallback == null) {
                fallback = spot.get();
            }
        }
        return relaxed ? Optional.ofNullable(fallback) : Optional.empty();
    }

    /** Ground straight ahead (within about 22 degrees) that the player can see, for things that run at them. */
    public static Optional<Spot> inFront(ServerPlayer player, RandomSource random, double min, double max, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        float yaw = player.getYHeadRot();
        Spot fallback = null;
        for (int i = 0; i < (relaxed ? 22 : 12); i++) {
            float angle = yaw + (random.nextFloat() - 0.5F) * 44.0F;
            double distance = Mth.lerp(random.nextDouble(), min, max);
            Optional<Spot> spot = groundAt(level, player, angle, distance, 6);
            if (spot.isEmpty()) {
                continue;
            }
            if (visibleFrom(player, spot.get().pos()) && clearPath(level, player, spot.get().pos())) {
                return spot;
            }
            if (fallback == null) {
                fallback = spot.get();
            }
        }
        return relaxed ? Optional.ofNullable(fallback) : Optional.empty();
    }

    /** Ground off to one side (50-85 degrees from the view), for things that run across the view. */
    public static Optional<Spot> toTheSide(ServerPlayer player, RandomSource random, double min, double max, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        float yaw = player.getYHeadRot();
        for (int i = 0; i < (relaxed ? 20 : 12); i++) {
            float side = random.nextBoolean() ? 1.0F : -1.0F;
            float angle = yaw + side * (50.0F + random.nextFloat() * 35.0F);
            Optional<Spot> spot = groundAt(level, player, angle, Mth.lerp(random.nextDouble(), min, max), 6);
            if (spot.isPresent() && (relaxed || visibleFrom(player, spot.get().pos()))) {
                return spot;
            }
        }
        return Optional.empty();
    }

    /** Somewhere behind the player that is not on their screen. */
    public static Optional<Spot> behind(ServerPlayer player, RandomSource random, double min, double max, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        float yaw = player.getYHeadRot() + 180.0F;
        for (int i = 0; i < (relaxed ? 24 : 14); i++) {
            float angle = yaw + (random.nextFloat() - 0.5F) * (relaxed ? 110.0F : 70.0F);
            Optional<Spot> spot = groundAt(level, player, angle, Mth.lerp(random.nextDouble(), min, max), 6);
            if (spot.isEmpty()) {
                continue;
            }
            if (!inViewCone(player, spot.get().pos(), 0.2) && !nearAnyPlayer(level, spot.get().pos(), Math.min(min, 3.0))) {
                return spot;
            }
        }
        return Optional.empty();
    }

    /** Anywhere around the player in a ring, any direction. */
    public static Optional<Spot> around(ServerPlayer player, RandomSource random, double min, double max, int attempts) {
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < attempts; i++) {
            float angle = random.nextFloat() * 360.0F;
            Optional<Spot> spot = groundAt(level, player, angle, Mth.lerp(random.nextDouble(), min, max), 10);
            if (spot.isPresent() && !nearAnyPlayer(level, spot.get().pos(), Math.min(min, 6.0))) {
                return spot;
            }
        }
        return Optional.empty();
    }

    /** True if the player is under the ground: no sky above them and well below the surface. */
    public static boolean isUnderground(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        if (!level.isPositionEntityTicking(feet) || level.canSeeSky(feet.above())) {
            return false;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        return feet.getY() < surface - 6;
    }

    /** A dark, covered floor in the caves around the player. */
    public static Optional<Spot> cave(ServerPlayer player, RandomSource random, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        int baseY = player.getBlockY();
        Spot fallback = null;
        for (int i = 0; i < (relaxed ? 40 : 26); i++) {
            float angle = random.nextFloat() * 360.0F;
            double distance = Mth.lerp(random.nextDouble(), 9.0, 28.0);
            int x = Mth.floor(player.getX() - Mth.sin(angle * Mth.DEG_TO_RAD) * distance);
            int z = Mth.floor(player.getZ() + Mth.cos(angle * Mth.DEG_TO_RAD) * distance);
            OptionalInt y = standableNear(level, x, z, baseY + random.nextInt(9) - 4, 6, 8);
            if (y.isEmpty()) {
                continue;
            }
            BlockPos feet = new BlockPos(x, y.getAsInt(), z);
            if (level.canSeeSky(feet)) {
                continue;
            }
            Spot spot = new Spot(Vec3.atBottomCenterOf(feet), false);
            if (level.getBrightness(LightLayer.BLOCK, feet) <= 6) {
                return Optional.of(spot);
            }
            if (fallback == null) {
                fallback = spot;
            }
        }
        return relaxed ? Optional.ofNullable(fallback) : Optional.empty();
    }

    /** Ground under a tree canopy, in a place where there are trees all around. */
    public static Optional<Spot> forest(ServerPlayer player, RandomSource random, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < (relaxed ? 34 : 22); i++) {
            float angle = random.nextFloat() * 360.0F;
            double distance = Mth.lerp(random.nextDouble(), 14.0, 52.0);
            int x = Mth.floor(player.getX() - Mth.sin(angle * Mth.DEG_TO_RAD) * distance);
            int z = Mth.floor(player.getZ() + Mth.cos(angle * Mth.DEG_TO_RAD) * distance);
            if (canopy(level, x, z) < 3) {
                continue;
            }
            int trees = 0;
            for (int[] d : new int[][] {{5, 0}, {-5, 0}, {0, 5}, {0, -5}}) {
                if (canopy(level, x + d[0], z + d[1]) >= 2) {
                    trees++;
                }
            }
            if (trees < (relaxed ? 1 : 2)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (standable(level, feet) && !nearAnyPlayer(level, Vec3.atBottomCenterOf(feet), 10.0)) {
                return Optional.of(new Spot(Vec3.atBottomCenterOf(feet), false));
            }
        }
        return Optional.empty();
    }

    /**
     * Inside something abandoned: near old planks and cobblestone, cobwebs, mossy or cracked
     * stone, never in a chunk where players have built.
     */
    public static Optional<Spot> abandonedStructure(ServerPlayer player, RandomSource random, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Surface structures: look down from the top of sampled columns.
        for (int i = 0; i < (relaxed ? 90 : 60); i++) {
            float angle = random.nextFloat() * 360.0F;
            double distance = Mth.lerp(random.nextDouble(), 6.0, 56.0);
            int x = Mth.floor(player.getX() - Mth.sin(angle * Mth.DEG_TO_RAD) * distance);
            int z = Mth.floor(player.getZ() + Mth.cos(angle * Mth.DEG_TO_RAD) * distance);
            cursor.set(x, player.getBlockY(), z);
            if (!level.isPositionEntityTicking(cursor)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            for (int y = top; y >= top - 8; y--) {
                cursor.setY(y);
                if (isAbandonedMarker(level.getBlockState(cursor))) {
                    Optional<Spot> spot = spotNearMarker(level, cursor.immutable(), random);
                    if (spot.isPresent()) {
                        return spot;
                    }
                    break;
                }
            }
        }
        // Underground structures (mineshafts, buried rooms): random points around the player.
        if (isUnderground(player) || relaxed) {
            for (int i = 0; i < 180; i++) {
                int x = player.getBlockX() + random.nextInt(65) - 32;
                int y = player.getBlockY() + random.nextInt(21) - 10;
                int z = player.getBlockZ() + random.nextInt(65) - 32;
                cursor.set(x, y, z);
                if (!level.isPositionEntityTicking(cursor) || level.isOutsideBuildHeight(cursor)) {
                    continue;
                }
                if (isAbandonedMarker(level.getBlockState(cursor))) {
                    Optional<Spot> spot = spotNearMarker(level, cursor.immutable(), random);
                    if (spot.isPresent()) {
                        return spot;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Somewhere nobody stands: a tree top, the surface of water or bedrock floating in the sky. */
    public static Optional<Spot> unusual(ServerPlayer player, RandomSource random, boolean relaxed) {
        int first = random.nextInt(3);
        for (int k = 0; k < 3; k++) {
            Optional<Spot> spot = switch ((first + k) % 3) {
                case 0 -> treeTop(player, random, relaxed);
                case 1 -> onWater(player, random, relaxed);
                default -> skyBedrock(player, random, relaxed);
            };
            if (spot.isPresent()) {
                return spot;
            }
        }
        return Optional.empty();
    }

    public static Optional<Spot> treeTop(ServerPlayer player, RandomSource random, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < (relaxed ? 40 : 24); i++) {
            int[] xz = ring(player, random, 12.0, 56.0);
            cursor.set(xz[0], player.getBlockY(), xz[1]);
            if (!level.isPositionEntityTicking(cursor)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, xz[0], xz[1]);
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, xz[0], xz[1]);
            if (top - ground < 3) {
                continue;
            }
            cursor.setY(top - 1);
            if (!level.getBlockState(cursor).is(BlockTags.LEAVES)) {
                continue;
            }
            BlockPos feet = new BlockPos(xz[0], top, xz[1]);
            if (standable(level, feet)) {
                return Optional.of(new Spot(Vec3.atBottomCenterOf(feet), false));
            }
        }
        return Optional.empty();
    }

    public static Optional<Spot> onWater(ServerPlayer player, RandomSource random, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < (relaxed ? 40 : 24); i++) {
            int[] xz = ring(player, random, 14.0, 56.0);
            cursor.set(xz[0], player.getBlockY(), xz[1]);
            if (!level.isPositionEntityTicking(cursor)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, xz[0], xz[1]);
            cursor.setY(top - 1);
            BlockState surface = level.getBlockState(cursor);
            if (!surface.getFluidState().is(FluidTags.WATER) || !surface.getFluidState().isSource()) {
                continue;
            }
            BlockPos feet = new BlockPos(xz[0], top, xz[1]);
            if (isFree(level, feet) && isFree(level, feet.above())) {
                // Feet on the very top of the water block: it stands on the surface.
                return Optional.of(new Spot(new Vec3(xz[0] + 0.5, top, xz[1] + 0.5), true));
            }
        }
        return Optional.empty();
    }

    public static Optional<Spot> skyBedrock(ServerPlayer player, RandomSource random, boolean relaxed) {
        ServerLevel level = player.serverLevel();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minHeight = level.getSeaLevel() + 20;
        for (int i = 0; i < (relaxed ? 60 : 36); i++) {
            int[] xz = ring(player, random, 10.0, 60.0);
            cursor.set(xz[0], player.getBlockY(), xz[1]);
            if (!level.isPositionEntityTicking(cursor)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, xz[0], xz[1]);
            if (top - 1 < minHeight) {
                continue;
            }
            cursor.setY(top - 1);
            if (!level.getBlockState(cursor).is(Blocks.BEDROCK)) {
                continue;
            }
            cursor.setY(top - 2);
            if (!level.getBlockState(cursor).isAir()) {
                continue;
            }
            BlockPos feet = new BlockPos(xz[0], top, xz[1]);
            if (standable(level, feet)) {
                return Optional.of(new Spot(Vec3.atBottomCenterOf(feet), false));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ checks

    /** Solid floor, two free blocks above it, no fluid, nothing that burns. Only in entity-ticking chunks. */
    public static boolean standable(ServerLevel level, BlockPos feet) {
        if (level.isOutsideBuildHeight(feet) || !level.isPositionEntityTicking(feet)) {
            return false;
        }
        BlockPos floorPos = feet.below();
        BlockState floor = level.getBlockState(floorPos);
        if (!floor.isFaceSturdy(level, floorPos, Direction.UP) || !floor.getFluidState().isEmpty()
                || floor.is(Blocks.MAGMA_BLOCK) || floor.is(BlockTags.CAMPFIRES)) {
            return false;
        }
        return isFree(level, feet) && isFree(level, feet.above());
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
                && !state.is(BlockTags.FIRE) && !state.is(Blocks.POWDER_SNOW) && !state.is(Blocks.SWEET_BERRY_BUSH)
                && !state.is(Blocks.CACTUS) && !state.is(Blocks.WITHER_ROSE) && !state.is(Blocks.POINTED_DRIPSTONE);
    }

    /** The standable height in a column closest to {@code centerY}, scanning {@code up} above and {@code down} below. */
    public static OptionalInt standableNear(ServerLevel level, int x, int z, int centerY, int up, int down) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, centerY, z);
        if (!level.isPositionEntityTicking(cursor)) {
            return OptionalInt.empty();
        }
        for (int offset = 0; offset <= Math.max(up, down); offset++) {
            if (offset <= down && standable(level, new BlockPos(x, centerY - offset, z))) {
                return OptionalInt.of(centerY - offset);
            }
            if (offset > 0 && offset <= up && standable(level, new BlockPos(x, centerY + offset, z))) {
                return OptionalInt.of(centerY + offset);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * A standing spot in the direction {@code angle} (degrees, Minecraft yaw) {@code distance}
     * blocks away: the surface if it is within {@code maxRise} of the player's height,
     * otherwise the nearest floor around the player's height (caves, the NULL dimension).
     */
    public static Optional<Spot> groundAt(ServerLevel level, Player player, float angle, double distance, int maxRise) {
        int x = Mth.floor(player.getX() - Mth.sin(angle * Mth.DEG_TO_RAD) * distance);
        int z = Mth.floor(player.getZ() + Mth.cos(angle * Mth.DEG_TO_RAD) * distance);
        int baseY = player.getBlockY();
        if (!level.isPositionEntityTicking(new BlockPos(x, baseY, z))) {
            return Optional.empty();
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(surface - baseY) <= maxRise && standable(level, new BlockPos(x, surface, z))) {
            return Optional.of(new Spot(new Vec3(x + 0.5, surface, z + 0.5), false));
        }
        OptionalInt y = standableNear(level, x, z, baseY, SCAN_UP, SCAN_DOWN);
        return y.isPresent() ? Optional.of(new Spot(new Vec3(x + 0.5, y.getAsInt(), z + 0.5), false)) : Optional.empty();
    }

    /** True if the player could see a figure standing at {@code feet} (head or chest unobstructed). */
    public static boolean visibleFrom(Player player, Vec3 feet) {
        Vec3 eye = player.getEyePosition();
        return clear(player, eye, feet.add(0.0, 1.6, 0.0)) || clear(player, eye, feet.add(0.0, 1.0, 0.0));
    }

    /** True if {@code pos} lies within the player's view cone (ignoring walls). */
    public static boolean inViewCone(Player player, Vec3 pos, double coneDot) {
        Vec3 to = pos.add(0.0, 1.0, 0.0).subtract(player.getEyePosition());
        if (to.lengthSqr() < 1.0E-6) {
            return true;
        }
        return player.getViewVector(1.0F).dot(to.normalize()) >= coneDot;
    }

    /** True if nothing blocks walking in a straight line from {@code from} to the player (sampled every block). */
    private static boolean clearPath(ServerLevel level, Player player, Vec3 from) {
        Vec3 to = player.position();
        double distance = from.distanceTo(to);
        int steps = Math.max(1, (int) distance);
        for (int i = 1; i < steps; i++) {
            Vec3 p = from.add(to.subtract(from).scale(i / (double) steps));
            BlockPos at = BlockPos.containing(p.x, from.y + 0.2 + (to.y - from.y) * i / steps, p.z);
            if (!level.isPositionEntityTicking(at)) {
                return false;
            }
            if (!isFree(level, at) && !isFree(level, at.above())) {
                return false;
            }
        }
        return true;
    }

    public static boolean nearAnyPlayer(ServerLevel level, Vec3 pos, double radius) {
        for (Player other : level.players()) {
            if (!other.isSpectator() && other.position().distanceToSqr(pos) < radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean clear(Player player, Vec3 from, Vec3 to) {
        return player.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                .getType() == HitResult.Type.MISS;
    }

    /** How many blocks of leaves hang over the ground in a column (0 outside forests or in unloaded chunks). */
    private static int canopy(ServerLevel level, int x, int z) {
        if (!level.isPositionEntityTicking(new BlockPos(x, level.getSeaLevel(), z))) {
            return 0;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static int[] ring(Player player, RandomSource random, double min, double max) {
        float angle = random.nextFloat() * 360.0F;
        double distance = Mth.lerp(random.nextDouble(), min, max);
        return new int[] {
                Mth.floor(player.getX() - Mth.sin(angle * Mth.DEG_TO_RAD) * distance),
                Mth.floor(player.getZ() + Mth.cos(angle * Mth.DEG_TO_RAD) * distance)};
    }

    /** Blocks that suggest something old and abandoned stands here. */
    public static boolean isAbandonedMarker(BlockState state) {
        return state.is(NullBlocks.RETRO_PLANKS.get()) || state.is(NullBlocks.RETRO_COBBLESTONE.get())
                || state.is(NullBlocks.RETRO_MOSSY_COBBLESTONE.get()) || state.is(NullBlocks.RETRO_BRICKS.get())
                || state.is(NullBlocks.RETRO_GLASS.get()) || state.is(Blocks.COBWEB) || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.MOSSY_STONE_BRICKS) || state.is(Blocks.CRACKED_STONE_BRICKS);
    }

    private static Optional<Spot> spotNearMarker(ServerLevel level, BlockPos marker, RandomSource random) {
        if (BuildProtection.playerBlocks(level, marker) > 0) {
            return Optional.empty();
        }
        Spot outside = null;
        for (int radius = 1; radius <= 4; radius++) {
            for (int attempt = 0; attempt < 6; attempt++) {
                int x = marker.getX() + random.nextInt(radius * 2 + 1) - radius;
                int z = marker.getZ() + random.nextInt(radius * 2 + 1) - radius;
                OptionalInt y = standableNear(level, x, z, marker.getY(), 3, 5);
                if (y.isEmpty()) {
                    continue;
                }
                BlockPos feet = new BlockPos(x, y.getAsInt(), z);
                Spot spot = new Spot(Vec3.atBottomCenterOf(feet), false);
                if (!level.canSeeSky(feet.above())) {
                    return Optional.of(spot);
                }
                if (outside == null) {
                    outside = spot;
                }
            }
        }
        return Optional.ofNullable(outside);
    }
}
