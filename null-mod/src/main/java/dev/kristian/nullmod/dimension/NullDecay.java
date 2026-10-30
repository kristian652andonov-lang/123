package dev.kristian.nullmod.dimension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Regions of the NULL dimension that are missing (removed) or wrong (rebuilt from another seed),
 * and the budgeted block edits that take them apart and put them back.
 * <p>
 * Every edit only touches blocks that are exactly what the generator placed (or air where the
 * generator placed air), so anything a player built inside NULL is never removed or overwritten,
 * and restoring is exact: the region comes back from {@link NullWorldGeneration#sample}. Nothing
 * changes within {@link #PLAYER_MARGIN} blocks of a player, positions protected by
 * {@link NullWorldGeneration#isProtected} (the spawn platform, main walkways and their railings)
 * are never touched, and nothing is ever placed into a player. Damaged regions are persisted so
 * even a crash cannot leave a hole behind; they are all restored when the server stops.
 */
final class NullDecay {
    /** Most regions that may be missing or shifted at the same time. */
    static final int MAX_DAMAGED = 12;
    /** Closest a player may be to a region that is being taken apart or rebuilt. */
    static final int PLAYER_MARGIN = 6;
    /** Closest a player may be to a region that is being put back. */
    static final int RESTORE_MARGIN = 3;
    private static final int VISITS_PER_TICK = 2048;
    private static final int EDITS_PER_TICK = 384;
    /** A removal that cannot continue for this long (player in the way, chunk unloaded) is undone instead. */
    private static final int ABORT_AFTER = 200;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    enum Op { TAKE, RESTORE }

    /** A box of the dimension that is missing ({@code variant == 0}) or holds another seed's geometry. */
    static final class Region {
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        final long variant;
        long restoreAt;
        boolean busy;

        Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long variant, long restoreAt) {
            this.minX = minX;
            this.minY = Math.max(NullWorldGeneration.MIN_Y, minY);
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = Math.min(NullWorldGeneration.MAX_Y, maxY);
            this.maxZ = maxZ;
            this.variant = variant;
            this.restoreAt = restoreAt;
        }

        boolean shifted() {
            return variant != 0L;
        }

        int sizeX() {
            return maxX - minX + 1;
        }

        int sizeY() {
            return maxY - minY + 1;
        }

        int sizeZ() {
            return maxZ - minZ + 1;
        }

        BlockPos center() {
            return new BlockPos((minX + maxX) >> 1, (minY + maxY) >> 1, (minZ + maxZ) >> 1);
        }

        boolean overlaps(Region other, int margin) {
            return maxX + margin >= other.minX && minX - margin <= other.maxX
                    && maxY + margin >= other.minY && minY - margin <= other.maxY
                    && maxZ + margin >= other.minZ && minZ - margin <= other.maxZ;
        }
    }

    private static final class Job {
        final Region region;
        final Op op;
        int cursor;
        int waited;

        Job(Region region, Op op) {
            this.region = region;
            this.op = op;
        }
    }

    /** Remaining work of one tick, shared by all jobs. */
    private static final class Budget {
        int visits;
        int edits;

        Budget(int visits, int edits) {
            this.visits = visits;
            this.edits = edits;
        }
    }

    private final List<Region> damaged = new ArrayList<>();
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final NullWorldGeneration.Column original = new NullWorldGeneration.Column();
    private final NullWorldGeneration.Column changed = new NullWorldGeneration.Column();
    private boolean dirty;

    int damagedCount() {
        return damaged.size();
    }

    /** True (once) after the set of damaged regions changed, so it can be saved. */
    boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    // ------------------------------------------------------------------ starting work

    /** Starts taking a region apart (or rebuilding it from its variant seed); it comes back at {@code restoreAt}. */
    void start(Region region) {
        damaged.add(region);
        region.busy = true;
        jobs.add(new Job(region, Op.TAKE));
        dirty = true;
    }

    /** True if the box touches a region that is already missing or shifted. */
    boolean overlapsDamaged(Region region) {
        for (Region other : damaged) {
            if (region.overlaps(other, 0)) {
                return true;
            }
        }
        return false;
    }

    /** Every damaged region starts coming back now (as soon as players and chunks allow). */
    void restoreAllSoon(long now) {
        for (Region region : damaged) {
            region.restoreAt = Math.min(region.restoreAt, now);
        }
    }

    // ------------------------------------------------------------------ ticking

    void tick(ServerLevel level, long seed, long now) {
        for (Region region : damaged) {
            if (!region.busy && now >= region.restoreAt) {
                region.busy = true;
                jobs.add(new Job(region, Op.RESTORE));
            }
        }
        if (jobs.isEmpty()) {
            return;
        }
        Budget budget = new Budget(VISITS_PER_TICK, EDITS_PER_TICK);
        int count = jobs.size();
        for (int i = 0; i < count && budget.visits > 0 && budget.edits > 0; i++) {
            Job job = jobs.poll();
            if (job == null) {
                break;
            }
            if (!runnable(level, job)) {
                if (job.op == Op.TAKE && ++job.waited > ABORT_AFTER) {
                    // It could not finish: undo what was done instead of leaving half a hole.
                    job.region.restoreAt = now;
                    job.region.busy = true;
                    jobs.add(new Job(job.region, Op.RESTORE));
                } else {
                    jobs.add(job);
                }
                continue;
            }
            job.waited = 0;
            if (step(level, seed, job, budget)) {
                finish(job);
            } else {
                jobs.add(job);
            }
        }
    }

    /**
     * Puts every damaged region back immediately, loading chunks if needed. Used when the server
     * stops and on /null reset.
     */
    void restoreAllNow(ServerLevel level, long seed) {
        jobs.clear();
        for (Region region : new ArrayList<>(damaged)) {
            for (int cx = region.minX >> 4; cx <= region.maxX >> 4; cx++) {
                for (int cz = region.minZ >> 4; cz <= region.maxZ >> 4; cz++) {
                    level.getChunk(cx, cz);
                }
            }
            Job job = new Job(region, Op.RESTORE);
            step(level, seed, job, new Budget(Integer.MAX_VALUE, Integer.MAX_VALUE));
            finish(job);
        }
        damaged.clear();
        dirty = true;
    }

    /** Forgets everything without touching the world (the level is gone). */
    void clear() {
        jobs.clear();
        damaged.clear();
        dirty = false;
    }

    private void finish(Job job) {
        if (job.op == Op.RESTORE) {
            damaged.remove(job.region);
            dirty = true;
        }
        job.region.busy = false;
    }

    private static boolean runnable(ServerLevel level, Job job) {
        Region r = job.region;
        for (int cx = r.minX >> 4; cx <= r.maxX >> 4; cx++) {
            for (int cz = r.minZ >> 4; cz <= r.maxZ >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        int margin = job.op == Op.RESTORE ? RESTORE_MARGIN : PLAYER_MARGIN;
        return !playerNear(level, r.minX, r.minY, r.minZ, r.maxX, r.maxY, r.maxZ, margin);
    }

    /**
     * Visits positions of a job until it is done or the budget runs out.
     *
     * @return true when the whole region has been visited
     */
    private boolean step(ServerLevel level, long seed, Job job, Budget budget) {
        Region r = job.region;
        int sizeY = r.sizeY();
        int sizeZ = r.sizeZ();
        int total = r.sizeX() * sizeY * sizeZ;
        int lastColumn = -1;
        while (job.cursor < total && budget.visits > 0 && budget.edits > 0) {
            int index = job.cursor++;
            int column = index / sizeY;
            int x = r.minX + column / sizeZ;
            int z = r.minZ + column % sizeZ;
            int y = r.minY + index % sizeY;
            if (column != lastColumn) {
                NullWorldGeneration.column(seed, x, z, original);
                if (r.shifted()) {
                    NullWorldGeneration.column(r.variant, x, z, changed);
                }
                lastColumn = column;
            }
            budget.visits--;
            if (NullWorldGeneration.isProtected(x, y, z)) {
                continue;
            }
            int base = NullWorldGeneration.idAt(original, y);
            int wrong = r.shifted() ? NullWorldGeneration.idAt(changed, y) : NullWorldGeneration.AIR;
            if (base == wrong) {
                continue;
            }
            BlockState from = NullWorldGeneration.state(job.op == Op.TAKE ? base : wrong);
            cursor.set(x, y, z);
            if (level.getBlockState(cursor) != from) {
                // A player's block, or already changed: leave it alone.
                continue;
            }
            level.setBlock(cursor, NullWorldGeneration.state(job.op == Op.TAKE ? wrong : base), FLAGS);
            budget.edits--;
        }
        return job.cursor >= total;
    }

    // ------------------------------------------------------------------ choosing regions

    /**
     * Looks for a region near a player that is worth taking apart (or rebuilding from
     * {@code variant}): between {@code minDistance} and {@code maxDistance} blocks away
     * horizontally, loaded, clear of players, the spawn platform and other damaged regions, and
     * containing enough geometry to make a visible difference.
     *
     * @param variant 0 to look for a region to remove, otherwise the seed it would be rebuilt from
     */
    @Nullable
    Region find(ServerLevel level, long seed, ServerPlayer player, RandomSource random, double minDistance, double maxDistance,
                int minSize, int maxSize, int minHeight, int maxHeight, long variant, long restoreAt) {
        NullWorldGeneration.Column probe = new NullWorldGeneration.Column();
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
            int cx = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int cz = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            NullWorldGeneration.column(seed, cx, cz, probe);
            int cy = nearestSolid(probe, Mth.floor(player.getY()) + random.nextInt(41) - 20, 48);
            if (cy < 0) {
                continue;
            }
            int sx = minSize + random.nextInt(maxSize - minSize + 1);
            int sy = minHeight + random.nextInt(maxHeight - minHeight + 1);
            int sz = minSize + random.nextInt(maxSize - minSize + 1);
            int x0 = cx - sx / 2;
            int y0 = Mth.clamp(cy - sy / 2, NullWorldGeneration.MIN_Y, NullWorldGeneration.MAX_Y - sy + 1);
            int z0 = cz - sz / 2;
            Region region = new Region(x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1, variant, restoreAt);
            if (acceptable(level, seed, region, variant == 0L ? 28 : 48)) {
                return region;
            }
        }
        return null;
    }

    private boolean acceptable(ServerLevel level, long seed, Region region, int minChanges) {
        if (region.maxX >= -NullWorldGeneration.CLEARANCE_RADIUS - 4 && region.minX <= NullWorldGeneration.CLEARANCE_RADIUS + 4
                && region.maxZ >= -NullWorldGeneration.CLEARANCE_RADIUS - 4 && region.minZ <= NullWorldGeneration.CLEARANCE_RADIUS + 4
                && region.maxY >= NullWorldGeneration.CLEARANCE_MIN_Y - 4 && region.minY <= NullWorldGeneration.CLEARANCE_MAX_Y + 4) {
            return false;
        }
        for (Region other : damaged) {
            if (region.overlaps(other, 2)) {
                return false;
            }
        }
        for (int cx = region.minX >> 4; cx <= region.maxX >> 4; cx++) {
            for (int cz = region.minZ >> 4; cz <= region.maxZ >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        int margin = region.shifted() ? PLAYER_MARGIN + 4 : PLAYER_MARGIN;
        if (playerNear(level, region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ, margin)) {
            return false;
        }
        return changes(seed, region, minChanges) >= minChanges;
    }

    /** How many positions of a region would change, counting up to {@code enough}. */
    private int changes(long seed, Region region, int enough) {
        int count = 0;
        for (int x = region.minX; x <= region.maxX; x++) {
            for (int z = region.minZ; z <= region.maxZ; z++) {
                NullWorldGeneration.column(seed, x, z, original);
                if (region.shifted()) {
                    NullWorldGeneration.column(region.variant, x, z, changed);
                }
                for (int y = region.minY; y <= region.maxY; y++) {
                    if (NullWorldGeneration.isProtected(x, y, z)) {
                        continue;
                    }
                    int base = NullWorldGeneration.idAt(original, y);
                    int wrong = region.shifted() ? NullWorldGeneration.idAt(changed, y) : NullWorldGeneration.AIR;
                    if (base != wrong && ++count >= enough) {
                        return count;
                    }
                }
            }
        }
        return count;
    }

    /** The non-air y nearest to {@code target} within {@code range}, or -1. */
    private static int nearestSolid(NullWorldGeneration.Column column, int target, int range) {
        for (int d = 0; d <= range; d++) {
            int up = target + d;
            if (up <= NullWorldGeneration.MAX_Y && up >= NullWorldGeneration.MIN_Y && NullWorldGeneration.idAt(column, up) != NullWorldGeneration.AIR) {
                return up;
            }
            int down = target - d;
            if (d > 0 && down >= NullWorldGeneration.MIN_Y && down <= NullWorldGeneration.MAX_Y
                    && NullWorldGeneration.idAt(column, down) != NullWorldGeneration.AIR) {
                return down;
            }
        }
        return -1;
    }

    /** True if any player of the level is within {@code margin} blocks of the box. */
    static boolean playerNear(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, double margin) {
        for (ServerPlayer player : level.players()) {
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double dx = Math.max(0.0, Math.max(minX - px, px - (maxX + 1)));
            double dy = Math.max(0.0, Math.max(minY - (py + 1.8), py - (maxY + 1)));
            double dz = Math.max(0.0, Math.max(minZ - pz, pz - (maxZ + 1)));
            if (dx * dx + dy * dy + dz * dz < margin * margin) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ persistence

    void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Region region : damaged) {
            CompoundTag entry = new CompoundTag();
            entry.putIntArray("box", new int[]{region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ});
            entry.putLong("variant", region.variant);
            list.add(entry);
        }
        tag.put("regions", list);
    }

    /** Reads regions left damaged by an earlier run; they all come back shortly. */
    void load(CompoundTag tag, long now) {
        damaged.clear();
        jobs.clear();
        ListTag list = tag.getList("regions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int[] box = entry.getIntArray("box");
            if (box.length != 6 || box[3] < box[0] || box[4] < box[1] || box[5] < box[2]
                    || box[3] - box[0] > 64 || box[4] - box[1] > 64 || box[5] - box[2] > 64) {
                continue;
            }
            damaged.add(new Region(box[0], box[1], box[2], box[3], box[4], box[5], entry.getLong("variant"), now + 40L + i * 10L));
        }
        dirty = false;
    }
}
