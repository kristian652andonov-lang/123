package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * A structure described in local coordinates, turned into a {@link PlacementJob} at a world
 * position with a rotation and mirror. Setting a block twice keeps the last one. When it becomes
 * a job, blocks are ordered so nothing is placed before what holds it: carved air first, then
 * solid blocks bottom-up, then attached things (torches, ladders, doors, signs, plants).
 */
public final class Blueprint {
    public static final int PHASE_CARVE = 0;
    public static final int PHASE_SOLID = 1;
    public static final int PHASE_ATTACHED = 2;

    /** A read-only view of one planned block. */
    public record Cell(int x, int y, int z, BlockState state, SafeBlockPlacer.Mode mode, int phase) {
    }

    private static final class Entry {
        final int x;
        final int y;
        final int z;
        final BlockState state;
        final SafeBlockPlacer.Mode mode;
        final SafeBlockPlacer.Support support;
        final int phase;
        final int order;
        final int group;
        final int sub;
        final int sortY;
        @Nullable
        final PlacementJob.PostPlace post;

        Entry(int x, int y, int z, BlockState state, SafeBlockPlacer.Mode mode, SafeBlockPlacer.Support support,
              int phase, int order, int group, int sub, int sortY, @Nullable PlacementJob.PostPlace post) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.mode = mode;
            this.support = support;
            this.phase = phase;
            this.order = order;
            this.group = group;
            this.sub = sub;
            this.sortY = sortY;
            this.post = post;
        }
    }

    private final Map<Long, Entry> entries = new HashMap<>();
    private SafeBlockPlacer.Mode mode = SafeBlockPlacer.Mode.PLACE_INTO_AIR;
    private int counter;
    private int groups;

    /** The mode used by the following calls that do not name one. */
    public Blueprint mode(SafeBlockPlacer.Mode mode) {
        this.mode = mode;
        return this;
    }

    public SafeBlockPlacer.Mode mode() {
        return mode;
    }

    // ------------------------------------------------------------------ setting blocks

    /** A solid part of the structure (air means "carve this"). */
    public Blueprint set(int x, int y, int z, BlockState state) {
        return put(x, y, z, state, mode, SafeBlockPlacer.Support.NONE, state.isAir() ? PHASE_CARVE : PHASE_SOLID, 0, 0, 0, null);
    }

    public Blueprint set(int x, int y, int z, BlockState state, SafeBlockPlacer.Mode mode) {
        return put(x, y, z, state, mode, SafeBlockPlacer.Support.NONE, state.isAir() ? PHASE_CARVE : PHASE_SOLID, 0, 0, 0, null);
    }

    public Blueprint set(int x, int y, int z, Block block) {
        return set(x, y, z, block.defaultBlockState());
    }

    /** Something that needs support (torch, ladder, plant, snow): placed last, only if it can stay. */
    public Blueprint attach(int x, int y, int z, BlockState state) {
        return put(x, y, z, state, mode, SafeBlockPlacer.Support.SURVIVE, PHASE_ATTACHED, 0, 0, 0, null);
    }

    /** Placed last but without a support check: things that are meant to hang where they should not. */
    public Blueprint loose(int x, int y, int z, BlockState state) {
        return put(x, y, z, state, mode, SafeBlockPlacer.Support.NONE, PHASE_ATTACHED, 0, 0, 0, null);
    }

    /** Something that needs a full face below it (sand on the ground). */
    public Blueprint grounded(int x, int y, int z, BlockState state) {
        return put(x, y, z, state, mode, SafeBlockPlacer.Support.SOLID_BELOW, PHASE_SOLID, 0, 0, 0, null);
    }

    /** Turns natural ground into air (corridors, shafts). */
    public Blueprint carve(int x, int y, int z) {
        return put(x, y, z, Blocks.AIR.defaultBlockState(), SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.NONE,
                PHASE_CARVE, 0, 0, 0, null);
    }

    public Blueprint fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, state);
                }
            }
        }
        return this;
    }

    public Blueprint remove(int x, int y, int z) {
        Entry old = entries.remove(BlockPos.asLong(x, y, z));
        if (old != null && old.group != 0) {
            removeGroup(old.group);
        }
        return this;
    }

    public boolean has(int x, int y, int z) {
        return entries.containsKey(BlockPos.asLong(x, y, z));
    }

    @Nullable
    public BlockState get(int x, int y, int z) {
        Entry e = entries.get(BlockPos.asLong(x, y, z));
        return e == null ? null : e.state;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** All planned blocks (unordered). */
    public List<Cell> cells() {
        List<Cell> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            out.add(new Cell(e.x, e.y, e.z, e.state, e.mode, e.phase));
        }
        return out;
    }

    /** Removes every planned block for which {@code test} is true (gaps in broken buildings). */
    public void removeIf(java.util.function.Predicate<Cell> test) {
        List<Long> doomed = new ArrayList<>();
        for (Map.Entry<Long, Entry> entry : entries.entrySet()) {
            Entry e = entry.getValue();
            if (test.test(new Cell(e.x, e.y, e.z, e.state, e.mode, e.phase))) {
                doomed.add(entry.getKey());
            }
        }
        for (Long key : doomed) {
            Entry e = entries.get(key);
            if (e != null) {
                remove(e.x, e.y, e.z);
            }
        }
    }

    // ------------------------------------------------------------------ compound pieces

    /**
     * A door: both halves, placed together or not at all. {@code facing} is the way someone
     * walking through it faces. With {@code needsFloor} the lower half needs a full block below.
     */
    public Blueprint door(int x, int y, int z, Block door, Direction facing, DoorHingeSide hinge, boolean open, boolean needsFloor) {
        int group = ++groups;
        int order = counter++;
        BlockState lower = door.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        put(x, y, z, lower, mode, needsFloor ? SafeBlockPlacer.Support.SOLID_BELOW : SafeBlockPlacer.Support.NONE,
                PHASE_ATTACHED, order, group, 0, null);
        put(x, y + 1, z, upper, mode, SafeBlockPlacer.Support.NONE, PHASE_ATTACHED, order, group, 1, null);
        return this;
    }

    /** A standing oak sign with its text set (and waxed so it cannot be edited) once placed. */
    public Blueprint sign(int x, int y, int z, int rotation16, SignText front, @Nullable SignText back, boolean needsGround) {
        BlockState state = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation16 & 15);
        return put(x, y, z, state, mode, needsGround ? SafeBlockPlacer.Support.SURVIVE : SafeBlockPlacer.Support.NONE,
                PHASE_ATTACHED, 0, 0, 0, signWriter(front, back));
    }

    /** A wall sign hanging on the block behind it ({@code facing} points away from that block). */
    public Blueprint wallSign(int x, int y, int z, Direction facing, SignText front) {
        BlockState state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing);
        return put(x, y, z, state, mode, SafeBlockPlacer.Support.SURVIVE, PHASE_ATTACHED, 0, 0, 0, signWriter(front, null));
    }

    /** Stairs: {@code facing} is the side the tall part is on. */
    public static BlockState stairs(Block stairs, Direction facing, boolean upsideDown) {
        return stairs.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.HALF, upsideDown ? Half.TOP : Half.BOTTOM);
    }

    /** Builds sign text from up to four lines. */
    public static SignText text(List<Component> lines, DyeColor color, boolean glowing) {
        SignText text = new SignText();
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            text = text.setMessage(i, lines.get(i));
        }
        return text.setColor(color).setHasGlowingText(glowing);
    }

    /** Writes text onto the sign at the position (and waxes it) right after it was placed. */
    public static PlacementJob.PostPlace signWriter(SignText front, @Nullable SignText back) {
        return (level, pos) -> {
            if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                sign.setText(front, true);
                if (back != null) {
                    sign.setText(back, false);
                }
                sign.setWaxed(true);
            }
        };
    }

    // ------------------------------------------------------------------ transforms

    /** A copy with Y flipped inside {@code [0, height-1]}: stairs and doors turn upside down too. */
    public Blueprint flippedY(int height) {
        Blueprint out = new Blueprint();
        out.counter = counter;
        out.groups = groups;
        for (Entry e : entries.values()) {
            int y = height - 1 - e.y;
            BlockState state = e.state;
            if (state.hasProperty(BlockStateProperties.HALF)) {
                state = state.setValue(BlockStateProperties.HALF,
                        state.getValue(BlockStateProperties.HALF) == Half.TOP ? Half.BOTTOM : Half.TOP);
            }
            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                state = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                        state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
            }
            // Nothing upside down can be "supported"; it simply hangs there.
            out.entries.put(BlockPos.asLong(e.x, y, e.z), new Entry(e.x, y, e.z, state, e.mode, SafeBlockPlacer.Support.NONE,
                    e.phase, e.order, e.group, 1 - e.sub, height - 1 - e.sortY, e.post));
        }
        return out;
    }

    /** World position of a local block. Mirror first, then rotation, like vanilla structures. */
    public static BlockPos toWorld(BlockPos origin, int x, int y, int z, Rotation rotation, Mirror mirror) {
        int mx = x;
        int mz = z;
        if (mirror == Mirror.LEFT_RIGHT) {
            mz = -mz;
        } else if (mirror == Mirror.FRONT_BACK) {
            mx = -mx;
        }
        int rx;
        int rz;
        if (rotation == Rotation.CLOCKWISE_90) {
            rx = -mz;
            rz = mx;
        } else if (rotation == Rotation.CLOCKWISE_180) {
            rx = -mx;
            rz = -mz;
        } else if (rotation == Rotation.COUNTERCLOCKWISE_90) {
            rx = mz;
            rz = -mx;
        } else {
            rx = mx;
            rz = mz;
        }
        return origin.offset(rx, y, rz);
    }

    /** The rotation that turns local south (+Z) into {@code direction}. */
    public static Rotation rotationFor(Direction direction) {
        return switch (direction) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** {min, max} world corners of everything planned. */
    public BlockPos[] worldBounds(BlockPos origin, Rotation rotation, Mirror mirror) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Entry e : entries.values()) {
            BlockPos p = toWorld(origin, e.x, e.y, e.z, rotation, mirror);
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (entries.isEmpty()) {
            return new BlockPos[]{origin, origin};
        }
        return new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }

    /** Appends everything to a job in a safe placing order. */
    public void appendTo(PlacementJob job, BlockPos origin, Rotation rotation, Mirror mirror) {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparingInt((Entry e) -> e.phase)
                .thenComparingInt(e -> e.sortY)
                .thenComparingInt(e -> e.order)
                .thenComparingInt(e -> e.sub));
        Map<Integer, Integer> groupIds = new HashMap<>();
        for (Entry e : list) {
            BlockPos pos = toWorld(origin, e.x, e.y, e.z, rotation, mirror);
            BlockState state = e.state.mirror(mirror).rotate(rotation);
            int group = e.group == 0 ? 0 : groupIds.computeIfAbsent(e.group, g -> job.newGroup());
            job.add(pos, state, e.mode, e.support, group, e.post);
        }
    }

    /** Copies another blueprint in, shifted by an offset (later blocks win). */
    public Blueprint merge(Blueprint other, int dx, int dy, int dz) {
        Map<Integer, Integer> groupIds = new HashMap<>();
        Map<Integer, Integer> orders = new HashMap<>();
        List<Entry> list = new ArrayList<>(other.entries.values());
        list.sort(Comparator.comparingInt((Entry e) -> e.order).thenComparingInt(e -> e.sub));
        for (Entry e : list) {
            int group = e.group == 0 ? 0 : groupIds.computeIfAbsent(e.group, g -> ++groups);
            int order = e.group == 0 ? counter++ : orders.computeIfAbsent(e.group, g -> counter++);
            insert(e.x + dx, e.y + dy, e.z + dz, e.state, e.mode, e.support, e.phase, order, group, e.sub, e.sortY + dy, e.post);
        }
        return this;
    }

    // ------------------------------------------------------------------ internals

    private Blueprint put(int x, int y, int z, BlockState state, SafeBlockPlacer.Mode mode, SafeBlockPlacer.Support support,
                          int phase, int order, int group, int sub, @Nullable PlacementJob.PostPlace post) {
        // Both halves of a group sort by the lower half's height so they stay next to each other.
        int sortY = group != 0 && sub == 1 ? y - 1 : y;
        return insert(x, y, z, state, mode, support, phase, group == 0 ? counter++ : order, group, sub, sortY, post);
    }

    private Blueprint insert(int x, int y, int z, BlockState state, SafeBlockPlacer.Mode mode, SafeBlockPlacer.Support support,
                             int phase, int order, int group, int sub, int sortY, @Nullable PlacementJob.PostPlace post) {
        long key = BlockPos.asLong(x, y, z);
        Entry old = entries.get(key);
        if (old != null && old.group != 0 && old.group != group) {
            removeGroup(old.group);
        }
        entries.put(key, new Entry(x, y, z, state, mode, support, phase, order, group, sub, sortY, post));
        return this;
    }

    private void removeGroup(int group) {
        Iterator<Map.Entry<Long, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().group == group) {
                it.remove();
            }
        }
    }
}
