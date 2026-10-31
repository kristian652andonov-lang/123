package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.world.LocationFinder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * The only changes the figures make to things players built, and the guarantee that every one of
 * them is undone. Two kinds exist:
 * <ul>
 * <li><b>A shattered window pane.</b> The exact block state is kept and put back into the empty
 * spot: after a short while once nobody is looking at it, at the latest after
 * {@code maxTicks} (even in plain view), at once when its player leaves (logs out, dies, changes
 * dimension or walks away), when its chunk is about to unload (written straight into the chunk
 * before it is saved), when the server stops and on {@code /null reset}. Nothing is dropped and no
 * neighbour is updated, so the window comes back exactly as it was. If something else was put
 * into the hole meanwhile it is left alone.</li>
 * <li><b>An opened door</b> (wooden door, wooden trapdoor or fence gate). It is closed again a few
 * minutes later when nobody is looking and nobody stands in it, as long as nobody has touched it
 * since (a door a player closed or changed is theirs again).</li>
 * </ul>
 * Pending changes are also written to the world's NULL data, so even after a crash they are undone
 * when the world is loaded again. Server thread only.
 */
public final class TemporaryChanges {
    public static final TemporaryChanges INSTANCE = new TemporaryChanges();

    /** Clients are told; neighbours are not (no shape updates, no physics), so the pane comes back exactly. */
    static final int PANE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** As vanilla toggles doors: clients are told at once and the other half follows through its shape update. */
    static final int DOOR_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE;
    /** Its player further away than this counts as gone. */
    private static final double OWNER_RANGE = 40.0;
    private static final int CHECK_INTERVAL = 5;
    private static final String SYSTEM_KEY = "stalkers";
    private static final String LIST_KEY = "temporary";
    /** Most changes pending at once (each player has at most one encounter, which makes at most four). */
    private static final int MAX_PENDING = 96;

    public enum Kind { PANE, DOOR }

    private record Key(ResourceKey<Level> dimension, long pos) {
    }

    private static final class Change {
        final Kind kind;
        final ResourceKey<Level> dimension;
        final BlockPos pos;
        /** Pane: the pane that was there. Door: the closed door before it was opened. */
        final BlockState original;
        /** Pane: air. Door: the open door as it was left. */
        final BlockState changed;
        @Nullable
        final UUID owner;
        final long createdAt;
        final int minTicks;
        final int maxTicks;
        /** Read back from the world's data after a restart: undone as soon as its chunk is there. */
        boolean fromDisk;
        int unseenTicks;

        Change(Kind kind, ResourceKey<Level> dimension, BlockPos pos, BlockState original, BlockState changed,
               @Nullable UUID owner, long createdAt, int minTicks, int maxTicks) {
            this.kind = kind;
            this.dimension = dimension;
            this.pos = pos.immutable();
            this.original = original;
            this.changed = changed;
            this.owner = owner;
            this.createdAt = createdAt;
            this.minTicks = minTicks;
            this.maxTicks = Math.max(minTicks, maxTicks);
        }
    }

    private final Map<Key, Change> changes = new LinkedHashMap<>();
    private boolean loaded;

    private TemporaryChanges() {
    }

    // ------------------------------------------------------------------ making changes

    /**
     * True if the block is a window (glass or a glass pane) that may shatter for a moment: not
     * waterlogged, no block entity, nothing that could pour in or fall through, nothing hanging on
     * it, nobody standing on it, and every neighbour either air, glass or a full solid block (so
     * nothing loses its support while it is gone).
     */
    public static boolean canShatter(ServerLevel level, BlockPos pos) {
        if (NullDimensions.isNull(level) || FieldDimension.isField(level)) {
            return false;
        }
        BlockState state = LocationFinder.stateIfLoaded(level, pos);
        if (state == null || !isWindow(state) || state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction side : Direction.values()) {
            cursor.setWithOffset(pos, side);
            BlockState neighbour = LocationFinder.stateIfLoaded(level, cursor);
            if (neighbour == null || !neighbour.getFluidState().isEmpty()) {
                return false;
            }
            if (!neighbour.isAir() && !isWindow(neighbour) && !neighbour.isCollisionShapeFullBlock(level, cursor)) {
                return false;
            }
        }
        AABB around = new AABB(pos).inflate(1.0);
        if (!level.getEntitiesOfClass(HangingEntity.class, around).isEmpty()) {
            return false;
        }
        AABB onTop = new AABB(pos).inflate(0.3, 0.0, 0.3).expandTowards(0.0, 1.5, 0.0);
        return level.getEntitiesOfClass(Entity.class, onTop, entity -> !(entity instanceof StalkerEntity)).isEmpty();
    }

    public static boolean isWindow(BlockState state) {
        return state.is(Tags.Blocks.GLASS_PANES) || state.is(Tags.Blocks.GLASS_BLOCKS);
    }

    /**
     * Shatters a window: glass-breaking particles and sound for everyone near, the block gone (no
     * drop), and the exact state kept to be put back. Refused (false) when the settings forbid it
     * or {@link #canShatter} says no.
     */
    public boolean shatter(ServerLevel level, BlockPos pos, @Nullable ServerPlayer owner, int minTicks, int maxTicks) {
        if (!StalkerSettings.windowBreaking() || !canShatter(level, pos) || changes.size() >= MAX_PENDING) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), PANE_FLAGS)) {
            return false;
        }
        add(level, new Change(Kind.PANE, level.dimension(), pos, state, Blocks.AIR.defaultBlockState(),
                owner == null ? null : owner.getUUID(), level.getServer().getTickCount(), minTicks, maxTicks));
        return true;
    }

    /**
     * True if this is something the figure may open: the lower half of a closed wooden door that
     * opens by hand, a closed wooden trapdoor or a closed fence gate; never powered (redstone owns
     * it then) and never waterlogged.
     */
    public static boolean isOpenableDoor(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.OPEN) || state.getValue(BlockStateProperties.OPEN)) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock door) {
            return state.is(BlockTags.WOODEN_DOORS) && door.type().canOpenByHand()
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            return state.is(BlockTags.WOODEN_TRAPDOORS);
        }
        return state.getBlock() instanceof FenceGateBlock && state.is(BlockTags.FENCE_GATES);
    }

    /**
     * Opens a door (nothing else about it changes; the other half of a door follows on its own) and
     * remembers to close it again. Refused (false) when the settings forbid it or it is not
     * {@link #isOpenableDoor openable}.
     */
    public boolean openDoor(ServerLevel level, BlockPos pos, @Nullable Entity cause, @Nullable ServerPlayer owner,
                            int minTicks, int maxTicks) {
        if (!StalkerSettings.doorOpening() || NullDimensions.isNull(level) || FieldDimension.isField(level)
                || changes.size() >= MAX_PENDING) {
            return false;
        }
        BlockState state = LocationFinder.stateIfLoaded(level, pos);
        if (state == null || !isOpenableDoor(state)) {
            return false;
        }
        if (!level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, Boolean.TRUE), DOOR_FLAGS)) {
            return false;
        }
        level.gameEvent(cause, GameEvent.BLOCK_OPEN, pos);
        add(level, new Change(Kind.DOOR, level.dimension(), pos, state, level.getBlockState(pos),
                owner == null ? null : owner.getUUID(), level.getServer().getTickCount(), minTicks, maxTicks));
        return true;
    }

    private void add(ServerLevel level, Change change) {
        changes.put(new Key(change.dimension, change.pos.asLong()), change);
        save(level.getServer());
    }

    // ------------------------------------------------------------------ undoing them

    /** Every few ticks: undoes what is due (see the class description). */
    public void tick(MinecraftServer server) {
        if (!loaded) {
            loaded = true;
            load(server);
        }
        if (changes.isEmpty() || server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        long now = server.getTickCount();
        boolean dirty = false;
        Iterator<Change> it = changes.values().iterator();
        while (it.hasNext()) {
            Change change = it.next();
            ServerLevel level = server.getLevel(change.dimension);
            if (level == null) {
                it.remove();
                dirty = true;
                continue;
            }
            if (!LocationFinder.isLoaded(level, change.pos.getX(), change.pos.getZ())) {
                // Only a change read back after a restart can be in an unloaded chunk (unloading undoes them).
                continue;
            }
            boolean seen = StalkerSight.anyoneSeesBlock(level, change.pos, 48.0)
                    || (change.kind == Kind.DOOR && StalkerSight.anyoneSeesBlock(level, change.pos.above(), 48.0));
            change.unseenTicks = seen ? 0 : change.unseenTicks + CHECK_INTERVAL;
            if (due(server, level, change, now)) {
                undo(level, change);
                it.remove();
                dirty = true;
            }
        }
        if (dirty) {
            save(server);
        }
    }

    private static boolean due(MinecraftServer server, ServerLevel level, Change change, long now) {
        long age = now - change.createdAt;
        if (change.kind == Kind.PANE) {
            if (change.fromDisk || age >= change.maxTicks || ownerAway(server, level, change)) {
                return true;
            }
            // After a short while, as soon as nobody has had it in view for a second.
            return age >= change.minTicks && change.unseenTicks >= 20;
        }
        if (occupied(level, change)) {
            // Never close a door on someone.
            return false;
        }
        if (change.fromDisk || age >= change.maxTicks) {
            return true;
        }
        if (ownerAway(server, level, change)) {
            return change.unseenTicks >= 20;
        }
        return age >= change.minTicks && change.unseenTicks >= 40;
    }

    private static boolean ownerAway(MinecraftServer server, ServerLevel level, Change change) {
        if (change.owner == null) {
            return false;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(change.owner);
        return owner == null || !owner.isAlive() || owner.level() != level
                || owner.distanceToSqr(Vec3.atCenterOf(change.pos)) > OWNER_RANGE * OWNER_RANGE;
    }

    private static boolean occupied(ServerLevel level, Change change) {
        AABB box = new AABB(change.pos);
        if (change.original.getBlock() instanceof DoorBlock) {
            box = box.expandTowards(0.0, 1.0, 0.0);
        }
        return !level.getEntitiesOfClass(LivingEntity.class, box.inflate(0.2), entity -> !(entity instanceof StalkerEntity)).isEmpty();
    }

    /** Puts one change back right now through the level (its chunk must be loaded). */
    private static void undo(ServerLevel level, Change change) {
        BlockState current = level.getBlockState(change.pos);
        if (change.kind == Kind.PANE) {
            if (current.isAir()) {
                level.setBlock(change.pos, change.original, PANE_FLAGS);
            }
            return;
        }
        if (current.equals(change.changed) && current.hasProperty(BlockStateProperties.OPEN)) {
            if (level.setBlock(change.pos, current.setValue(BlockStateProperties.OPEN, Boolean.FALSE), DOOR_FLAGS)) {
                level.gameEvent(null, GameEvent.BLOCK_CLOSE, change.pos);
            }
        }
    }

    /** The same, written straight into a chunk that is being unloaded (the level no longer has it). */
    private static void undoInChunk(LevelChunk chunk, Change change) {
        BlockState current = chunk.getBlockState(change.pos);
        if (change.kind == Kind.PANE) {
            if (current.isAir()) {
                chunk.setBlockState(change.pos, change.original, false);
                chunk.setUnsaved(true);
            }
            return;
        }
        if (!current.equals(change.changed) || !current.hasProperty(BlockStateProperties.OPEN)) {
            return;
        }
        chunk.setBlockState(change.pos, current.setValue(BlockStateProperties.OPEN, Boolean.FALSE), false);
        if (current.getBlock() instanceof DoorBlock) {
            // No shape updates run here, so the upper half is closed by hand.
            BlockPos upper = change.pos.above();
            BlockState top = chunk.getBlockState(upper);
            if (top.is(current.getBlock()) && top.hasProperty(BlockStateProperties.OPEN) && top.getValue(BlockStateProperties.OPEN)) {
                chunk.setBlockState(upper, top.setValue(BlockStateProperties.OPEN, Boolean.FALSE), false);
            }
        }
        chunk.setUnsaved(true);
    }

    /** A chunk is about to be unloaded (and saved): everything pending in it is put back first. */
    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (changes.isEmpty()) {
            return;
        }
        // Through ChunkAccess: that is where getPos() (a ChunkPos) is declared.
        ChunkAccess access = chunk;
        ChunkPos chunkPos = access.getPos();
        boolean dirty = false;
        Iterator<Change> it = changes.values().iterator();
        while (it.hasNext()) {
            Change change = it.next();
            if (change.dimension.equals(level.dimension()) && (change.pos.getX() >> 4) == chunkPos.x
                    && (change.pos.getZ() >> 4) == chunkPos.z) {
                undoInChunk(chunk, change);
                it.remove();
                dirty = true;
            }
        }
        if (dirty) {
            save(level.getServer());
        }
    }

    /** A player left: everything that was done around them is put back now. */
    public void undoOwnedBy(MinecraftServer server, UUID owner) {
        undoMatching(server, change -> owner.equals(change.owner));
    }

    /**
     * Server stopping or {@code /null reset}: everything is put back now. Changes whose chunk is not
     * loaded stay in the world's data and are undone when it is loaded again.
     */
    public void undoAll(MinecraftServer server) {
        undoMatching(server, change -> true);
    }

    private void undoMatching(MinecraftServer server, Predicate<Change> filter) {
        if (changes.isEmpty()) {
            return;
        }
        boolean dirty = false;
        Iterator<Change> it = changes.values().iterator();
        while (it.hasNext()) {
            Change change = it.next();
            if (!filter.test(change)) {
                continue;
            }
            ServerLevel level = server.getLevel(change.dimension);
            if (level == null) {
                it.remove();
                dirty = true;
                continue;
            }
            if (!LocationFinder.isLoaded(level, change.pos.getX(), change.pos.getZ())) {
                change.fromDisk = true;
                continue;
            }
            undo(level, change);
            it.remove();
            dirty = true;
        }
        if (dirty) {
            save(server);
        }
    }

    /** Forgets everything in memory (the data on disk stays): the server is gone. */
    public void clear() {
        changes.clear();
        loaded = false;
    }

    /** Puts back the change at this position now, if there is one (tests). */
    public boolean undoNow(ServerLevel level, BlockPos pos) {
        Change change = changes.remove(new Key(level.dimension(), pos.asLong()));
        if (change == null) {
            return false;
        }
        undo(level, change);
        save(level.getServer());
        return true;
    }

    /** Puts back everything in one chunk exactly as the unload hook does (tests). */
    public void undoChunkNow(ServerLevel level, BlockPos anyPosInChunk) {
        onChunkUnload(level, level.getChunkAt(anyPosInChunk));
    }

    public boolean isPending(ServerLevel level, BlockPos pos) {
        return changes.containsKey(new Key(level.dimension(), pos.asLong()));
    }

    public int pending() {
        return changes.size();
    }

    // ------------------------------------------------------------------ persistence

    private void save(MinecraftServer server) {
        CorruptionData data = CorruptionManager.data(server);
        ListTag list = new ListTag();
        for (Change change : changes.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("dim", change.dimension.location().toString());
            entry.putLong("pos", change.pos.asLong());
            entry.putByte("kind", (byte) change.kind.ordinal());
            entry.put("original", NbtUtils.writeBlockState(change.original));
            entry.put("changed", NbtUtils.writeBlockState(change.changed));
            list.add(entry);
        }
        data.systemTag(SYSTEM_KEY).put(LIST_KEY, list);
        data.setDirty();
    }

    private void load(MinecraftServer server) {
        ListTag list = CorruptionManager.data(server).systemTag(SYSTEM_KEY).getList(LIST_KEY, Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return;
        }
        HolderGetter<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        List<Change> read = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("dim"));
            int kind = entry.getByte("kind");
            if (id == null || kind < 0 || kind >= Kind.values().length) {
                continue;
            }
            BlockState original = NbtUtils.readBlockState(blocks, entry.getCompound("original"));
            BlockState changed = NbtUtils.readBlockState(blocks, entry.getCompound("changed"));
            if (original.isAir()) {
                // A block that no longer exists (its mod is gone) reads back as air: nothing to put back.
                continue;
            }
            Change change = new Change(Kind.values()[kind], ResourceKey.create(Registries.DIMENSION, id),
                    BlockPos.of(entry.getLong("pos")), original, changed, null, server.getTickCount(), 0, 0);
            change.fromDisk = true;
            read.add(change);
        }
        for (Change change : read) {
            changes.putIfAbsent(new Key(change.dimension, change.pos.asLong()), change);
        }
    }
}
