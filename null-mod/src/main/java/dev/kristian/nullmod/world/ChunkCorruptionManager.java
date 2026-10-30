package dev.kristian.nullmod.world;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Background terrain corruption. Every few seconds each player gets a chance (from the stage,
 * how corrupted their region is and the world corruption setting) that a small patch of natural
 * ground 24-80 blocks away stops loading properly: black blocks, missing textures, error blocks
 * and corrupted versions of whatever was there. Stage 1 patches are extremely rare and only a
 * block or three; they grow with the stages. From Stage 3 corruption also spreads outward from
 * the places it took hold earlier (a bounded list kept in the world's NULL data).
 * <p>
 * Nothing is ever scanned chunk-wide: columns are sampled through the heightmaps of loaded
 * chunks, and every change goes through {@link SafeBlockPlacer}.
 */
public final class ChunkCorruptionManager implements CorruptionSystem {
    public static final ChunkCorruptionManager INSTANCE = new ChunkCorruptionManager();
    public static final String NAME = "world_corruption";

    private static final int PASS_PERIOD = 20;
    private static final int CHECK_MIN_TICKS = 80;
    private static final int CHECK_JITTER_TICKS = 60;
    /** Chance per player check of a new patch, per stage, before region and config scaling. */
    private static final float[] PATCH_CHANCE = {0.006F, 0.03F, 0.06F, 0.09F, 0.11F};
    /** Chance per player check that known corruption grows (Stage 3 onwards). */
    private static final float[] SPREAD_CHANCE = {0.0F, 0.0F, 0.12F, 0.18F, 0.24F};
    private static final int[] SIZE_MIN = {1, 2, 4, 6, 8};
    private static final int[] SIZE_MAX = {3, 7, 12, 18, 24};
    /** How far one infection may grow from where it started, per stage. */
    private static final int[] SPOT_RADIUS_LIMIT = {3, 6, 10, 16, 22};
    private static final int MAX_SPOTS = 48;
    private static final double SPOT_REACH = 112.0;
    private static final int MAX_FAILURES = 4;

    private final RandomSource random = RandomSource.create();
    private final Map<UUID, Long> nextCheck = new HashMap<>();
    private final List<Spot> spots = new ArrayList<>();
    private boolean loaded;

    /** A place where corruption took hold. From Stage 3 it grows outward from here. */
    public static final class Spot {
        private final ResourceKey<Level> dimension;
        private final BlockPos center;
        private int radius;
        private long lastGrowth;
        private int failures;

        Spot(ResourceKey<Level> dimension, BlockPos center, int radius, long lastGrowth, int failures) {
            this.dimension = dimension;
            this.center = center;
            this.radius = radius;
            this.lastGrowth = lastGrowth;
            this.failures = failures;
        }

        public ResourceKey<Level> dimension() {
            return dimension;
        }

        public BlockPos center() {
            return center;
        }

        public int radius() {
            return radius;
        }
    }

    private ChunkCorruptionManager() {
    }

    @Override
    public String name() {
        return NAME;
    }

    // ------------------------------------------------------------------ CorruptionSystem

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        if (server.getTickCount() % PASS_PERIOD != 0) {
            return;
        }
        ensureLoaded(ctx.data());
        if (ctx.paused() || !NullConfig.mayReplaceNatural()) {
            return;
        }
        double setting = NullConfig.get(NullConfig.WORLD_CORRUPTION_INTENSITY);
        if (setting <= 0.0) {
            return;
        }
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (!LocationFinder.canHaveSurface(level)) {
                continue;
            }
            Long next = nextCheck.get(player.getUUID());
            if (next == null) {
                nextCheck.put(player.getUUID(), now + CHECK_MIN_TICKS + random.nextInt(CHECK_JITTER_TICKS * 2));
                continue;
            }
            if (now < next) {
                continue;
            }
            nextCheck.put(player.getUUID(), now + CHECK_MIN_TICKS + random.nextInt(CHECK_JITTER_TICKS));
            checkPlayer(ctx, level, player, setting);
        }
    }

    private void checkPlayer(CorruptionContext ctx, ServerLevel level, ServerPlayer player, double setting) {
        int stage = ctx.stage().number();
        float local = ctx.localCorruption(player.blockPosition());
        float scale = (float) setting * (0.35F + 1.3F * local);
        float spread = SPREAD_CHANCE[stage - 1] * scale;
        if (spread > 0.0F && random.nextFloat() < spread) {
            Spot spot = nearestSpot(level, player.blockPosition(), SPOT_REACH, stage);
            if (spot != null && grow(level, spot, stage, growthAmount(stage, local, random), random, null)) {
                return;
            }
        }
        if (random.nextFloat() >= PATCH_CHANCE[stage - 1] * scale) {
            return;
        }
        BlockPos center = LocationFinder.findGround(level, player, random, 24.0, 80.0, LocationFinder.View.HIDDEN, 6, pos -> true);
        if (center != null) {
            submitPatch(level, center, patchSize(stage, local, random), stage, random, null);
        }
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        nextCheck.remove(player.getUUID());
    }

    @Override
    public void onReset(MinecraftServer server) {
        spots.clear();
        nextCheck.clear();
        loaded = false;
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        spots.clear();
        nextCheck.clear();
        loaded = false;
    }

    // ------------------------------------------------------------------ event entry points

    /** corruption_patch: a patch of natural ground near the player stops loading properly. */
    public boolean corruptNear(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource rnd = req.random();
        int stage = req.stageNumber();
        int size = patchSize(stage, req.intensity(), rnd);
        if (req.forced()) {
            size = Math.max(size, SIZE_MIN[stage - 1] + 2);
        }
        double min = req.minDistance(16.0, 6.0);
        double max = req.forced() ? 48.0 : 64.0;
        LocationFinder.View view = req.view(LocationFinder.View.HIDDEN);
        int attempts = req.attempts(3);
        UUID who = req.player().getUUID();
        MinecraftServer server = req.server();
        for (int i = 0; i < attempts; i++) {
            BlockPos center = LocationFinder.findGround(level, req.player(), rnd, min, max, view, 4, pos -> true);
            if (center == null) {
                continue;
            }
            String detail = BuiltInRegistries.BLOCK.getKey(level.getBlockState(center).getBlock()).getPath();
            if (submitPatch(level, center, size, stage, rnd, result -> {
                if (result.anyPlaced() && !result.aborted()) {
                    WorldMemory.remember(server, "world:corruption", who, detail);
                }
            })) {
                return true;
            }
        }
        return false;
    }

    /** infection_spread: corruption grows outward from somewhere it already took hold. */
    public boolean spreadNear(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        ensureLoaded(CorruptionManager.data(req.server()));
        int stage = req.stageNumber();
        int amount = growthAmount(stage, req.intensity(), req.random()) * 2;
        Spot spot = nearestSpot(level, req.player().blockPosition(), req.forced() ? 160.0 : SPOT_REACH, stage);
        if (spot != null && grow(level, spot, stage, amount, req.random(), null)) {
            return true;
        }
        // Forced with nothing to grow from: corruption takes hold here instead.
        return req.forced() && corruptNear(req);
    }

    // ------------------------------------------------------------------ patches

    /** Patch size in blocks for a stage, scaled by how corrupted the region is (0..1). */
    public static int patchSize(int stage, float local, RandomSource random) {
        int s = Mth.clamp(stage, 1, 5) - 1;
        int base = LocationFinder.between(random, SIZE_MIN[s], SIZE_MAX[s]);
        int size = Math.round(base * (0.7F + 0.6F * Mth.clamp(local, 0.0F, 1.0F)));
        return Mth.clamp(size, 1, SIZE_MAX[s]);
    }

    private static int growthAmount(int stage, float local, RandomSource random) {
        int s = Mth.clamp(stage, 1, 5);
        int base = 2 + (s - 3) * 2 + random.nextInt(3 + s);
        return Math.max(2, Math.round(base * (0.7F + 0.6F * Mth.clamp(local, 0.0F, 1.0F))));
    }

    /**
     * Plans a patch of about {@code size} surface blocks around {@code center}, or returns null
     * if the area is not loaded or not natural. Nothing is changed until the job runs.
     */
    @Nullable
    public PlacementJob buildPatch(ServerLevel level, BlockPos center, int size, int stage, RandomSource rnd) {
        int reach = 2 + 2 * (int) Math.ceil(Math.sqrt(Math.max(1, size)));
        int cx = center.getX();
        int cz = center.getZ();
        if (!LocationFinder.areaLoaded(level, cx - reach - 1, cz - reach - 1, cx + reach + 1, cz + reach + 1)) {
            return null;
        }
        List<BlockPos> cells = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<int[]> frontier = new ArrayList<>();
        frontier.add(new int[]{cx, cz});
        seen.add(ChunkPos.asLong(cx, cz));
        int guard = 0;
        while (cells.size() < size && !frontier.isEmpty() && guard++ < size * 16 + 16) {
            // Mostly the oldest candidates (a compact blob), sometimes any (ragged edges).
            int index = rnd.nextFloat() < 0.7F ? rnd.nextInt(Math.min(frontier.size(), 3)) : rnd.nextInt(frontier.size());
            int[] c = frontier.remove(index);
            if (Math.abs(c[0] - cx) > reach || Math.abs(c[1] - cz) > reach) {
                continue;
            }
            int y = LocationFinder.groundY(level, c[0], c[1]);
            if (y == LocationFinder.NONE || Math.abs(y - center.getY()) > 2) {
                continue;
            }
            BlockPos pos = new BlockPos(c[0], y, c[1]);
            if (!LocationFinder.isGroundBlock(level.getBlockState(pos))) {
                continue;
            }
            cells.add(pos);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                int nx = c[0] + side.getStepX();
                int nz = c[1] + side.getStepZ();
                if (seen.add(ChunkPos.asLong(nx, nz))) {
                    frontier.add(new int[]{nx, nz});
                }
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        double furthest = 1.0;
        for (BlockPos cell : cells) {
            furthest = Math.max(furthest, Math.sqrt(Mth.square(cell.getX() - cx) + Mth.square(cell.getZ() - cz)) + 1.0);
        }
        PlacementJob job = new PlacementJob(level, "corruption_patch");
        for (BlockPos cell : cells) {
            double d = Math.sqrt(Mth.square(cell.getX() - cx) + Mth.square(cell.getZ() - cz));
            float core = (float) (1.0 - d / furthest);
            BlockState natural = level.getBlockState(cell);
            job.replace(cell, CorruptionPalette.corrupt(natural, rnd, stage, core));
            // In bigger patches the heart goes down into the ground as well.
            if (size >= 6 && core > 0.45F && rnd.nextFloat() < 0.6F) {
                BlockPos below = cell.below();
                BlockState under = level.getBlockState(below);
                if (LocationFinder.isGroundBlock(under)) {
                    job.replace(below, CorruptionPalette.corrupt(under, rnd, stage, core));
                }
            }
        }
        if (stage >= 2 && rnd.nextFloat() < 0.3F) {
            addCanopy(level, job, center, stage, rnd, reach);
        }
        return job;
    }

    /** Sometimes the tree standing over a patch is caught as well. */
    private static void addCanopy(ServerLevel level, PlacementJob job, BlockPos center, int stage, RandomSource rnd, int reach) {
        for (int attempt = 0; attempt < 6; attempt++) {
            int x = center.getX() + rnd.nextInt(reach * 2 + 1) - reach;
            int z = center.getZ() + rnd.nextInt(reach * 2 + 1) - reach;
            int top = LocationFinder.surfaceY(level, x, z);
            int ground = LocationFinder.groundY(level, x, z);
            if (top == LocationFinder.NONE || ground == LocationFinder.NONE || top - ground < 2 || top - ground > 24) {
                continue;
            }
            BlockPos leaf = new BlockPos(x, top, z);
            if (!level.getBlockState(leaf).is(BlockTags.LEAVES)) {
                continue;
            }
            int count = 2 + rnd.nextInt(3 + stage);
            for (int i = 0; i < count; i++) {
                BlockPos p = leaf.offset(rnd.nextInt(3) - 1, -rnd.nextInt(2), rnd.nextInt(3) - 1);
                BlockState s = level.getBlockState(p);
                if (s.is(BlockTags.LEAVES) && BuildProtection.isNatural(s)) {
                    job.replace(p, CorruptionPalette.corrupt(s, rnd, stage, 0.3F));
                }
            }
            return;
        }
    }

    /**
     * Plans and queues a patch. When it has placed anything the spot is remembered, so later
     * stages can grow from it. {@code after} (optional) sees the job's result.
     */
    public boolean submitPatch(ServerLevel level, BlockPos center, int size, int stage, RandomSource rnd,
                               @Nullable Consumer<PlacementJob.Result> after) {
        PlacementJob job = buildPatch(level, center, size, stage, rnd);
        if (job == null) {
            return false;
        }
        int radius = Math.max(1, Math.max(Math.max(job.max().getX() - center.getX(), center.getX() - job.min().getX()),
                Math.max(job.max().getZ() - center.getZ(), center.getZ() - job.min().getZ())));
        MinecraftServer server = level.getServer();
        ResourceKey<Level> dimension = level.dimension();
        BlockPos anchor = center.immutable();
        job.onComplete(result -> {
            if (result.anyPlaced() && !result.aborted()) {
                addSpot(server, dimension, anchor, radius);
            }
            if (after != null) {
                after.accept(result);
            }
        });
        return SafeBlockPlacer.INSTANCE.submit(job);
    }

    // ------------------------------------------------------------------ infection

    /**
     * Grows corruption outward from a spot: short wandering rays start in the corrupted heart
     * and infect the first natural ground they reach past its edge, which gives the infection
     * ragged, finger-like borders. Returns false if nothing could grow.
     */
    public boolean grow(ServerLevel level, Spot spot, int stage, int amount, RandomSource rnd,
                        @Nullable Consumer<PlacementJob.Result> after) {
        int limit = SPOT_RADIUS_LIMIT[Mth.clamp(stage, 1, 5) - 1];
        BlockPos c = spot.center;
        int reach = Math.min(limit, spot.radius + 4) + 1;
        if (!spot.dimension.equals(level.dimension())
                || !LocationFinder.areaLoaded(level, c.getX() - reach - 1, c.getZ() - reach - 1, c.getX() + reach + 1, c.getZ() + reach + 1)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        PlacementJob job = new PlacementJob(level, "infection_spread");
        Set<Long> chosen = new HashSet<>();
        boolean foundCorruption = false;
        int furthest = spot.radius;
        int rays = amount * 3;
        for (int r = 0; r < rays && chosen.size() < amount; r++) {
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            double x = c.getX() + 0.5;
            double z = c.getZ() + 0.5;
            int lastY = c.getY();
            boolean onCorruption = false;
            for (int step = 0; step <= reach; step++) {
                int bx = Mth.floor(x);
                int bz = Mth.floor(z);
                double dist = Math.sqrt(Mth.square(bx - c.getX()) + Mth.square(bz - c.getZ()));
                if (dist > limit) {
                    break;
                }
                int y = LocationFinder.groundY(level, bx, bz);
                if (y == LocationFinder.NONE || Math.abs(y - lastY) > 2) {
                    break;
                }
                BlockPos pos = new BlockPos(bx, y, bz);
                BlockState state = level.getBlockState(pos);
                if (CorruptionPalette.isCorruption(state)) {
                    onCorruption = true;
                    foundCorruption = true;
                    lastY = y;
                } else if (onCorruption && LocationFinder.isGroundBlock(state)) {
                    if (chosen.add(pos.asLong())) {
                        job.replace(pos, CorruptionPalette.corrupt(state, rnd, stage, 0.15F + 0.3F * rnd.nextFloat()));
                        furthest = Math.max(furthest, (int) Math.ceil(dist));
                        infectCanopy(level, job, bx, bz, y, stage, rnd);
                    }
                    break;
                } else if (onCorruption || step > 2) {
                    // Water, something built, or the heart was dug out: this ray stops.
                    break;
                }
                angle += (rnd.nextDouble() - 0.5) * 0.7;
                x += Math.cos(angle);
                z += Math.sin(angle);
            }
        }
        long now = server.overworld().getGameTime();
        if (!foundCorruption) {
            // The corruption here was dug away. Forget the spot after a few tries.
            if (++spot.failures >= MAX_FAILURES) {
                spots.remove(spot);
            }
            save(server);
            return false;
        }
        if (job.isEmpty()) {
            spot.lastGrowth = now;
            return false;
        }
        int grownTo = furthest;
        job.onComplete(result -> {
            if (result.anyPlaced() && !result.aborted()) {
                spot.radius = Math.max(spot.radius, grownTo);
                spot.lastGrowth = server.overworld().getGameTime();
                spot.failures = 0;
                save(server);
            }
            if (after != null) {
                after.accept(result);
            }
        });
        return SafeBlockPlacer.INSTANCE.submit(job);
    }

    private static void infectCanopy(ServerLevel level, PlacementJob job, int x, int z, int groundY, int stage, RandomSource rnd) {
        if (rnd.nextFloat() >= 0.25F) {
            return;
        }
        int top = LocationFinder.surfaceY(level, x, z);
        if (top == LocationFinder.NONE || top <= groundY + 2 || top > groundY + 24) {
            return;
        }
        BlockPos leaf = new BlockPos(x, top, z);
        BlockState state = level.getBlockState(leaf);
        if (state.is(BlockTags.LEAVES) && BuildProtection.isNatural(state)) {
            job.replace(leaf, CorruptionPalette.corrupt(state, rnd, stage, 0.2F));
        }
    }

    /** The nearest known spot in this level that can still grow, within {@code reach} blocks. */
    @Nullable
    public Spot nearestSpot(ServerLevel level, BlockPos pos, double reach, int stage) {
        int limit = SPOT_RADIUS_LIMIT[Mth.clamp(stage, 1, 5) - 1];
        Spot best = null;
        double bestDist = reach * reach;
        for (Spot spot : spots) {
            if (!spot.dimension.equals(level.dimension()) || spot.radius >= limit) {
                continue;
            }
            double d = spot.center.distSqr(pos);
            if (d <= bestDist) {
                bestDist = d;
                best = spot;
            }
        }
        return best;
    }

    /** Known spots (read-only view), e.g. for NULLROOT growing near corrupted ground. */
    public List<Spot> spots(MinecraftServer server) {
        ensureLoaded(CorruptionManager.data(server));
        return Collections.unmodifiableList(spots);
    }

    void addSpot(MinecraftServer server, ResourceKey<Level> dimension, BlockPos center, int radius) {
        ensureLoaded(CorruptionManager.data(server));
        long now = server.overworld().getGameTime();
        for (Spot spot : spots) {
            if (spot.dimension.equals(dimension) && spot.center.distSqr(center) < 36.0) {
                spot.radius = Math.max(spot.radius, radius);
                spot.lastGrowth = now;
                spot.failures = 0;
                save(server);
                return;
            }
        }
        if (spots.size() >= MAX_SPOTS) {
            Spot oldest = spots.get(0);
            for (Spot spot : spots) {
                if (spot.lastGrowth < oldest.lastGrowth) {
                    oldest = spot;
                }
            }
            spots.remove(oldest);
        }
        spots.add(new Spot(dimension, center.immutable(), Math.max(1, radius), now, 0));
        save(server);
    }

    // ------------------------------------------------------------------ persistence

    private void ensureLoaded(CorruptionData data) {
        if (loaded) {
            return;
        }
        loaded = true;
        spots.clear();
        ListTag list = data.systemTag(NAME).getList("spots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && spots.size() < MAX_SPOTS; i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("dim"));
            if (id == null) {
                continue;
            }
            spots.add(new Spot(ResourceKey.create(Registries.DIMENSION, id), BlockPos.of(entry.getLong("pos")),
                    Math.max(1, entry.getInt("r")), entry.getLong("t"), entry.getInt("f")));
        }
    }

    private void save(MinecraftServer server) {
        CorruptionData data = CorruptionManager.data(server);
        ListTag list = new ListTag();
        for (Spot spot : spots) {
            CompoundTag entry = new CompoundTag();
            entry.putString("dim", spot.dimension.location().toString());
            entry.putLong("pos", spot.center.asLong());
            entry.putInt("r", spot.radius);
            entry.putLong("t", spot.lastGrowth);
            entry.putInt("f", spot.failures);
            list.add(entry);
        }
        data.systemTag(NAME).put("spots", list);
        data.setDirty();
    }
}
