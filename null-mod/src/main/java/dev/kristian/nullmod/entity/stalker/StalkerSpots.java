package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.world.BuildProtection;
import dev.kristian.nullmod.world.LocationFinder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where the figure can be: behind a tree trunk, outside a window, in a doorway, at the end of a
 * tunnel, in the rock. Everything here reads only chunks that are already loaded and is asked once
 * per encounter, never every tick.
 */
final class StalkerSpots {
    private StalkerSpots() {
    }

    /** Behind a trunk: where its feet are, the way it leans out, and the yaw that faces the player. */
    record Trunk(BlockPos trunk, Vec3 hide, Vec3 side, float yaw) {
    }

    /** Outside a window: the glass at its eye level, the way out of the house, its feet, its yaw (facing in). */
    record Window(BlockPos glass, Direction outward, BlockPos feet, float yaw) {
    }

    /** A straight, level tunnel from the player: the cell it stands in at the far end, the way along, the wall. */
    record Corridor(BlockPos feet, Direction direction, BlockPos wall, int length) {
    }

    /** Never in the NULL dimension or the field; only in worlds with a sky (the overworld and its like). */
    static boolean allowedLevel(ServerLevel level) {
        return !NullDimensions.isNull(level) && !FieldDimension.isField(level) && level.dimensionType().hasSkyLight();
    }

    /** Loaded, dry and nothing to bump into. */
    static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = LocationFinder.stateIfLoaded(level, pos);
        return state != null && state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /** Two passable blocks over a top face it can stand on. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        if (!passable(level, feet) || !passable(level, feet.above())) {
            return false;
        }
        BlockPos below = feet.below();
        BlockState ground = LocationFinder.stateIfLoaded(level, below);
        return ground != null && ground.isFaceSturdy(level, below, Direction.UP);
    }

    // ------------------------------------------------------------------ trees

    /**
     * A natural tree trunk between the distances, roughly in the player's view, with room right
     * behind it (seen from the player) where the figure stands hidden, and a side it can lean out
     * to with a clear view of the player.
     */
    @Nullable
    static Trunk treeTrunk(ServerLevel level, ServerPlayer player, RandomSource random, double minDist, double maxDist, int attempts) {
        Vec3 eye = player.getEyePosition();
        for (int i = 0; i < attempts; i++) {
            LocationFinder.View view = i % 3 == 2 ? LocationFinder.View.EDGE : LocationFinder.View.VISIBLE;
            LocationFinder.Column column = LocationFinder.column(player, random, minDist, maxDist, view);
            int x = column.x();
            int z = column.z();
            if (!LocationFinder.isLoaded(level, x, z)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
            if (!level.getBlockState(cursor).is(BlockTags.LOGS)) {
                continue;
            }
            int logs = 0;
            while (logs < 40 && cursor.getY() > level.getMinBuildHeight() && level.getBlockState(cursor).is(BlockTags.LOGS)) {
                cursor.move(Direction.DOWN);
                logs++;
            }
            if (logs < 4 || !level.getBlockState(cursor).is(BlockTags.DIRT)) {
                continue;
            }
            BlockPos base = new BlockPos(x, cursor.getY() + 1, z);
            if (!singleTrunk(level, base) || !naturalCanopy(level, new BlockPos(x, top, z))) {
                continue;
            }
            double dx = x + 0.5 - player.getX();
            double dz = z + 0.5 - player.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < minDist * 0.8 || length > maxDist * 1.2) {
                continue;
            }
            dx /= length;
            dz /= length;
            BlockPos feet = groundNear(level, BlockPos.containing(x + 0.5 + dx * 0.95, base.getY(), z + 0.5 + dz * 0.95));
            if (feet == null) {
                continue;
            }
            Vec3 hide = new Vec3(x + 0.5 + dx * 0.95, feet.getY(), z + 0.5 + dz * 0.95);
            // Hidden: the trunk is what stands between the player's eyes and its chest.
            BlockHitResult hit = level.clip(new ClipContext(eye, hide.add(0.0, 1.3, 0.0), ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || !level.getBlockState(hit.getBlockPos()).is(BlockTags.LOGS)) {
                continue;
            }
            Vec3 across = new Vec3(-dz, 0.0, dx);
            double sign = random.nextBoolean() ? 1.0 : -1.0;
            for (int s = 0; s < 2; s++, sign = -sign) {
                Vec3 side = across.scale(sign);
                Vec3 head = hide.add(side.scale(0.75)).add(0.0, StalkerEntity.VISUAL_EYE_HEIGHT - 0.1, 0.0);
                if (StalkerSight.clear(level, player, eye, head)) {
                    return new Trunk(base, hide, side, StalkerSight.yawTowards(hide, player.position()));
                }
            }
        }
        return null;
    }

    /** No other log beside the trunk at its foot: a single trunk, not a log wall or a giant tree. */
    private static boolean singleTrunk(ServerLevel level, BlockPos base) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int up = 0; up <= 1; up++) {
                BlockState state = LocationFinder.stateIfLoaded(level, base.relative(side).above(up));
                if (state == null || state.is(BlockTags.LOGS)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Leaves that grew there (not placed by a player) around the top of the trunk. */
    private static boolean naturalCanopy(ServerLevel level, BlockPos topLog) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    cursor.set(topLog.getX() + dx, topLog.getY() + dy, topLog.getZ() + dz);
                    BlockState state = LocationFinder.stateIfLoaded(level, cursor);
                    if (state != null && state.is(BlockTags.LEAVES) && state.hasProperty(BlockStateProperties.PERSISTENT)
                            && !state.getValue(BlockStateProperties.PERSISTENT)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A standing spot in this column within one block up or down, or null. */
    @Nullable
    static BlockPos groundNear(ServerLevel level, BlockPos around) {
        for (int dy : new int[]{0, -1, 1}) {
            BlockPos feet = around.above(dy);
            if (standable(level, feet)) {
                return feet;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ windows

    /**
     * Whether the player is in a house: something solid over their head and no sky above them.
     * (Deep underground is not a house: the windows search below also needs sky outside.)
     */
    static boolean indoors(ServerLevel level, ServerPlayer player) {
        BlockPos head = BlockPos.containing(player.getEyePosition());
        if (level.canSeeSky(head)) {
            return false;
        }
        for (int up = 1; up <= 8; up++) {
            BlockState state = LocationFinder.stateIfLoaded(level, head.above(up));
            if (state != null && !state.isAir() && !state.is(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A window of the room the player is in with a place outside for the figure to stand, its face
     * behind the glass and in clear view of the player through it. Windows the player is not
     * looking at are preferred (it should be there when they turn, not appear before their eyes).
     */
    @Nullable
    static Window window(ServerLevel level, ServerPlayer player, RandomSource random, boolean relaxed) {
        BlockPos feet = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        int radius = relaxed ? 12 : 9;
        List<Window> hidden = new ArrayList<>();
        List<Window> visible = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal < (relaxed ? 1.5 : 2.5) || horizontal > radius) {
                    continue;
                }
                for (int dy = 0; dy <= 3; dy++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockState state = LocationFinder.stateIfLoaded(level, cursor);
                    if (state == null || !TemporaryChanges.isWindow(state) || state.is(Blocks.TINTED_GLASS)) {
                        continue;
                    }
                    Window found = windowAt(level, player, eye, cursor.immutable());
                    if (found == null) {
                        continue;
                    }
                    Vec3 face = Vec3.atBottomCenterOf(found.feet()).add(0.0, StalkerEntity.VISUAL_EYE_HEIGHT, 0.0);
                    (StalkerSight.inCone(player, face, StalkerSight.ON_SCREEN_DOT) ? visible : hidden).add(found);
                }
            }
        }
        if (!hidden.isEmpty()) {
            return hidden.get(random.nextInt(hidden.size()));
        }
        return visible.isEmpty() ? null : visible.get(random.nextInt(visible.size()));
    }

    /**
     * The place outside this window (seen from the player's eyes) where the figure can stand with
     * its face behind the glass, or null.
     */
    @Nullable
    static Window windowAt(ServerLevel level, ServerPlayer player, Vec3 eye, BlockPos glass) {
        Vec3 center = Vec3.atCenterOf(glass);
        for (Direction out : Direction.Plane.HORIZONTAL) {
            // The player has to be inside, behind the glass.
            double along = (eye.x - center.x) * out.getStepX() + (eye.z - center.z) * out.getStepZ();
            if (along > -0.5) {
                continue;
            }
            BlockPos outside = glass.relative(out);
            if (!passable(level, outside) || !level.canSeeSky(outside)) {
                continue;
            }
            for (int dy = 0; dy >= -3; dy--) {
                BlockPos stand = outside.above(dy);
                if (!standable(level, stand) || !passable(level, stand.above(2))) {
                    continue;
                }
                double eyeY = stand.getY() + StalkerEntity.VISUAL_EYE_HEIGHT;
                BlockPos wallAtEyes = new BlockPos(glass.getX(), (int) Math.floor(eyeY), glass.getZ());
                BlockState atEyes = LocationFinder.stateIfLoaded(level, wallAtEyes);
                if (atEyes == null || !TemporaryChanges.isWindow(atEyes) || atEyes.is(Blocks.TINTED_GLASS)) {
                    continue;
                }
                Vec3 face = new Vec3(stand.getX() + 0.5, eyeY, stand.getZ() + 0.5);
                if (!StalkerSight.clear(level, player, eye, face)) {
                    continue;
                }
                float yaw = StalkerSight.yawTowards(face, face.subtract(out.getStepX(), 0.0, out.getStepZ()));
                return new Window(wallAtEyes, out, stand, yaw);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ doors and panes

    /** Every door, trapdoor and gate the figure may open around a position, nearest last. */
    static List<BlockPos> doors(ServerLevel level, BlockPos center, int radius, int height) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -height; dy <= height; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = LocationFinder.stateIfLoaded(level, cursor);
                    if (state != null && TemporaryChanges.isOpenableDoor(state)) {
                        found.add(cursor.immutable());
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center)).reversed());
        return found;
    }

    /**
     * A window near the player that may shatter ({@link TemporaryChanges#canShatter}), preferably
     * one they can see or almost see.
     */
    @Nullable
    static BlockPos breakablePane(ServerLevel level, ServerPlayer player, RandomSource random, boolean relaxed) {
        BlockPos feet = player.blockPosition();
        int radius = relaxed ? 14 : 10;
        double minDist = relaxed ? 2.0 : 3.0;
        List<BlockPos> inSight = new ArrayList<>();
        List<BlockPos> others = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    double distance = Math.sqrt(cursor.distSqr(feet));
                    if (distance < minDist || distance > radius) {
                        continue;
                    }
                    BlockState state = LocationFinder.stateIfLoaded(level, cursor);
                    if (state == null || !TemporaryChanges.isWindow(state) || !TemporaryChanges.canShatter(level, cursor)) {
                        continue;
                    }
                    Vec3 center = Vec3.atCenterOf(cursor);
                    boolean seen = StalkerSight.inCone(player, center, 0.3)
                            && StalkerSight.clear(level, player, player.getEyePosition(), center);
                    (seen ? inSight : others).add(cursor.immutable());
                }
            }
        }
        List<BlockPos> pool = inSight.isEmpty() ? others : inSight;
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    // ------------------------------------------------------------------ underground

    /** Natural stone or deepslate the miner may dig through (never ores, never anything built). */
    static boolean diggable(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) && BuildProtection.isNatural(state);
    }

    /**
     * The horizontal direction from the player along which the rock between {@code near} and
     * {@code far} blocks is (almost) all natural stone, at the player's head height; null if none.
     */
    @Nullable
    static Direction solidRock(ServerLevel level, ServerPlayer player, RandomSource random, int near, int far) {
        BlockPos head = player.blockPosition().above();
        List<Direction> order = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        Collections.shuffle(order, new Random(random.nextLong()));
        Direction best = null;
        double bestShare = 0.7;
        for (Direction direction : order) {
            int stone = 0;
            int samples = 0;
            boolean unloaded = false;
            for (int k = near; k <= far; k += 2) {
                BlockState state = LocationFinder.stateIfLoaded(level, head.relative(direction, k));
                if (state == null) {
                    unloaded = true;
                    break;
                }
                samples++;
                if (diggable(state)) {
                    stone++;
                }
            }
            if (unloaded || samples == 0) {
                continue;
            }
            double share = stone / (double) samples;
            if (share > bestShare) {
                bestShare = share;
                best = direction;
            }
        }
        return best;
    }

    /**
     * The first blocked cell walking from the player's feet along a direction (where the open space
     * the player is in meets the rock), within {@code max} blocks; null if the way stays open.
     */
    @Nullable
    static BlockPos wallAlong(ServerLevel level, BlockPos from, Direction direction, int max) {
        for (int k = 1; k <= max; k++) {
            BlockPos cell = from.relative(direction, k);
            if (LocationFinder.stateIfLoaded(level, cell) == null) {
                return null;
            }
            if (!passable(level, cell) || !passable(level, cell.above())) {
                return cell;
            }
        }
        return null;
    }

    /**
     * A straight, level, open tunnel leading away from the player (the way they look first),
     * between {@code minLength} and {@code maxLength} blocks long and ending at a wall.
     */
    @Nullable
    static Corridor corridor(ServerLevel level, ServerPlayer player, int minLength, int maxLength) {
        BlockPos start = player.blockPosition();
        Vec3 look = player.getViewVector(1.0F);
        List<Direction> order = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        order.sort(Comparator.comparingDouble((Direction d) -> -(d.getStepX() * look.x + d.getStepZ() * look.z)));
        for (Direction direction : order) {
            int length = 0;
            BlockPos wall = null;
            for (int k = 1; k <= maxLength + 1; k++) {
                BlockPos cell = start.relative(direction, k);
                if (!standable(level, cell)) {
                    wall = cell;
                    break;
                }
                length = k;
            }
            if (wall == null || length < minLength) {
                continue;
            }
            BlockState end = LocationFinder.stateIfLoaded(level, wall);
            if (end == null || end.getCollisionShape(level, wall).isEmpty()) {
                // The way drops away or is wet rather than ending at a wall.
                continue;
            }
            return new Corridor(start.relative(direction, length), direction, wall, length);
        }
        return null;
    }
}
