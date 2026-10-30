package dev.kristian.nullmod.world;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/**
 * The only way NULL changes blocks outside its own dimension. Generators describe what they want
 * as a {@link PlacementJob}; this queue carries the changes out a slice at a time (at most
 * {@link #CHANGES_PER_TICK} block changes per server tick over all jobs) and checks every change
 * again right before making it:
 * <ul>
 * <li>never in the NULL dimension, never in an unloaded chunk (nothing is ever loaded for it);</li>
 * <li>never near anything a player built ({@link BuildProtection}); the expensive scan runs once
 * per job (atomic jobs) or once per chunk (other jobs) when the job is accepted, and only the
 * cheap per-chunk "player placed blocks here" counter is re-read while the job runs;</li>
 * <li>{@link Mode#PLACE_INTO_AIR} only into air or replaceable plants, and only while structures
 * are allowed; {@link Mode#REPLACE_NATURAL} only over natural terrain, and only while natural
 * replacement is allowed;</li>
 * <li>never a solid block inside a living entity, never opening a path for water or lava;</li>
 * <li>blocks are set with {@link #FLAGS}: clients are told, neighbours are not, so nothing falls,
 * pops off or cascades. Floating terrain must therefore use the non-falling retro sand and
 * gravel.</li>
 * </ul>
 */
public final class SafeBlockPlacer implements CorruptionSystem {
    public static final SafeBlockPlacer INSTANCE = new SafeBlockPlacer();

    /** Most block changes made per server tick, over all jobs. */
    public static final int CHANGES_PER_TICK = 200;
    /** Most changes looked at per tick (made or skipped), so a job full of rejected changes cannot stall a tick. */
    public static final int CHECKS_PER_TICK = 1_200;
    /** Update clients, but do not update neighbours or their shapes: no physics cascades. */
    public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final int SLICE = 64;
    private static final int MAX_QUEUED_JOBS = 48;
    private static final long MAX_JOB_AGE_TICKS = 20L * 60L * 3L;
    private static final Direction[] FLUID_SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    /** What a change may replace. */
    public enum Mode {
        /** Only into air or replaceable plants ({@link BuildProtection#canPlaceInto}); needs structures to be allowed. */
        PLACE_INTO_AIR,
        /** Only over natural terrain ({@link BuildProtection#isNatural}); needs natural replacement to be allowed. */
        REPLACE_NATURAL,
        /** Whichever of the two applies to the block that is there when the change runs (buried structures). */
        NATURAL_OR_AIR
    }

    /** What has to hold the block up when it is placed. */
    public enum Support {
        NONE,
        /** The block's own survival rule (torches, ladders, signs, plants, snow). */
        SURVIVE,
        /** A full face below (sand on the ground, doors). */
        SOLID_BELOW
    }

    private final ArrayDeque<PlacementJob> queue = new ArrayDeque<>();

    private SafeBlockPlacer() {
    }

    @Override
    public String name() {
        return "world_placer";
    }

    // ------------------------------------------------------------------ public API

    /**
     * Accepts a job if it may run at all: right dimension, world modification on, queue not full,
     * and its area not protected. Returns false (and never calls the job's callback) if refused,
     * so generators can try another location.
     */
    public boolean submit(PlacementJob job) {
        MinecraftServer server = job.server();
        ServerLevel level = server.getLevel(job.dimension());
        if (level == null || queue.size() >= MAX_QUEUED_JOBS || !accept(level, job)) {
            return false;
        }
        job.queuedAt = server.getTickCount();
        queue.addLast(job);
        return true;
    }

    /**
     * Runs a whole job right now, with the same checks but without the per-tick budget. Meant for
     * GameTests and tiny edits; returns what happened (aborted if the job was refused).
     */
    public PlacementJob.Result runNow(ServerLevel level, PlacementJob job) {
        if (!level.dimension().equals(job.dimension()) || !accept(level, job)) {
            job.aborted = true;
            return job.result();
        }
        boolean allowNatural = NullConfig.mayReplaceNatural();
        boolean allowStructures = NullConfig.mayBuildStructures();
        long tick = level.getServer().getTickCount();
        guard(level, job, tick);
        while (!job.aborted && job.cursor < job.size()) {
            runOne(level, job, allowNatural, allowStructures, tick);
        }
        job.finished = true;
        complete(job);
        return job.result();
    }

    /** Number of jobs waiting or running. */
    public int queued() {
        return queue.size();
    }

    /** Stops every job (their callbacks run with {@code aborted = true}). */
    public void abortAll(String reason) {
        if (queue.isEmpty()) {
            return;
        }
        NullMod.LOGGER.debug("NULL: aborting {} world jobs ({})", queue.size(), reason);
        List<PlacementJob> jobs = new ArrayList<>(queue);
        queue.clear();
        for (PlacementJob job : jobs) {
            job.aborted = true;
            complete(job);
        }
    }

    /** Stops the jobs of one dimension (its level is unloading). */
    public void abortDimension(Level level) {
        Iterator<PlacementJob> it = queue.iterator();
        List<PlacementJob> stopped = new ArrayList<>();
        while (it.hasNext()) {
            PlacementJob job = it.next();
            if (job.dimension().equals(level.dimension())) {
                it.remove();
                job.aborted = true;
                stopped.add(job);
            }
        }
        for (PlacementJob job : stopped) {
            complete(job);
        }
    }

    // ------------------------------------------------------------------ CorruptionSystem

    @Override
    public void tick(MinecraftServer server, CorruptionContext context) {
        if (queue.isEmpty()) {
            return;
        }
        if (!NullConfig.get(NullConfig.WORLD_MODIFICATION)) {
            abortAll("world modification switched off");
            return;
        }
        boolean allowNatural = NullConfig.mayReplaceNatural();
        boolean allowStructures = NullConfig.mayBuildStructures();
        long tick = server.getTickCount();
        int changes = CHANGES_PER_TICK;
        int checks = CHECKS_PER_TICK;

        // Round robin: every job gets a slice in turn, so one big structure never starves a
        // small patch, until the tick's budget is used up.
        boolean progress = true;
        while (progress && changes > 0 && checks > 0) {
            progress = false;
            for (PlacementJob job : queue) {
                if (changes <= 0 || checks <= 0) {
                    break;
                }
                if (job.finished || job.aborted) {
                    continue;
                }
                ServerLevel level = server.getLevel(job.dimension());
                if (level == null || NullDimensions.isNull(level) || tick - job.queuedAt > MAX_JOB_AGE_TICKS) {
                    job.aborted = true;
                    continue;
                }
                guard(level, job, tick);
                if (job.aborted) {
                    continue;
                }
                int sliceChanges = Math.min(SLICE, changes);
                int made = 0;
                int looked = 0;
                while (made < sliceChanges && looked < checks && job.cursor < job.size() && !job.aborted) {
                    int before = job.placed;
                    looked += runOne(level, job, allowNatural, allowStructures, tick);
                    made += job.placed - before;
                }
                changes -= made;
                checks -= looked;
                if (job.cursor >= job.size()) {
                    job.finished = true;
                }
                if (looked > 0) {
                    progress = true;
                }
            }
        }

        Iterator<PlacementJob> it = queue.iterator();
        List<PlacementJob> done = new ArrayList<>();
        while (it.hasNext()) {
            PlacementJob job = it.next();
            if (job.finished || job.aborted) {
                it.remove();
                done.add(job);
            }
        }
        for (PlacementJob job : done) {
            complete(job);
        }
    }

    @Override
    public void onReset(MinecraftServer server) {
        abortAll("NULL reset");
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        // No callbacks: the server is going away and nothing should be recorded any more.
        queue.clear();
    }

    // ------------------------------------------------------------------ internals

    /** Decides whether a job may run, and caches its protection result. */
    private static boolean accept(ServerLevel level, PlacementJob job) {
        if (job.isEmpty() || NullDimensions.isNull(level) || !NullConfig.get(NullConfig.WORLD_MODIFICATION)) {
            return false;
        }
        int radius = NullConfig.get(NullConfig.BUILD_PROTECTION_RADIUS);
        job.guardRadius = radius;
        job.protectedChunks.clear();
        if (job.isAtomic()) {
            if (BuildProtection.isAreaProtected(level, job.min(), job.max())) {
                return false;
            }
        } else {
            Map<Long, int[]> bounds = new HashMap<>();
            for (PlacementJob.Change change : job.changes()) {
                BlockPos pos = change.pos();
                long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
                int[] b = bounds.computeIfAbsent(key, k -> new int[]{
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE});
                b[0] = Math.min(b[0], pos.getX());
                b[1] = Math.min(b[1], pos.getY());
                b[2] = Math.min(b[2], pos.getZ());
                b[3] = Math.max(b[3], pos.getX());
                b[4] = Math.max(b[4], pos.getY());
                b[5] = Math.max(b[5], pos.getZ());
            }
            boolean anyOpen = false;
            for (Map.Entry<Long, int[]> entry : bounds.entrySet()) {
                int[] b = entry.getValue();
                boolean prot = BuildProtection.isAreaProtected(level, new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]));
                job.protectedChunks.put(entry.getKey(), prot);
                anyOpen |= !prot;
            }
            if (!anyOpen) {
                return false;
            }
        }
        int cx0 = (job.minX() - radius) >> 4;
        int cx1 = (job.maxX() + radius) >> 4;
        int cz0 = (job.minZ() - radius) >> 4;
        int cz1 = (job.maxZ() + radius) >> 4;
        long[] guard = new long[(cx1 - cx0 + 1) * (cz1 - cz0 + 1)];
        int n = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                guard[n++] = ChunkPos.asLong(cx, cz);
            }
        }
        job.guardChunks = guard;
        return true;
    }

    /**
     * Once per tick per job: if a player has placed blocks near the job since it was accepted,
     * an atomic job stops and a scattered job stops touching the chunks around that spot.
     */
    private static void guard(ServerLevel level, PlacementJob job, long tick) {
        if (job.guardedAt == tick) {
            return;
        }
        job.guardedAt = tick;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int reach = (job.guardRadius >> 4) + 1;
        for (long key : job.guardChunks) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (!LocationFinder.chunkReady(level, cx, cz)) {
                continue;
            }
            cursor.set(cx << 4, 0, cz << 4);
            if (BuildProtection.playerBlocks(level, cursor) <= 0) {
                continue;
            }
            if (job.isAtomic()) {
                job.aborted = true;
                return;
            }
            for (Map.Entry<Long, Boolean> entry : job.protectedChunks.entrySet()) {
                int jx = ChunkPos.getX(entry.getKey());
                int jz = ChunkPos.getZ(entry.getKey());
                if (Math.abs(jx - cx) <= reach && Math.abs(jz - cz) <= reach) {
                    entry.setValue(Boolean.TRUE);
                }
            }
        }
    }

    /**
     * Handles the change (or group of changes) at the job's cursor and advances it.
     * Returns how many changes were looked at.
     */
    private static int runOne(ServerLevel level, PlacementJob job, boolean allowNatural, boolean allowStructures, long tick) {
        PlacementJob.Change first = job.change(job.cursor);
        if (first.group() == 0) {
            job.cursor++;
            if (allowed(level, job, first, allowNatural, allowStructures, tick)) {
                apply(level, job, first);
            } else {
                job.skipped++;
            }
            return 1;
        }
        int end = job.cursor;
        while (end < job.size() && job.change(end).group() == first.group()) {
            end++;
        }
        boolean ok = true;
        for (int i = job.cursor; i < end && ok; i++) {
            ok = allowed(level, job, job.change(i), allowNatural, allowStructures, tick);
        }
        int count = end - job.cursor;
        for (int i = job.cursor; i < end; i++) {
            if (ok) {
                apply(level, job, job.change(i));
            } else {
                job.skipped++;
            }
        }
        job.cursor = end;
        return count;
    }

    private static boolean allowed(ServerLevel level, PlacementJob job, PlacementJob.Change change,
                                   boolean allowNatural, boolean allowStructures, long tick) {
        BlockPos pos = change.pos();
        if (level.isOutsideBuildHeight(pos)) {
            return false;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (!LocationFinder.chunkReady(level, cx, cz)) {
            return false;
        }
        if (!job.isAtomic()) {
            Boolean prot = job.protectedChunks.get(ChunkPos.asLong(cx, cz));
            if (prot == null || prot) {
                return false;
            }
        }
        BlockState current = level.getBlockState(pos);
        BlockState target = change.state();
        if (current == target) {
            return false;
        }
        boolean modeOk = switch (change.mode()) {
            case PLACE_INTO_AIR -> allowStructures && BuildProtection.canPlaceInto(current);
            case REPLACE_NATURAL -> allowNatural && BuildProtection.isNatural(current);
            case NATURAL_OR_AIR -> (allowNatural && BuildProtection.isNatural(current))
                    || (allowStructures && BuildProtection.canPlaceInto(current));
        };
        if (!modeOk) {
            return false;
        }
        boolean neighboursLoaded = neighboursLoaded(level, pos);
        if (opensFluid(level, pos, current, target, neighboursLoaded)) {
            return false;
        }
        if (change.support() != Support.NONE && (!neighboursLoaded || !supported(level, pos, target, change.support()))) {
            return false;
        }
        return !insideEntity(level, job, pos, current, target, tick);
    }

    /** True if all horizontally adjacent blocks are in loaded chunks (so reading them never loads one). */
    private static boolean neighboursLoaded(ServerLevel level, BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        return LocationFinder.isLoaded(level, x - 1, z) && LocationFinder.isLoaded(level, x + 1, z)
                && LocationFinder.isLoaded(level, x, z - 1) && LocationFinder.isLoaded(level, x, z + 1);
    }

    /**
     * Swapping a solid block for air (or something water can flow through) next to a fluid would
     * let it pour in. Refused; an unknown neighbour counts as wet.
     */
    private static boolean opensFluid(ServerLevel level, BlockPos pos, BlockState current, BlockState target, boolean neighboursLoaded) {
        if (current.isAir() || !current.getFluidState().isEmpty()) {
            return false;
        }
        if (!target.isAir() && target.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }
        if (!neighboursLoaded) {
            return true;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction side : FLUID_SIDES) {
            cursor.setWithOffset(pos, side);
            if (!level.getBlockState(cursor).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean supported(ServerLevel level, BlockPos pos, BlockState target, Support support) {
        return switch (support) {
            case NONE -> true;
            case SURVIVE -> target.canSurvive(level, pos);
            case SOLID_BELOW -> {
                BlockPos below = pos.below();
                yield level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
            }
        };
    }

    /** Never put something solid (or a cobweb) where a living entity is standing. */
    private static boolean insideEntity(ServerLevel level, PlacementJob job, BlockPos pos, BlockState current, BlockState target, long tick) {
        if (target.isAir()) {
            return false;
        }
        boolean solid = target.is(Blocks.COBWEB) || !target.getCollisionShape(level, pos).isEmpty();
        if (!solid) {
            return false;
        }
        if (!current.isAir() && current.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }
        if (job.entitiesAt != tick) {
            job.entitiesAt = tick;
            AABB box = new AABB(job.minX(), job.minY(), job.minZ(), job.maxX() + 1.0, job.maxY() + 1.0, job.maxZ() + 1.0).inflate(1.0);
            job.living = level.getEntitiesOfClass(LivingEntity.class, box);
        }
        for (LivingEntity entity : job.living) {
            if (entity.isAlive() && entity.getBoundingBox().intersects(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0)) {
                return true;
            }
        }
        return false;
    }

    private static void apply(ServerLevel level, PlacementJob job, PlacementJob.Change change) {
        BlockState before = level.getBlockState(change.pos());
        if (!level.setBlock(change.pos(), change.state(), FLAGS)) {
            job.skipped++;
            return;
        }
        clearOtherHalf(level, change.pos(), before);
        job.placed++;
        if (job.firstPlaced == null) {
            job.firstPlaced = change.pos();
        }
        PlacementJob.PostPlace post = change.post();
        if (post != null) {
            try {
                post.apply(level, change.pos());
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL: post-placement step of world job '{}' failed", job.name(), e);
            }
        }
    }

    /**
     * Neighbours are never updated, so replacing one half of a tall plant would leave the other
     * half hanging in the air. Take it away as well (it is the same plant, never anything built).
     */
    private static void clearOtherHalf(ServerLevel level, BlockPos pos, BlockState before) {
        if (!(before.getBlock() instanceof DoublePlantBlock) || !before.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return;
        }
        boolean lower = before.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
        BlockPos other = lower ? pos.above() : pos.below();
        BlockState state = level.getBlockState(other);
        if (state.is(before.getBlock())) {
            level.setBlock(other, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    private static void complete(PlacementJob job) {
        job.living = List.of();
        Consumer<PlacementJob.Result> callback = job.completionCallback();
        NullMod.LOGGER.debug("NULL world job '{}': {} placed, {} skipped{}", job.name(), job.placed, job.skipped,
                job.aborted ? " (aborted)" : "");
        if (callback == null) {
            return;
        }
        try {
            callback.accept(job.result());
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("NULL: completion callback of world job '{}' failed", job.name(), e);
        }
    }
}
