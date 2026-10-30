package dev.kristian.nullmod.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An ordered list of block changes that {@link SafeBlockPlacer} carries out a slice at a time.
 * Changes run in the order they were added, so supports must be added before the things they
 * hold up (walls before torches, the lower half of a door before the upper half).
 * <p>
 * A job never touches the world by itself; it only describes what should happen. Every change
 * is validated again right before it is made.
 */
public final class PlacementJob {
    /** Hard cap so a bug in a generator can never queue a world-sized edit. */
    public static final int MAX_CHANGES = 16_000;

    /** Something to do to a block right after it was placed (sign text...). */
    @FunctionalInterface
    public interface PostPlace {
        void apply(ServerLevel level, BlockPos pos);
    }

    /**
     * One block change. Changes that share a non-zero {@code group} and follow each other in the
     * job are placed together or not at all (both halves of a door).
     */
    public record Change(BlockPos pos, BlockState state, SafeBlockPlacer.Mode mode, SafeBlockPlacer.Support support,
                         int group, @Nullable PostPlace post) {
    }

    /** What a job did, handed to {@link #onComplete(Consumer)} once it is finished or aborted. */
    public record Result(String name, int placed, int skipped, boolean aborted, @Nullable BlockPos firstPlaced) {
        public boolean anyPlaced() {
            return placed > 0;
        }
    }

    private final MinecraftServer server;
    private final ResourceKey<Level> dimension;
    private final String name;
    private final List<Change> changes = new ArrayList<>();
    private boolean atomic;
    @Nullable
    private Consumer<Result> onComplete;
    private int groups;
    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;

    // ---- execution state, owned by SafeBlockPlacer ----
    int cursor;
    int placed;
    int skipped;
    boolean aborted;
    boolean finished;
    @Nullable
    BlockPos firstPlaced;
    long queuedAt = -1L;
    long guardedAt = Long.MIN_VALUE;
    long entitiesAt = Long.MIN_VALUE;
    List<LivingEntity> living = List.of();
    /** Chunk (ChunkPos long) to "protected" for non-atomic jobs, decided when the job was accepted. */
    final Map<Long, Boolean> protectedChunks = new HashMap<>();
    /** Chunks whose player-placed block counter is re-checked every tick while the job runs. */
    long[] guardChunks = new long[0];
    int guardRadius;

    public PlacementJob(ServerLevel level, String name) {
        this.server = level.getServer();
        this.dimension = level.dimension();
        this.name = name;
    }

    public MinecraftServer server() {
        return server;
    }

    public String name() {
        return name;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public int size() {
        return changes.size();
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * An atomic job is accepted only if its whole area is unprotected, and stops completely if a
     * player starts building nearby while it runs. Use it for structures, which must never end up
     * half built against somebody's base. Non-atomic jobs (scattered terrain corruption) are
     * judged chunk by chunk.
     */
    public PlacementJob atomic() {
        this.atomic = true;
        return this;
    }

    public boolean isAtomic() {
        return atomic;
    }

    /** Called once on the server thread when the job has finished or was aborted. */
    public PlacementJob onComplete(@Nullable Consumer<Result> callback) {
        this.onComplete = callback;
        return this;
    }

    @Nullable
    Consumer<Result> completionCallback() {
        return onComplete;
    }

    /** Places a block into air or a replaceable plant. */
    public PlacementJob place(BlockPos pos, BlockState state) {
        return add(pos, state, SafeBlockPlacer.Mode.PLACE_INTO_AIR, SafeBlockPlacer.Support.NONE, 0, null);
    }

    /** Replaces natural terrain. */
    public PlacementJob replace(BlockPos pos, BlockState state) {
        return add(pos, state, SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.NONE, 0, null);
    }

    public PlacementJob add(BlockPos pos, BlockState state, SafeBlockPlacer.Mode mode, SafeBlockPlacer.Support support) {
        return add(pos, state, mode, support, 0, null);
    }

    public PlacementJob add(BlockPos pos, BlockState state, SafeBlockPlacer.Mode mode, SafeBlockPlacer.Support support,
                            int group, @Nullable PostPlace post) {
        if (changes.size() >= MAX_CHANGES) {
            return this;
        }
        BlockPos immutable = pos.immutable();
        changes.add(new Change(immutable, state, mode, support, group, post));
        minX = Math.min(minX, immutable.getX());
        minY = Math.min(minY, immutable.getY());
        minZ = Math.min(minZ, immutable.getZ());
        maxX = Math.max(maxX, immutable.getX());
        maxY = Math.max(maxY, immutable.getY());
        maxZ = Math.max(maxZ, immutable.getZ());
        return this;
    }

    /** A fresh group id for changes that must be placed together. */
    public int newGroup() {
        return ++groups;
    }

    public List<Change> changes() {
        return Collections.unmodifiableList(changes);
    }

    Change change(int index) {
        return changes.get(index);
    }

    /** Lowest corner of everything this job touches (only meaningful when not empty). */
    public BlockPos min() {
        return new BlockPos(minX, minY, minZ);
    }

    /** Highest corner of everything this job touches (only meaningful when not empty). */
    public BlockPos max() {
        return new BlockPos(maxX, maxY, maxZ);
    }

    int minX() {
        return minX;
    }

    int minY() {
        return minY;
    }

    int minZ() {
        return minZ;
    }

    int maxX() {
        return maxX;
    }

    int maxY() {
        return maxY;
    }

    int maxZ() {
        return maxZ;
    }

    Result result() {
        return new Result(name, placed, skipped, aborted, firstPlaced);
    }

    @Override
    public String toString() {
        return "PlacementJob[" + name + ", " + changes.size() + " changes" + (atomic ? ", atomic" : "") + "]";
    }
}
