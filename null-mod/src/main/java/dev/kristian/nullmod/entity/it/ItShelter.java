package dev.kristian.nullmod.entity.it;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;

/**
 * "Indoors": the test that decides whether IT can get at a player. Starting in the cells the
 * player occupies, it floods the air around them (six directions, through anything a person could
 * pass: air, plants, carpets, open doors, open trapdoors and gates, leaves). The player is
 * sheltered if the flood stays inside a closed room: it never reaches a cell open to the sky (at
 * or above the column's highest motion-blocking block, leaves excluded), never leaves a box of
 * {@value ItTuning#SHELTER_RADIUS} blocks around them ({@value ItTuning#SHELTER_UP} up,
 * {@value ItTuning#SHELTER_DOWN} down), and never needs more than {@value ItTuning#SHELTER_MAX_CELLS}
 * cells. So: a solid roof, walls on every side, a closed door, unbroken windows. It works the same
 * for the field's houses, ordinary player houses and a hole with a lid; a missing window pane or
 * an open door is a way in. Unloaded cells count as open.
 * <p>
 * The survey also notes the room's windows and walls (with the direction out of the room), which
 * IT uses when it prowls around a house. Server thread only.
 */
public final class ItShelter {
    /** A window pane or a piece of wall around a room and the direction out of the room through it. */
    public record Opening(BlockPos pos, Direction outward) {
    }

    /** What the flood found. */
    public record Room(boolean enclosed, BlockPos min, BlockPos max, List<Opening> windows, List<Opening> walls) {
        static final Room OPEN = new Room(false, BlockPos.ZERO, BlockPos.ZERO, List.of(), List.of());

        /** Centre of the room's air (block coordinates, bottom of the floor cells). */
        public Vec3 center() {
            return new Vec3((min.getX() + max.getX() + 1) * 0.5, min.getY(), (min.getZ() + max.getZ() + 1) * 0.5);
        }

        /** Half the room's size along x and z, at least one block. */
        public double halfX() {
            return Math.max(1.0, (max.getX() - min.getX() + 1) * 0.5);
        }

        public double halfZ() {
            return Math.max(1.0, (max.getZ() - min.getZ() + 1) * 0.5);
        }
    }

    private record Cached(ResourceKey<Level> level, long time, BlockPos feet, Room room) {
    }

    private static final int MAX_WINDOWS = 32;
    private static final int MAX_WALLS = 48;
    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private ItShelter() {
    }

    /** True if this player is hidden from IT right now (a fresh test, shared within one tick). */
    public static boolean isSheltered(ServerPlayer player) {
        return room(player, 0).enclosed();
    }

    /**
     * The room around a player. A result for the same block, world and player is reused for up
     * to {@code maxAgeTicks} (0: only within the same tick).
     */
    public static Room room(ServerPlayer player, int maxAgeTicks) {
        Level level = player.level();
        long now = level.getGameTime();
        BlockPos feet = feetOf(player);
        Cached cached = CACHE.get(player.getUUID());
        if (cached != null && cached.level() == level.dimension() && cached.feet().equals(feet)
                && now >= cached.time() && now - cached.time() <= maxAgeTicks) {
            return cached.room();
        }
        Room room = survey(level, feet, BlockPos.containing(player.getEyePosition()));
        CACHE.put(player.getUUID(), new Cached(level.dimension(), now, feet, room));
        return room;
    }

    /** True if a person standing with their feet in this block would be sheltered. */
    public static boolean isEnclosed(Level level, BlockPos feet) {
        return survey(level, feet, feet.above()).enclosed();
    }

    /** Forgets what was cached for a player (logout, dimension change). */
    public static void forget(UUID player) {
        CACHE.remove(player);
    }

    public static void clear() {
        CACHE.clear();
    }

    /** The block a player's feet are in (a little above the soles, so slabs and farmland count as floor). */
    public static BlockPos feetOf(ServerPlayer player) {
        return BlockPos.containing(player.getX(), player.getY() + 0.25, player.getZ());
    }

    /** Floods the air from the feet and head cells and reports the room, or {@link Room#OPEN}. */
    public static Room survey(Level level, BlockPos feet, BlockPos head) {
        if (!level.isLoaded(feet) || !level.isLoaded(head) || openToSky(level, feet) || openToSky(level, head)) {
            return Room.OPEN;
        }
        int sx = feet.getX();
        int sy = feet.getY();
        int sz = feet.getZ();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        List<Opening> windows = new ArrayList<>();
        List<Opening> walls = new ArrayList<>();
        queue.add(feet.immutable());
        seen.add(feet.asLong());
        if (!head.equals(feet)) {
            queue.add(head.immutable());
            seen.add(head.asLong());
        }
        int minX = sx;
        int minY = sy;
        int minZ = sz;
        int maxX = sx;
        int maxY = sy;
        int maxZ = sz;
        int cells = 0;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (++cells > ItTuning.SHELTER_MAX_CELLS
                    || Math.abs(pos.getX() - sx) > ItTuning.SHELTER_RADIUS || Math.abs(pos.getZ() - sz) > ItTuning.SHELTER_RADIUS
                    || pos.getY() - sy > ItTuning.SHELTER_UP || sy - pos.getY() > ItTuning.SHELTER_DOWN
                    || openToSky(level, pos)) {
                return Room.OPEN;
            }
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                long key = next.asLong();
                if (seen.contains(key)) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    return Room.OPEN;
                }
                BlockState state = level.getBlockState(next);
                if (passable(level, next, state)) {
                    seen.add(key);
                    queue.add(next);
                } else if (dir.getAxis().isHorizontal()) {
                    if (isGlass(state)) {
                        if (windows.size() < MAX_WINDOWS) {
                            windows.add(new Opening(next, dir));
                        }
                    } else if (walls.size() < MAX_WALLS) {
                        walls.add(new Opening(next, dir));
                    }
                }
            }
        }
        return new Room(true, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), List.copyOf(windows), List.copyOf(walls));
    }

    /** At or above the highest block in the column that blocks movement (leaves do not count): nothing overhead. */
    private static boolean openToSky(Level level, BlockPos pos) {
        return pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
    }

    /** Whether a person could get through this cell. */
    static boolean passable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        Block block = state.getBlock();
        if (block instanceof DoorBlock) {
            return state.getValue(DoorBlock.OPEN);
        }
        if (block instanceof TrapDoorBlock) {
            return state.getValue(TrapDoorBlock.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return state.getValue(FenceGateBlock.OPEN);
        }
        if (state.is(BlockTags.LEAVES)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return true;
        }
        // Carpets, snow layers, pressure plates and the like are floor, not wall.
        return shape.max(Direction.Axis.Y) - shape.min(Direction.Axis.Y) < 0.5;
    }

    static boolean isGlass(BlockState state) {
        return state.is(Tags.Blocks.GLASS_PANES) || state.is(Tags.Blocks.GLASS_BLOCKS) || state.getBlock() instanceof IronBarsBlock;
    }
}
